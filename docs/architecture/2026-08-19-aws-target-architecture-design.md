# EasyCRM on AWS — Target Architecture (design spec)

**Date:** 2026-08-19
**Status:** Design. Nothing in this document is built. It describes an **end state**, not a
migration path — sequencing is deliberately deferred to Part 5.
**Scope:** the whole system re-platformed onto AWS as five ECS services, with asynchronous
messaging, observability, metric-driven scaling and scheduled work.

## How this relates to the other architecture docs

| Doc | What it is |
|---|---|
| `2026-07-29-current-architecture.md` | What exists on `main` today: **one modular monolith, one Postgres, no broker** |
| `2026-07-29-target-architecture.md` | The completed *product* on the same monolithic shape |
| `2026-08-05-interview-challenges-and-aws-kafka.md` | The MSK/Kafka variant of this question |
| **this doc** | The AWS/ECS variant: five services, SNS/SQS, RDS Proxy, CloudWatch |

Read Part 4 before Part 1 if you want the short version. This design costs roughly **eight times**
the monolith and buys nothing a user can see at the current scale. It is built here because
the exercise is to build it well, and Part 4 states the case against it as plainly as the rest of
the document states the case for.

---

# Part 0 — Decisions, and why

Every one of these was a live choice. The alternatives are recorded so nobody has to reconstruct
the reasoning later.

| # | Decision | Rejected | Why |
|---|---|---|---|
| D1 | **Five services**: identity, master-data, sales, document, notification | Six (splitting quotation from order); three coarse; a CQRS read/write cut | Splitting `sales` would turn `QuotationService.accept` — today one local transaction that creates the order and flips the quotation — into a saga with compensation. That invariant is currently structural; a saga makes it procedural. `document` *is* split out, because CPU-bound bursty PDF rendering is a genuinely different scaling profile and it owns the only unauthenticated route |
| D2 | **One RDS Postgres, schema per service, role per service** | Database-per-service; shared schema | Keeps RLS, `@TenantId` and the ArchUnit rule intact — the split must not weaken isolation. One replication story, one backup story, one Flyway pattern. Database-per-service is the correct end state only if a service ever needs to move regions or scale independently at the storage layer |
| D3 | **Transactional outbox → relay → SNS FIFO → SQS FIFO** | Kinesis + DMS CDC; direct SDK publish from the transaction | See D4. Direct publish reintroduces the dual-write problem the outbox exists to solve |
| D4 | **No Kinesis, no DMS CDC** | Outbox → DMS CDC → Kinesis (the original ask) | At 100 tenants the event rate is ~0.17/sec against a 1,000/sec minimum shard. Kinesis' consumer parallelism is capped at shard count; SQS's is not. SQS gives native DLQ, redrive and visibility timeouts that Kinesis makes you build. Removing DMS also removes the worst failure mode in the design (F6). Recorded as a reversal of the original requirement — Part 4.4 states what was given up |
| D5 | **In-process `@Scheduled` + ShedLock** | EventBridge Scheduler → ECS RunTask; → Lambda; Step Functions | Zero new infrastructure and zero cold start. Its one real cost — batch CPU polluting the autoscaling signal — is paid for by scaling on request rate and backlog age rather than CPU (D8) |
| D6 | **ECS Service Connect** | Cloud Map + Resilience4j; internal ALB per service | Retries, timeouts and outlier ejection become task-definition config rather than code repeated in five services, and per-service RPS/latency/5xx land in CloudWatch for free |
| D7 | **One CloudFront distribution: SPA + API + public route** | Split CloudFront/ALB planes; API Gateway | Makes `/public/q/*` edge-cacheable (it renders byte-identically per frozen version), and same-origin removes a CORS preflight from every mutation. The `/api/*` cache policy becomes a security control — see F10 |
| D8 | **Scale on request rate and backlog age, never CPU** | CPU target tracking | Forced by D5: a 90-second expiry sweep inside a serving container would otherwise trigger a scale-out that serves nothing |
| D9 | **`audit_log` removed** | Keep it synchronous; move it async | User decision. `LOGIN_FAILED` was a real security control and becomes a structured CloudWatch log line with a metric filter and alarm |
| D10 | **Freeze a buyer snapshot into `QuotationVersion`** | Read the customer live at render time | Fixes a live correctness bug (F11) *and* removes `document-svc`'s synchronous dependency on `master-data` from the cached public render path. Prerequisite of the split |
| D11 | **RS256 + JWKS** | Keep HS256 | A shared symmetric secret across five services means every service can *mint* tokens, not merely verify them |
| D13 | **CodeDeploy blue/green**, except `notification` | ECS rolling + circuit breaker | A replacement task set validated by a `BeforeAllowTraffic` hook before it takes any traffic, and a rollback that shifts the listener back in seconds rather than draining tasks. `notification` has no ALB target, so blue/green does not apply to it — it stays rolling. The mixed strategy is forced, not chosen. Cost: two target groups per service, an `appspec.yaml` each, CodeDeploy owning the ECS service, and **double the tasks during a deploy** (F17) |
| D14 | **Migrations run pre-deploy as a one-off ECS task, as the schema owner, directly to RDS** | Flyway at application startup | Startup Flyway makes N tasks race the lock and offers no place to fail before traffic shifts. The owner credential is never registered on the Proxy, and long DDL transactions should not be multiplexed (F3) |
| D15 | **Terraform owns roles and credentials; Flyway owns schemas, grants and objects** | Flyway owns everything, as `V1__roles_and_extensions.sql` does today; Terraform owns grants too | A password is a rotation concern, a schema is a migration concern. Splitting them there keeps secrets out of version-controlled SQL without scattering grants across two tools that must stay in step |
| D12 | **One monorepo, Gradle multi-project, path-filtered CI** | A repository per service | "Independent deploys" is a pipeline property, not a repository property — path filters give them without giving up atomic refactors. `platform` becomes `implementation(project(":platform"))` rather than a published artifact, so a change to it and all five consumers is one commit. Contract tests fail in the same CI run as the change that breaks them, rather than later via a broker. Polyrepo wins on different team cadences, mixed toolchains, or repo scale — none of which apply |

---

# Part 1 — High-Level Design

## 1.1 Region and residency

**ap-south-1 (Mumbai), single region.** Customers are Indian distributors; the DPDP Act makes
data residency a requirement rather than a latency preference. Multi-AZ within the region; no
cross-region replication in this design.

## 1.2 Topology

```
                        CloudFront  (app.easycrm.in)
                             │
        ┌────────────────────┼────────────────────────┐
   default behavior     /api/*                   /public/q/*
        │                    │                        │
      S3 (OAC)          VPC origin               VPC origin
   React build        CachingDisabled          cached, bounded TTL
                      fwd Authorization        Authorization NOT forwarded
                             │                        │
                             └──────────┬─────────────┘
                                        │
                             internal ALB (path routing)
                                        │
    ┌─────────────┬──────────────┬──────┴───────┬──────────────────┐
 identity     master-data      sales        document          notification
  (2 tasks)    (2 tasks)      (2–10)        (1–20)          (1–N, no ALB target)
    └──────────────── ECS Service Connect (managed Envoy) ────────────────┘
                                        │
                                  RDS Proxy
                                        │
                          RDS PostgreSQL 16, Multi-AZ
                                        │
                    schemas: identity · master_data · sales · document
```

### ALB routing table (R4)

