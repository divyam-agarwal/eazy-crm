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
| **document** | `sales.pdf`, `share_link`, `/public/q/*` | `document` | **Bursty, CPU-bound.** The reason a split is defensible at all |
| **notification** | new | — (uses `sales` events) | Driven by SQS backlog |

Shared `platform` code (tenancy, money, error, security, persistence) becomes a versioned internal
library. That is a real cost of the split and is called out in Part 4.

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
  ├── identity_app     → schema identity      (tenant, app_user, refresh_token)
  ├── master_data_app  → schema master_data   (product, customer, contact, price_list, price_list_item)
  ├── sales_app        → schema sales         (enquiry, quotation, quotation_version, quotation_item,
  │                                            sales_order, document_counter, outbox)
  └── document_app     → schema document      (share_link)
```

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
max_tasks × hikari_pool_size  ≤  MaxConnectionsPercent × max_connections
```

| Service | Max tasks | Pool | Connections |
|---|---:|---:|---:|
| identity | 4 | 5 | 20 |
| master-data | 4 | 5 | 20 |
| sales | 10 | 10 | 100 |
| document | 20 | 5 | 100 |
| notification | 6 | 5 | 30 |
| **Total at full scale-out** | | | **270** |

A `db.t4g.medium` allows roughly 340 connections, and each costs ~10 MB of server memory. Without
this table written down, autoscaling does not prevent an outage — it moves the outage from one
service's ALB to Postgres, where it takes down all five.

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

Tenant-scoped and RLS-covered on write, so a service cannot write another tenant's event.

### The relay

A `@Scheduled` poller under ShedLock, every 2 seconds, selecting unpublished rows in
`occurred_at` order and publishing in batches, then stamping `published_at`.

Crash between publish and stamp means republish — at-least-once, by design.

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

- **ShedLock's `JdbcTemplateLockProvider`**, never an advisory lock (F2).
- **Every job loops tenants explicitly**: read ids from the global `tenant` table, then
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

Head-based sampling at 10%, with errors always sampled.

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
6. The frozen version is rendered — including the **buyer snapshot** (D10), so no call to
   `master-data` is made and the document is identical to the one that was sent.
7. WAF's rate-based rule caps abuse of the route.

## 3.3 Quotation auto-expiry

1. `@Scheduled` fires in `sales-svc`; ShedLock's table row admits exactly one task.
2. Tenant ids read from the global `tenant` table.
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
- The shared `platform` package must become a versioned library, with the release friction that
  implies.

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
| 8 | **Service extraction** — `document` first, then `notification`, `master-data`, `identity` | 2, 3, 6 | Needs the contract-test harness before the first extraction, not after |

**Recommended order:** 1 → 2 → 3 → 4, then reassess. Sub-projects 1–4 deliver a properly
observable, properly scaled production deployment of the system that exists today, and every
argument in Part 4 says to stop there until a trigger in §4.5 fires.

Sub-projects **9–13 (billing, plans and entitlements)** continue this numbering and are specified
in `../superpowers/specs/2026-08-19-billing-and-entitlements-design.md`. Two of them — user
invitations and the entitlement layer — are useful product work independent of both billing and
this AWS design.

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