| Path | Service |
|---|---|
| `/api/v1/auth/*`, `/api/v1/users/*`, `/api/v1/subscription`, `/api/v1/plans` | identity |
| `/api/v1/customers/*`, `/api/v1/products/*`, `/api/v1/price-lists/*`, `/api/v1/contacts/*` | master-data |
| `/api/v1/enquiries/*`, `/api/v1/quotations/*`, `/api/v1/orders/*` | sales |
| `/api/v1/documents/*` | document |
| `/public/q/*` | document |
| `/public/webhooks/*` | identity |

**This forces an API change.** Today the PDF and share endpoints are
`GET /api/v1/quotations/{id}/pdf` and `POST /api/v1/quotations/{id}/share` — under the
`/api/v1/quotations/*` prefix, which belongs to `sales`. Path-based routing cannot split a subtree
from its parent, so they move to `/api/v1/documents/quotations/{id}/pdf` and `.../share`.

The cost of that change is currently zero: no frontend exists to break. It will not be zero later.

Because CloudFront uses **VPC origins**, the ALB is internal and has no public listener. There is
no public entry point to the VPC at all, so the usual "lock the ALB to CloudFront with a secret
header" workaround is unnecessary.

`notification-svc` is not an ALB target. It is an SQS consumer, not an HTTP service.

## 1.3 Services

| Service | Owns (packages today) | Owns (schema) | Scaling profile |
|---|---|---|---|
| **identity** | `iam`, `tenant` | `identity` | Flat. Login/refresh only |
| **master-data** | `catalog`, `crm` | `master_data` | Read-heavy, low volume. Called by sales on every quotation write |
| **sales** | `sales` (enquiry, quotation, order) | `sales` | The main write path. Diurnal |
| **document** | `sales.pdf`, `share_link`, `render_payload`, `/public/q/*` | `document` | **Bursty, CPU-bound.** The reason a split is defensible at all |
| **notification** | new | — (uses `sales` events) | Driven by SQS backlog |

Shared `platform` code (tenancy, money, error, security, persistence) stays a Gradle module inside
the monorepo (D12), consumed as `implementation(project(":platform"))` — not a published artifact.

The rule that keeps it a platform rather than a junk drawer: **`platform` may contain mechanisms,
never meanings.** Tenancy is a mechanism; a customer is a meaning. A shared `Quotation` type is how
a split becomes a distributed monolith, where adding a field forces a five-service release. One
ArchUnit rule — `platform` may not reference any service package — enforces it.

Repository layout:

```
easy-crm/
├── platform/              one module; ArchUnit forbids it referencing any service
├── services/
│   ├── identity/          + billing, subscription, webhooks
│   ├── master-data/
│   ├── sales/
│   ├── document/
│   └── notification/
├── contracts/             event schemas + OpenAPI, versioned, additive-only
├── infra/                 terraform
└── docs/
```

## 1.4 Network

| Tier | Contents | Route |
|---|---|---|
| Public | NAT gateway only | IGW |
| Private-with-egress | ECS tasks | NAT + VPC endpoints |
| Isolated | RDS, RDS Proxy | none |

VPC **gateway** endpoint for S3 (free). VPC **interface** endpoints for ECR api/dkr, CloudWatch
Logs, Secrets Manager, SQS, SNS, KMS — roughly $9.50/month each, which is comparable to a second
NAT gateway but keeps AWS-service traffic off the public internet. That matters for the DPDP
argument, not just the bill.

---

# Part 2 — Low-Level Design

## 2.1 Data plane

### Schema and role matrix

One database. Four schemas. One non-owner role per service with `USAGE` on its own schema only.

```
rds-proxy
  ├── identity_app     → schema identity      (app_user, refresh_token, subscription, usage_counter)
  ├── master_data_app  → schema master_data   (product, customer, contact, price_list, price_list_item)
  ├── sales_app        → schema sales         (enquiry, quotation, quotation_version, quotation_item,
  │                                            sales_order, document_counter, outbox)
  ├── document_app     → schema document      (share_link, render_payload, outbox)
  ├── relay_app        → BYPASSRLS, SELECT/UPDATE on *.outbox ONLY        (R1)
  └── every app role   → SELECT on schema shared (tenant, plan)           (R2)
                         INSERT/UPDATE on shared.shedlock
```

### The `shared` schema (R2)

Three tables are genuinely cross-service and cannot live inside one service's schema:

| Table | Written by | Read by |
|---|---|---|
| `tenant` | identity | **every service** — scheduled jobs enumerate tenants to loop over |
| `plan` | identity (seeded) | every service — entitlement limits |
| `shedlock` | every service | every service |

They live in a `shared` schema with `SELECT` granted to every service role and write access granted
only to the owner. Without this, §2.4's "every job loops tenants explicitly" is impossible: `tenant`
would sit in the `identity` schema, which `sales_app` cannot reach.

`shared` is the *only* schema any service may read outside its own. That single exception is
explicit, enumerable and reviewable — which is the point.

Every existing isolation control survives unchanged: `TenantScopedEntity`, Hibernate `@TenantId`,
RLS policies keyed on `NULLIF(current_setting('app.current_tenant', true), '')::uuid`, and the
ArchUnit rule that fails the build for an entity that is neither tenant-scoped nor allowlisted.
**The split does not touch tenant isolation.** That is why D2 was chosen over
database-per-service.

The role grants do one extra job for free: a service's role has **no privileges outside its own
schema**, so a cross-schema migration fails rather than succeeding quietly. Schema ownership
enforces itself, in the same spirit as tenant scoping.

### F1 — the existing RLS mechanism is what makes RDS Proxy viable

RDS Proxy multiplexes client connections onto a smaller pool of database connections, reusing a
backend connection at transaction boundaries. It **pins** — permanently binds a backend connection
to one client session, disabling multiplexing — whenever it observes session state it cannot
reason about.

`TenantAwareTransactionManager` sets the tenant with:

```sql
SELECT set_config('app.current_tenant', :tid, true)
```

Two properties make this safe. It is a **function call, not a `SET` statement**, so the proxy's
statement inspection has nothing to pin on. And `is_local => true` discards the value at
commit or rollback, so even if a backend connection were reused, no tenant value can survive into
another session.

Challenge #9 forced this shape for a Hibernate reason — a session's tenant is resolved once at
session-open and never re-read. It turns out to be the only shape compatible with connection
multiplexing.

**This is verified by a metric, not by argument.** `DatabaseConnectionsCurrentlySessionPinned`
must sit at zero. If it does not, the Proxy is pure cost and added latency.

### F2 — two things that would pin every connection

**PgJDBC server-side prepared statements.** Hibernate re-executes identical SQL constantly. Past
`prepareThreshold` (default 5) PgJDBC issues a real `PREPARE`, which pins. The fix is one JDBC URL
parameter:

```
jdbc:postgresql://<proxy-endpoint>/easycrm?prepareThreshold=0
```

Nothing in the application reports this. Throughput simply stops scaling with task count.

**Session-level advisory locks.** ShedLock must use the table-based `JdbcTemplateLockProvider`.
Any advisory-lock-based locking (including a hand-rolled `pg_advisory_lock`) holds session state
for the whole duration of the job and pins the connection behind it. Given D5 puts scheduled work
in-process, this is load-bearing.

The `shedlock` table is **global and RLS-exempt**, joining `refresh_token` and `share_link` in the
ArchUnit `GLOBAL_TABLES` allowlist.

### F3 — connections that must bypass the Proxy

- **Flyway** runs as the schema *owner* with long DDL transactions. That credential is never
  registered on the Proxy; migrations connect directly to the instance.
- **Any `LISTEN/NOTIFY`** (see 2.3 — the tempting optimisation for the outbox relay) is session
  state and pins. Poll, or listen on a direct connection.

### F9 — the autoscaler's real ceiling is the database

```
max_tasks × deploy_factor × hikari_pool_size  ≤  MaxConnectionsPercent × max_connections
```

**`deploy_factor` is 2 for every blue/green service** (D13): a replacement task set runs alongside
the original until traffic shifts and the bake completes. Sizing the pool for steady state and then
deploying is how you exhaust the connection budget with a deployment — see F17.

| Service | Max tasks | Deploy factor | Pool | Peak connections |
|---|---:|---:|---:|---:|
| identity | 4 | ×2 | 4 | 32 |
| master-data | 4 | ×2 | 4 | 32 |
| sales | 8 | ×2 | 8 | 128 |
| document | 20 | ×2 | **3** | 120 |
| notification | 6 | ×1 (rolling) | 4 | 24 |
| **Peak, mid-deploy at full scale-out** | | | | **336** |

`document`'s pool is deliberately the smallest despite having the highest task ceiling: rendering is
**CPU-bound**, and a task reads one `render_payload` row and then spends its time in openhtmltopdf.
Uniform pool sizing would have made the busiest-scaling service the largest connection consumer for
no reason.

A `db.t4g.medium` (4 GiB) permits roughly 450 connections by the RDS default formula; budgeting 75%
of that through the Proxy gives ~340. **336 against 340 is tight, not comfortable** — the levers, in
order, are `document`'s pool, `sales`'s task ceiling, and moving to `db.t4g.large`.

Without this table written down, autoscaling does not prevent an outage — it moves the outage from
one service's ALB to Postgres, where it takes down all five.

### Cross-service references

There are **no foreign keys anywhere in the current schema** — 25 migrations, zero `REFERENCES`,
zero `@ManyToOne`. Every cross-aggregate reference is a bare `UUID` validated in application code.
The extraction seam was already cut at the data layer.

What changes is that `customers.findById()` — an RLS-scoped read inside the caller's transaction —
becomes a network call.

| Call site | Needs | After the split |
|---|---|---|
| `QuotationService.create` | `customer.stateCode` (CGST+SGST vs IGST) | Service Connect → master-data |
| `QuotationService` revise/edit | same | Service Connect → master-data |
| `PriceResolver.resolve` | price list + item | Service Connect → master-data |
| `QuotationPdfService` | buyer name, GSTIN, address | **none** — read from the frozen snapshot (D10) |

Four mechanisms replace the in-transaction read, matched to what each piece of data is for:

1. **Snapshot anything that must not change.** `QuotationVersion` already freezes items, totals and
   `placeOfSupply`. D10 adds the buyer snapshot.
2. **Validate at the write path.** `create` already calls master-data for `stateCode`; validation is
   that same call. Missing customer → 422. **If master-data is unavailable, quotation creation
   fails fast** — a quotation with the wrong tax split is worse than no quotation.
3. **Propagate by event.** `CustomerUpdated`/`CustomerArchived` flow through the outbox; sales
   consumes. Deletion becomes archival.
4. **Reconcile and alarm.** A nightly sweep emits `orphaned_references`, alarmed at > 0. This is
   detection, not prevention, and is genuinely weaker than a constraint.

### F11 — a live bug that the split forces you to fix

`QuotationVersion` freezes items, totals and `placeOfSupply`, but `QuotationPdfService` reads
`businessName`, `gstin` and `billingAddress` **live** from `customer` at render time. Edit a
customer's address, re-render a `SENT` quotation, and the same frozen version produces a different
document — including through a public share link the customer already holds. Challenge #28
guarantees byte-determinism across renders, not across customer edits.

Freezing a `buyer_snapshot` JSONB column into `QuotationVersion` fixes the bug, and removes
`document-svc`'s synchronous dependency on `master-data` from the most exposed and most
latency-sensitive route in the system. This is a prerequisite of the split, not a consequence.

## 2.2 Contracts between services

Compile-time safety is gone. Three controls replace it.

- **Event contracts.** `schema_version` column on the outbox; event POJOs and JSON Schema in one
  versioned Gradle module. Consumers ignore unknown fields. **Additive only** — never remove or
  repurpose a field. A breaking change is a new `event_type` running in parallel until every
  consumer has migrated.
- **REST contracts.** OpenAPI generated from controllers, plus consumer-driven contract tests in
  CI, so `master-data` cannot merge a change to a field `sales` depends on. This is the part teams
  skip and then regret.
- **DDL ownership.** Each service owns its schema's Flyway history and runs it in its own pipeline.
  Enforced by role grants, as above.

## 2.3 Asynchronous messaging

### Shape

```
sales transaction:
    UPDATE quotation SET status = 'SENT'
    INSERT INTO sales.outbox (...)              ← same transaction
                    │
        outbox relay  (@Scheduled every 2s, ShedLock)
                    │  MessageGroupId        = aggregate_id
                    │  MessageDeduplicationId = outbox.id
                    ▼
             SNS FIFO topic  easycrm-events
                    ├──► SQS FIFO  notification-queue    → notification-svc
                    ├──► SQS FIFO  cache-invalidation    → sales-svc
                    └──► SQS FIFO  analytics-queue       → read model (later)
                            each with redrive policy → DLQ
```

The outbox solves the dual-write problem: a database commit and a message publish cannot be atomic,
so the message becomes a row written by the same transaction as the state change.

**The seam already exists.** Challenge #22 deliberately kept `QuotationAcceptedEvent` as a
side-effect seam rather than a return channel, and `OrderStatusChangedEvent` follows it. The outbox
writer is one more synchronous `@EventListener` on those events. No service code learns about SNS.

### Outbox table (one per schema)

```sql
CREATE TABLE sales.outbox (
  id             UUID PRIMARY KEY,         -- UUIDv7, matches BaseEntity
  tenant_id      UUID NOT NULL,            -- explicit; see F4
  aggregate_type VARCHAR(50)  NOT NULL,    -- 'Quotation'
  aggregate_id   UUID         NOT NULL,
  event_type     VARCHAR(80)  NOT NULL,    -- 'QuotationSent'
  schema_version SMALLINT     NOT NULL,
  traceparent    VARCHAR(64),              -- W3C trace context; see F8
  payload        JSONB        NOT NULL,
  occurred_at    TIMESTAMPTZ  NOT NULL,
  published_at   TIMESTAMPTZ               -- NULL until the relay publishes
);
CREATE INDEX ON sales.outbox (published_at) WHERE published_at IS NULL;
```

RLS applies to the **application** roles, so a service cannot write or read another tenant's event
through normal code paths.

### F12 — the relay cannot run under RLS (R1)

The relay must read unpublished rows **across all tenants**. It is a `@Scheduled` method, so it has
no tenant context, so an RLS policy returns **zero rows** — and nothing throws. This is precisely
the silent-success failure §2.4 guards jobs against, and it would stop every event in the system
without a single error appearing anywhere.

Three ways out, and only one is sound:

| Option | Verdict |
|---|---|
| Relay loops tenants with `runAs` | N queries every 2 seconds, scaling with tenant count, to find a handful of rows. Rejected |
| Drop RLS on `outbox` | Removes the guarantee that a service cannot write another tenant's event. Rejected |
| **A dedicated `relay_app` role with `BYPASSRLS`, granted `SELECT`/`UPDATE` on `*.outbox` only** | **Chosen.** `BYPASSRLS` is a role-wide attribute, so it is bounded by *grants* instead: the role can reach nothing but outbox tables |

The relay runs as `relay_app` on its own Proxy-registered secret. The application roles keep RLS on
`outbox`, so the isolation guarantee holds everywhere except the one component that provably needs
to cross tenants.

### The relay

A `@Scheduled` poller under ShedLock, every 2 seconds, running as `relay_app` and selecting
unpublished rows in `occurred_at` order, publishing in batches, then stamping `published_at`.

Crash between publish and stamp means republish — at-least-once, by design.

### F16 — one relay is a decision, not an assumption

ShedLock serialises the relay to a single runner. That is obviously right for the *sweeps* —
running quotation expiry twice is wasted work — but the relay is **throughput**, and serialising
throughput needs defending.

Concurrent relays are standard, using Postgres as a work queue:

```sql
SELECT * FROM sales.outbox
 WHERE published_at IS NULL
 ORDER BY occurred_at
 LIMIT 100
 FOR UPDATE SKIP LOCKED;
```

**What stops it here is ordering.** Per-aggregate ordering comes free from one relay reading in
`occurred_at` order. With N workers and `SKIP LOCKED`, two events for the same quotation land in
two workers, and if the second finishes first its event is published ahead of the earlier one. SQS
FIFO preserves the order it *receives*; it cannot repair an order the producer got wrong.

The fix is to hash-partition rather than race — `hashtext(aggregate_id::text) % N = worker_index` —
which preserves ordering and scales linearly, at the cost of a fixed N and owning the rebalancing.
That is consumer-group partitioning, hand-written against your own database.

**Trigger for doing it:** one relay publishing batches of 100 every 2 s is ~50 events/second
against a workload producing ~0.17 — about 300× headroom. Move to `SKIP LOCKED` with hash
partitioning when **outbox lag is sustained rather than spiky**, which is already an alarmed
metric (§2.5).

Note also that `desiredCount: 1` is **not** a substitute for the lock: a rolling deploy runs old
and new tasks concurrently by design, so a second relay exists during every deployment whether you
planned for one or not.

**Not `LISTEN/NOTIFY`**, despite being the obvious way to avoid polling: it is session state and
pins the RDS Proxy connection (F3). Poll, or use a direct connection.

### Ordering and delivery

- **F5 — `MessageGroupId = aggregate_id`.** The invariant that matters is "events about one quotation
  arrive in order"; nothing consumes a tenant's events as a single ordered sequence. Choosing
  `tenant_id` instead would serialise each tenant into one group and make a large distributor a
  permanent bottleneck. **The guarantee is per-aggregate ordering only** — no consumer may assume
  more.
- **`MessageDeduplicationId = outbox.id`** gives a free 5-minute dedupe window. Beyond it,
  consumers dedupe on a `processed_event` table with a unique constraint, letting the existing
  challenge #15 `DataIntegrityViolation` handler absorb the race.
- **Duplicates are guaranteed, not exceptional.** Consumers must be idempotent.
- **Poison messages** are handled by the redrive policy (`maxReceiveCount` → DLQ), not by hand.

### F4 — messaging sits outside all four isolation layers

JWT resolution, `@TenantId`, RLS and ArchUnit all stop at the database boundary. SNS and SQS carry
every tenant's events interleaved, and the only things separating them are the topic/queue IAM
policies and the KMS key.

Two consequences, both mandatory:

1. `tenant_id` is an **explicit field** on every message, never inferred from ambient context.
2. Every consumer calls `TenantContext.runAs(tenantId, …)` **before** opening its transaction —
   the same shape as `GET /public/q/{token}`, and the same reason `spring.jpa.open-in-view: false`
   stays load-bearing (challenges #29–#30). A consumer that opens its transaction first writes
   under the wrong tenant, or under none, silently.

### What deliberately stays synchronous

- `QuotationService.accept` creating the order stays one local transaction. This is why `sales` was
  not split further (D1).
- Cross-service reads on the quotation write path are synchronous Service Connect calls, not
  events. Eventual consistency on a tax rate is not acceptable.

### What was given up by dropping Kinesis

- **Replay.** SQS deletes on acknowledgement, so rebuilding a read model means re-reading Postgres.
- **`ShareLinkViewed` for cached hits.** Once `/public/q/*` is edge-cached the origin never sees a
  repeat open, and CloudFront real-time logs deliver only to Kinesis. Edge-cached opens are
  recovered from standard S3 access logs via Athena on a daily batch, not in real time. "Your
  customer opened the quotation" therefore arrives the next day, not immediately.

## 2.4 Scheduled work

| Job | Owner | Cadence |
|---|---|---|
| Quotation auto-expiry past `valid_until` | sales | daily 02:00 IST |
| Follow-up reminder sweep | sales | every 15 min |
| Refresh-token cleanup | identity | daily |
| Share-link expiry + CloudFront invalidation | document | daily |
| Outbox reaper (published rows > 7 days) | each | hourly |
| Orphaned-reference reconciliation | sales | nightly |
| Owner digest | notification | daily 08:00 IST |

Rules, all non-optional:

- **ShedLock's `JdbcTemplateLockProvider`**, never an advisory lock (F2). ShedLock does not
  schedule anything — `@Scheduled` still fires on every task, and ShedLock decides which one
  executes the body, via an `INSERT … ON CONFLICT DO UPDATE … WHERE lock_until <= now()` whose
  primary key serialises the contenders.
- **`lockAtMostFor` is a lease, and it is per-job, not global.** It is how long the system waits
  after a holder dies before anyone else may run. Too short and two runners overlap; too long and a
  crash silently skips a window — there is no value safe against both, so it is set from each job's
  cadence:

  | Job | `lockAtMostFor` | Why |
  |---|---|---|
  | Outbox relay | **60 s** | Runs every 2 s. A 10-minute lease would stall *every event in the system* for ten minutes after one crash |
  | Quotation expiry, token cleanup, reconciliation | 10 m | Daily; a missed window is recoverable and the next run catches up |
  | Follow-up sweep | 5 m | Every 15 min |

- **ShedLock is not a fencing token.** A process that stalls past its lease — long GC, network
  partition — can resume and keep working while another instance holds the lock. Every job body
  must be idempotent regardless of the lock.
- **Every job loops tenants explicitly**: read ids from `shared.tenant` (R2), then
  `TenantContext.runAs(id, …)` with **one transaction per tenant**, so one bad tenant fails alone
  instead of rolling back the sweep.
- A `@Scheduled` method carries no JWT. Without `runAs`, RLS returns zero rows and **the job
  reports success having done nothing**. That silent-success mode is why every job emits a
  `rows_affected` metric and a heartbeat, alarmed on *absence* rather than on error.
- State changes made by jobs write to the outbox like any other change, so "your quotation expired"
  reaches the customer through the same pipeline as everything else.

## 2.5 Observability

### Traces

OpenTelemetry Java agent (`-javaagent`, no code change) → ADOT collector sidecar → CloudWatch
Application Signals and X-Ray. Auto-instruments Spring MVC, JDBC, HikariCP and the AWS SDK.

CloudFront's `X-Amz-Cf-Id` and the ALB's `X-Amzn-Trace-Id` are attached as root-span attributes for
correlation with access logs.

**Sampling — head-based now, tail-based later.** The SDK sampler
(`parentbased_traceidratio`) decides at the **root span** and propagates the verdict in the
`traceparent` sampled flag, so a trace is kept or dropped as a whole rather than 10% of the spans
in every trace. Rates come from X-Ray's centralized rules (reservoir + rate per route), which are
fetched at runtime and so change without a redeploy; the flat 10% is only the `Everything else`
rule.

"Errors always sampled" is **not achievable at the head** — the decision is made at `startSpan`,
before the request has failed — and not achievable in a **sidecar** either, since a per-task
collector never sees a whole trace. It arrives with the central collector ECS service and its
`tail_sampling` processor, whose policies OR together: keep on `status_code = ERROR`, keep on
latency over a threshold, plus a 10% probabilistic baseline. Until then, errors that fall outside
the sample are covered by logs and the error-rate metric, not by traces — which is the real cost of
staying on sidecars.

Either way, RED metrics come from the `spanmetrics` connector, which sits **before** the sampler and
sees 100% of spans. Once errors are kept preferentially the surviving traces are a biased
population, and counting them yields an error rate that is wrong by more than an order of magnitude.

### F8 — the outbox breaks trace continuity

The HTTP request that writes the outbox row and the relay that publishes it are seconds to minutes
apart, on different threads, in different transactions. The producing span has ended before the
message is sent.

So: capture `traceparent` into the outbox row at write time, re-inject it as an SQS message
attribute in the relay, and have the consumer attach it as a **span link, not a parent**. A
parent-child edge would misrepresent causality, and Application Signals renders it as one.

Without this, every trace stops dead at the outbox insert and the entire asynchronous half of the
system is invisible.

### Logs

Logback JSON encoder, one line per event. MDC carries `trace_id`, `span_id`, `tenant_id`,
`user_id`, `service`, `version`.

Shipped via **FireLens/FluentBit** rather than plain `awslogs`, specifically so ALB health-check
lines can be dropped — at a 30-second interval across 20 tasks that is a meaningful share of a
$0.57/GB ingest bill.

**No phone numbers, no GSTINs, no addresses in logs** (DPDP). 30-day retention, then S3.

With `audit_log` removed (D9), security-relevant events become structured log lines with CloudWatch
metric filters: `LOGIN_FAILED` rate per tenant drives a brute-force alarm.

### Metrics

Micrometer → **EMF through the ADOT collector**, not `PutMetricData`. EMF metrics are extracted
from log ingestion, avoiding $0.30/metric/month per custom metric.

**Never dimension a metric by `tenant_id`** — a cardinality explosion and a cost explosion at once.
Per-tenant questions are answered in Logs Insights, which is what it is for.

Service-level metrics: request rate, p50/p95/p99 latency, 5xx rate, Hikari pool utilisation,
outbox lag (`now() - min(occurred_at) where published_at is null`), job `rows_affected` and
heartbeats.

### Alarms

| Alarm | Why |
|---|---|
| `DatabaseConnectionsCurrentlySessionPinned` > 0 | The Proxy has stopped multiplexing (F1/F2) |
| Outbox lag > 60s | The relay is stuck; events are not flowing |
| `ApproximateAgeOfOldestMessage` > 300s | A consumer is falling behind or wedged |
| DLQ depth > 0 | Something is failing permanently |
| Hikari pending threads > 0 sustained | Approaching the connection ceiling (F9) |
| Job heartbeat absent | A scheduled job silently stopped running |
| 5xx rate, p99 latency, RDS CPU/storage | Standard |

## 2.6 Scaling

| Service | Signal | Target |
|---|---|---|
| identity, master-data, sales | `ALBRequestCountPerTarget` | ~300/target/min, plus a p95 `TargetResponseTime` step policy |
| document | p95 `TargetResponseTime` | 800 ms — CPU-bound renders; CloudFront absorbs repeats |
| notification | `ApproximateAgeOfOldestMessage` | 60 s — backlog **age**, not depth; depth alone lies when messages are cheap |
| all | scheduled scaling | min 2 during 09:00–20:00 IST, min 1 overnight |

- **Never CPU** (D8): batch sweeps run in-process, so CPU no longer means "serving load."
- Scheduled scaling matters more than reactive scaling here. Distributor traffic is sharply diurnal
  and near-zero at night; pre-scaling beats reacting to a ramp you can predict.
- Cooldowns: 60 s out, 300 s in. This must exceed JVM boot (~15–20 s) plus health-check plus
  deregistration delay, or target tracking overshoots and oscillates.
- Bounded by F9.

## 2.7 Security

- **RS256 + JWKS** (D11). identity-svc holds the private key in Secrets Manager; the other services
  verify with a cached public key fetched from an internal JWKS endpoint.
- **IAM authentication to RDS Proxy** — no database password exists in any task.
- Secrets injected via task-definition `secrets`, never plaintext environment variables.
- Least-privilege task roles: only `notification` may receive from its queue; only the relay's
  service may publish to the topic.
- Customer-managed KMS keys on RDS, SQS, SNS, S3 and log groups.
- WAF on CloudFront: managed rule sets on `/api/*`, and a **rate-based rule on `/public/q/*`** —
  the only unauthenticated route, and the most expensive uncapped operation in the product.
- Service Connect mTLS requires an ACM private CA at roughly $400/month. Noted, and not
  recommended at this scale.

### F10 — the `/api/*` cache policy is a security control

If an authenticated response is ever cached by CloudFront, one distributor's pipeline is served to
another. This is exactly the class of failure the four isolation layers exist to make structurally
impossible, and CloudFront sits outside all of them.

Mitigation is explicit and testable, and must be treated as such: `CachingDisabled` on `/api/*`, an
origin-request policy forwarding `Authorization`, ordered behaviours that never fall through to the
default, and an integration test that asserts no `Cache-Control` allows shared caching on an
authenticated response.

Related: caching `/public/q/*` is in direct tension with revoking a share link. A cached PDF
outlives revocation until TTL expiry. Resolution is a bounded TTL (~5 minutes) plus an explicit
invalidation on revoke.

### F13 — `document-svc` cannot reach the data it renders (R3)

Rendering a quotation needs `quotation`, `quotation_version` and `quotation_item`. Those live in
the `sales` schema, which `document_app` has no privileges on. As first written, the service could
not read its own inputs.

The wrong fixes are obvious and both bad: grant `document_app` read access into `sales` (which
destroys the schema-ownership boundary that makes D2 work), or have `document-svc` call `sales-svc`
synchronously on every render (which puts a cross-service hop on the most exposed, most
latency-sensitive route in the product).

**The fix: freeze the render payload at send time.** When `sales-svc` freezes a version, its
`QuotationSent` event carries the complete rendering input — line items, totals, tax split, seller
letterhead and the buyer snapshot from D10. `document-svc` persists that as an immutable
`document.render_payload` row keyed by `quotation_version_id`.

The public render then reads **nothing but its own schema**. No cross-service call, no cross-schema
grant, and the payload is immutable by construction — which is what makes the CloudFront cache
correct rather than merely convenient.

Scope note: `document-svc` renders **frozen versions only**. Draft preview, which has no frozen
payload, stays out of scope — as it effectively already is, since the current endpoint defaults to
the latest `SENT` version.

## 2.8 Deployment, migrations and recovery

### F14 — concurrent versions, Flyway and `ddl-auto: validate` are mutually hostile (R5)

Flyway runs at application startup today. Deployed to ECS that breaks in two ways:

1. **N tasks start at once** and race for the Flyway lock. Flyway serialises them, but every
   loser waits on a lock during startup, inflating deploy time and health-check windows.
2. **Every deployment strategy runs old and new code against the same schema simultaneously.**
   Rolling interleaves them; blue/green (D13) keeps the *entire* previous task set alive through
   traffic shift and bake, so the overlap window is longer, not shorter. With
   `spring.jpa.hibernate.ddl-auto: validate` — which this codebase relies on — an old task meeting a
   new schema fails validation and **crash-loops**, and ECS reads that as a failed deployment of the
   *new* version.

Two rules follow, and neither is optional:

- **Migrations run as a one-off ECS task before the service deploy**, never at application startup.
  Flyway is disabled in the service image.
- **Migrations are expand/contract.** A release may only add. Anything destructive — dropping a
  column, tightening a constraint, renaming — ships one release *after* the code that stopped using
  it. This is what makes any deployment strategy survivable, and it is a discipline rather than a
  mechanism, so it belongs in the review checklist — the migration lint can only flag destructive
  statements, not judge whether the code that used them is gone.

### Database change management (D14, D15)

**Where migrations live.** In the monorepo, partitioned the same way the schemas are — **five
independent histories**, each with its own `flyway_schema_history` table inside its own schema
(`flyway.defaultSchema=sales`). Version numbers restart at `V1` per history, because the histories
are genuinely independent.

```
platform/db/src/main/resources/db/migration/      extensions · shared schema · grants · RLS helpers
services/identity/src/main/resources/db/migration/
services/master-data/src/main/resources/db/migration/
services/sales/src/main/resources/db/migration/
services/document/src/main/resources/db/migration/
```

`platform/db` runs **first, always**. It owns what belongs to no single service: `CREATE EXTENSION`,
schema creation, the `shared` schema (`tenant`, `plan`, `shedlock`), and the grants that make D2's
ownership boundary real — including the `relay_app` grants that bound F12's `BYPASSRLS`.

Under D15, **Terraform creates the roles and owns their Secrets Manager secrets and rotation**,
reaching the database through a bootstrap task in the VPC; Flyway creates schemas, grants and
objects. Today's single `V1__roles_and_extensions.sql` splits along that line.

Collapsing today's 25-migration single history into five is a one-time restructuring, part of
sub-project 8.

**How they run.**

| | |
|---|---|
| Trigger | The pipeline, `aws ecs run-task` on a dedicated task definition, waiting on exit code |
| Credential | The schema **owner** — application roles are non-owner and cannot execute DDL |
| Network | **Directly to RDS, bypassing the Proxy** (F3) |
| In the service image | `spring.flyway.enabled=false` — disabled, not merely unused |
| On failure | The pipeline stops and nothing deploys. That is the reason it is a separate step |

**Why migrate-first is what makes rollback safe.** Migrations run before traffic shifts, so the
old version serves requests against the new schema for the whole deploy — and under blue/green that
window is longer than under rolling, because the original task set stays up through the bake.

That is not the risk it appears to be, because of one fact:

> A rollback shifts traffic back to the previous version. **It does not revert the migration — and
> it must not.**

So the previous image has to run correctly against the migrated schema *regardless*. Migrate-first
plus additive-only is the only self-consistent combination; deploy-first would require new code to
tolerate the old schema, which it cannot, since it generally needs the new column.

**Expand/contract, concretely.** Renaming `quotation.notes` to `remarks` is three releases:

| | Migration | Code |
|---|---|---|
| R1 — expand | add `remarks`, nullable, backfill | writes both, reads `notes` |
| R2 | — | writes both, reads `remarks` |
| R3 — contract | drop `notes` | `remarks` only |

Every release is independently rollback-safe. Two rules follow:

- **New columns are nullable or carry a default.** A `NOT NULL` column with no default breaks every
  insert from the version still serving traffic.
- **`SET NOT NULL` ships a release later**, via `ADD CONSTRAINT … NOT VALID` then
  `VALIDATE CONSTRAINT` — the direct form scans the table under a strong lock.

`ddl-auto: validate` is compatible with this: Hibernate validates that *mapped* columns exist and
ignores extra ones, so expand is safe. Only contract can crash-loop an old task, which is precisely
why contract ships late.

**Two Postgres details that cause outages.**

- **`CREATE INDEX CONCURRENTLY` cannot run inside a transaction**, and Flyway wraps migrations in
  one by default — such a migration needs `-- flyway:executeInTransaction=false`. Without
  `CONCURRENTLY`, index creation blocks writes for its duration.
- **Set `lock_timeout` to ~3s and retry.** DDL waiting on `ACCESS EXCLUSIVE` blocks every
  subsequent query on that table, *including reads*. A migration that waits thirty seconds behind
  one long query takes the table down for thirty seconds. Failing fast and retrying is strictly
  better than queueing.

### Deployment pipeline (R10, D13)

```
pull request
  ├── build + ALL tests (Testcontainers)          no path filter — the suite is seconds
  ├── ArchUnit: tenant scoping · platform-may-not-reference-services
  ├── contract tests: every consumer of a changed provider
  └── migration lint: naming, and destructive ops require an explicit marker

merge to main
  ├── changed paths → affected services            (platform/ ⇒ all)
  ├── image per affected service, tag = git sha, IMMUTABLE, scan on push
  ├── STAGING
  │     platform migration → service migrations → blue/green → smoke tests
  └── manual approval
        PROD: same four steps
```

**Path-filter the deploys, not the tests.** The suite is 231 tests in ~12 seconds; filtering tests
would require computing the transitive Gradle dependency graph correctly, which is a class of bug
bought for no measurable time. Filtering deploys is a simple, safe rule.

**Blue/green mechanics** (D13): two target groups per service, an `appspec.yaml` per service, and
CodeDeploy owning the ECS service — which means Terraform must `ignore_changes` on the task
definition, or the two will fight. The `BeforeAllowTraffic` hook runs smoke tests against the green
target group **before it receives any traffic**, which is the main thing blue/green buys over
rolling here. `notification` has no load balancer, so it deploys rolling with the circuit breaker.

**Promotion is gated by a human at production.** Staging deploys on merge and runs smoke tests;
production waits on an approval. While one person operates this and there is no on-call rotation,
someone should be awake when revenue infrastructure changes.

Three pipeline properties that are not optional: **immutable ECR tags, never `latest`** (exact
rollback needs an exact artifact); **GitHub OIDC federation to an AWS role**, so no long-lived AWS
credentials exist in GitHub; and **`concurrency: deploy-<env>`**, so two migration tasks cannot
race — Flyway's lock would keep that correct, but the pipeline should not depend on it.

### F17 — blue/green doubles the connection budget, not just the compute

A blue/green deployment runs a complete replacement task set alongside the original until traffic
shifts and the bake finishes. Every one of those tasks opens its own HikariCP pool.

Sizing pools for steady state and then deploying is how a **deployment** exhausts the database
connection budget — an outage triggered by the mechanism chosen to make deployments safe, and one
that only appears when a deploy coincides with a scale-out. F9's table therefore carries an explicit
`deploy_factor`, and the safe peak is `max_tasks × 2 × pool`, not `max_tasks × pool`.

The second-order effect matters too: a deploy at peak traffic is the worst possible moment, which is
an argument for deploying against scheduled-minimum capacity rather than merely "outside business
hours."

### Graceful shutdown (R6)

Without this, every deploy and every scale-in drops in-flight requests — including PDF renders that
have already burned their CPU.

| Setting | Value | Why |
|---|---|---|
| ALB deregistration delay | 30 s | Stop new traffic before the task dies |
| `server.shutdown` | `graceful` | Finish in-flight requests |
| `spring.lifecycle.timeout-per-shutdown-phase` | 25 s | Under the ECS stop timeout |
| ECS `stopTimeout` | 45 s | SIGTERM → grace → SIGKILL |
| Readiness vs liveness | separate actuator groups | Readiness fails first so the ALB drains before the container is killed |

### Timeout ordering (R9)

Each layer must time out **after** the layer inside it, so the innermost failure is the one
reported and the error is attributable:

```
task request timeout   20 s
ALB idle timeout       25 s
CloudFront origin      30 s   (default; raising it needs a quota increase)
client                 60 s
```

A PDF render must therefore complete inside 20 s. Anything that cannot — bulk import, for
instance — must be asynchronous by construction, not merely slow.

### Backup and recovery (R7)

| | |
|---|---|
| Automated backups | 30-day retention, PITR enabled |
| RPO | 5 minutes (PITR granularity) |
| RTO | 1 hour (restore + task rollout) |
| Restore test | **Monthly, into a scratch VPC.** An untested backup is a hypothesis |
| Deletion protection | On, for RDS and the S3 log bucket |
| Cross-region snapshot copy | Deliberately not enabled — single-region by D-region choice; revisit if RTO tightens |

Note that the DLQs and the outbox are also state: a redrive after a restore can replay events the
restored database has already applied. Consumer idempotency (`processed_event`) is what makes that
survivable, which is a second reason it is not optional.

### Environments (R10)

| Env | Shape | Cost |
|---|---|---|
| dev | Single-AZ RDS, 1 task per service, no WAF | ~$120/mo |
| staging | Production-shaped, scaled to 1 task per service | ~$250/mo |
| prod | As Part 1 | $580–820/mo |

**Part 4.1's figure is production only.** Three environments is roughly $950–1,190/month, and any
honest comparison against the monolith has to use the same multiplier on both sides.

### F15 — CloudFront's dependencies live in `us-east-1` (R8)

The WAF web ACL for a CloudFront distribution must be created with **`CLOUDFRONT` scope in
`us-east-1`**, and the ACM certificate for the distribution must also be issued in `us-east-1` —
even though every other resource in this design is in `ap-south-1`. Terraform needs a second
aliased provider for that. It fails at apply time rather than silently, but it reliably surprises
people once.

---

# Part 3 — Data flows

## 3.1 Quotation send, end to end

1. `POST /api/v1/quotations/{id}/send` → CloudFront (`/api/*`, uncached) → internal ALB → `sales-svc`.
2. JWT verified locally against the cached JWKS public key; `TenantContext` set from the claim.
3. Transaction opens; `TenantAwareTransactionManager` runs `set_config('app.current_tenant', …, true)`.
4. Version frozen, quote number assigned via `document_counter` `SELECT … FOR UPDATE`.
5. Synchronous `@EventListener` writes `QuotationSent` to `sales.outbox` **in the same transaction**,
   carrying `tenant_id` and the current `traceparent`.
6. Commit. The HTTP response returns. Everything to this point is one atomic unit.
7. Within ~2 s the relay publishes to the SNS FIFO topic with `MessageGroupId = quotation_id`.
8. `notification-svc` receives from its queue, restores tenant context with `runAs`, dedupes on
   `outbox.id`, sends the WhatsApp message, deletes the message.
9. A failure at step 8 is retried per the redrive policy, then lands in the DLQ. The quotation is
   still correctly `SENT`; only the notification is outstanding.

## 3.2 Public share link render

1. `GET /public/q/{token}` → CloudFront `/public/q/*` behaviour.
2. **Cache hit:** served from the edge. The origin is never touched — no PDF render, no database
   query, no Fargate CPU. This is the single largest efficiency gain in the design.
3. **Cache miss:** VPC origin → internal ALB → `document-svc`, no JWT.
4. `share_link` (global, RLS-exempt) resolves the token to `(tenant_id, quotation_version_id)`.
5. `TenantContext.runAs(tenantId, …)` installs the tenant **before** the rendering transaction
   opens. `open-in-view: false` is what makes this correct (challenge #29).
6. The frozen `document.render_payload` is rendered (F13) — it already contains the line items,
   totals, tax split, letterhead and the **buyer snapshot** (D10). No call to `master-data`, no call
   to `sales`, no cross-schema read. The document is byte-identical to the one that was sent.
7. WAF's rate-based rule caps abuse of the route.

## 3.3 Quotation auto-expiry

1. `@Scheduled` fires in `sales-svc`; ShedLock's table row admits exactly one task.
2. Tenant ids read from `shared.tenant` — the one schema every service may read (R2).
3. Per tenant: `runAs` → one transaction → `SENT` versions past `valid_until` flipped to `EXPIRED`
   → an `QuotationExpired` outbox row per affected quotation.
4. `rows_affected` and a heartbeat are emitted per run.
5. The relay publishes; `notification-svc` tells the salesperson.
6. A failure for one tenant rolls back that tenant only.

## 3.4 Quotation create, across services

1. `POST /api/v1/quotations` → `sales-svc`.
2. Service Connect call to `master-data-svc` for the customer (`stateCode`) and the resolved price
   list. Envoy applies the timeout, retry and circuit-breaker policy.
3. `master-data` unavailable → fail fast with 503. A quotation with the wrong tax split is worse
   than no quotation.
4. Customer not found → 422.
5. GST computed server-side (per-line round-then-sum, HALF_UP), quotation and version written in
   one local transaction.

---

# Part 4 — Cost, and the case against

## 4.1 Estimated monthly cost

ap-south-1, list prices, order-of-magnitude. **Verify against the AWS pricing calculator before
committing to anything.**

| Item | Est./month |
|---|---:|
| Fargate — 10 tasks @ 0.75 vCPU / 1.5 GB (sized to hold Envoy + ADOT sidecars) | $250–310 |
| RDS `db.t4g.medium` Multi-AZ + 100 GB gp3 | ~$165 |
| RDS Proxy | $22–88 |
| NAT gateway + VPC interface endpoints | $60–120 |
| Internal ALB + CloudFront + WAF | ~$45 |
| CloudWatch (logs, traces, dashboards) | $30–80 |
| SNS/SQS, Secrets Manager, ECR, S3 | ~$10 |
| **Total** | **~$580–820** |

The same application as a modular monolith — one ECS service, single-AZ RDS, ALB, CloudFront — is
**~$70–90/month**.

At ₹1,500 per tenant per month, the monolith pays for its infrastructure at about **5** paying
tenants. This design needs about **40**.

## 4.2 The non-monetary cost

- Five deploy pipelines, five log groups, five scaling policies, a contract-test harness and a
  relay to babysit — for one engineer.
- Quotation creation makes a network hop where it made a same-transaction read. p50 worsens, and a
  new failure mode appears: `master-data` down blocks quoting.
- Eventual consistency becomes user-visible where it previously was not.
- **SNS/SQS and CloudFront are new surfaces where tenant isolation depends on configuration being
  correct rather than on the database refusing** (F4, F10). This codebase's central thesis is that
  isolation must be structural. The split adds two places where it is procedural.
- Shared `platform` code needs a boundary discipline (mechanisms, not meanings) that a single
  codebase never required. Under D12 it does **not** need to become a published, separately
  versioned library — that cost applies to a repository-per-service split, and is one of the
  reasons this design does not take one.

## 4.3 What it genuinely buys

- **Independent scaling for `document-svc`** — CPU-bound bursty PDF rendering really is a different
  profile, and it owns the only unauthenticated route. This is the strongest justification and it
  would hold even at today's scale.
- Blast-radius isolation: a PDF render loop cannot exhaust the connection pool that quotation
  writes depend on.
- Independent deploys, and the ability to staff services separately.

At roughly 100 tenants, none of the last two are binding constraints.

## 4.4 Reversals from the original requirement

This design was asked to use **Kinesis** for inter-service messaging and **DMS CDC** to feed it.
Both were removed after the numbers were worked out (D4). What that gave up is recorded in §2.3.
The trigger to reinstate them is a genuine replay requirement, or a third independent consumer of
the same event stream, or real-time capture of edge-cached share-link views.

## 4.5 When this stops being over-engineering

- PDF render load diverges enough that a shared service's scaling signal becomes unusable.
- More than about three engineers contend on a single deploy pipeline.
- A tenant large enough to justify dedicated isolation.
- A regulatory requirement to separate identity data from business data.

Until one of those is true, the modular monolith on ECS — Part 5, sub-project 1, and nothing after
it — is the correct production answer, and this document's own recommendation.

---

# Part 5 — Decomposition into sub-projects

Each is independently specifiable and independently shippable. Dependencies noted.

| # | Sub-project | Depends on | Notes |
|---|---|---|---|
| 1 | **Buyer snapshot** (D10/F11) | — | Pure application change. Fixes a live correctness bug. No AWS work. **Do this first regardless of whether anything else happens** |
| 2 | **AWS foundation** — VPC, ECS cluster, RDS + Proxy, CloudFront/ALB/WAF, ECR, CI/CD; deploy today's monolith unchanged | — | The largest and most valuable piece. Delivers a production deployment on its own |
| 3 | **Observability** — OTel agent, ADOT sidecar, structured logs, EMF metrics, dashboards, alarms | 2 | |
| 4 | **Scaling policies** — target tracking and scheduled scaling | 3 | You cannot scale on metrics you do not emit |
| 5 | **Scheduled jobs** — ShedLock, the seven jobs, per-tenant loops, heartbeats | — for the sweeps; **6** for the jobs that emit events | The sweeps themselves are app-only; auto-expiry cannot publish `QuotationExpired` until the outbox exists |
| 6 | **Outbox + relay + SNS/SQS + first consumer** | 2 | Notification is the first real consumer |
| 7 | **Security hardening** — RS256/JWKS, IAM auth to Proxy, WAF rate rules, cache-policy tests | 2 | |
| 8 | **Service extraction** — `document` first, then `notification`, `master-data`, `identity` | 2, 3, 6 | Needs the contract-test harness before the first extraction, not after. `document` extraction includes the render-payload freeze (F13) and the `/api/v1/documents/*` route move (R4) |

**Recommended order:** 1 → 2 → 3 → 4, then reassess. Sub-projects 1–4 deliver a properly
observable, properly scaled production deployment of the system that exists today, and every
argument in Part 4 says to stop there until a trigger in §4.5 fires.

Sub-projects **9–13 (billing, plans and entitlements)** continue this numbering and are specified
in `../superpowers/specs/2026-08-19-billing-and-entitlements-design.md`. Two of them — user
invitations and the entitlement layer — are useful product work independent of both billing and
this AWS design.

The outbox itself is specified at class level in `2026-08-19-outbox-lld.md` — the shared
`platform-outbox` module, how each service adopts it, and the full request path from HTTP call to
consumed message.

---

# Appendix A — Findings

| # | Finding |
|---|---|
| F1 | The existing `set_config(..., is_local => true)` RLS mechanism is the only shape compatible with RDS Proxy multiplexing. Verified by `DatabaseConnectionsCurrentlySessionPinned`, not by argument |
| F2 | PgJDBC prepared statements (`prepareThreshold=0`) and session-level advisory locks each pin every connection, silently |
| F3 | Flyway and any `LISTEN/NOTIFY` must bypass the Proxy |
| F4 | SNS/SQS sit outside all four isolation layers; `tenant_id` must be explicit and consumers must `runAs` before opening a transaction |
| F5 | `MessageGroupId = aggregate_id`, not `tenant_id` — keying by tenant makes a large distributor a permanent bottleneck |
| F6 | *(Eliminated by D4.)* A stopped DMS task retains WAL until the RDS instance runs out of storage, taking down all five services from a component nobody watches |
| F7 | *(Eliminated by D4.)* Kinesis consumer parallelism is capped at shard count; scaling past it adds idle tasks and an autoscaler that never converges |
| F8 | The outbox breaks trace continuity. `traceparent` must be stored, re-injected, and attached as a span **link**, not a parent |
| F9 | The autoscaler's real ceiling is `max_tasks × pool ≤ proxy connection budget`. Without it, autoscaling relocates the outage to Postgres |
| F10 | The `/api/*` CloudFront cache policy is a tenant-isolation control and must be tested as one |
| F11 | `QuotationVersion` does not snapshot the buyer, so re-rendering a sent quotation after a customer edit produces a different document. A live bug, independent of this design |
| F12 | The relay must read across tenants, but RLS returns zero rows to a `@Scheduled` method with no tenant context — silently. Needs a `BYPASSRLS` relay role bounded by grants to `*.outbox` |
| F12b | `BYPASSRLS` fixes only the *database* layer. Hibernate's `@TenantId` appends its own tenant predicate from the session identifier, which the relay does not have — so a JPA read still returns zero rows. The relay's read path must use `JdbcTemplate` on a separate `relay_app` DataSource. See the outbox LLD, OF1 |
| F13 | `document-svc` cannot reach the `sales` data it renders. Resolved by freezing an immutable render payload into its own schema at send time, which also makes the CloudFront cache correct by construction |
| F14 | Rolling deploys + startup Flyway + `ddl-auto: validate` crash-loop old tasks against a new schema. Migrations move to a pre-deploy task and must be expand/contract |
| F15 | A CloudFront distribution's WAF web ACL and ACM certificate must live in `us-east-1`, not `ap-south-1` |
| F17 | Blue/green runs a full replacement task set, so peak database connections are `max_tasks × 2 × pool`. Sizing pools for steady state means the deployment mechanism chosen for safety is what exhausts the connection budget |
| F16 | Serialising the relay is a decision, not a default. `SKIP LOCKED` scales it, but only hash partitioning preserves per-aggregate ordering. Trigger: sustained outbox lag. And `desiredCount: 1` is not a substitute for the lock — a rolling deploy always runs two |

# Appendix B — To verify before implementation

Claims in this document that rest on AWS behaviour and should be confirmed against current
documentation or a spike, not taken on trust:

1. RDS Proxy pinning behaviour for `set_config()`, PgJDBC prepared statements and advisory locks
   (F1, F2) — confirm with the pinning metric under load, not from documentation alone.
2. RDS Proxy minimum billing for instance classes below 8 vCPU (affects the $22 vs $88 range).
3. CloudFront VPC origins with an internal ALB, and behaviour ordering semantics (D7, F10).
4. SNS FIFO → SQS FIFO fan-out constraints and per-topic throughput limits.
5. Fargate and RDS pricing for ap-south-1 (Part 4.1 uses approximations).
6. ADOT collector support for EMF metric export in the intended configuration (§2.5).
7. Whether CloudWatch Application Signals renders span links as intended for the async hop (F8).
8. `max_connections` for `db.t4g.medium` — Part 2.1 assumes ~450 from the RDS default formula and
   budgets 75% through the Proxy. At 336 projected peak (F9) the margin is thin enough that the real
   number matters.
9. CodeDeploy blue/green with ECS: whether `BeforeAllowTraffic` hooks can reach the green target
   group from within the VPC as assumed, and the Terraform `ignore_changes` shape needed so
   CodeDeploy and Terraform do not fight over the task definition (D13).
10. Flyway multi-schema history behaviour — that `flyway.defaultSchema` per service places each
    history table in its own schema and that the five histories are genuinely independent (D14).
