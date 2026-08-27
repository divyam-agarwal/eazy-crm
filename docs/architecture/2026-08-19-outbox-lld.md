# Transactional Outbox — Low-Level Design

**Date:** 2026-08-19 · **revised 2026-08-27** (LLD #5 of 6 — see Part 0)
**Status:** Design. Not built.
**Parent:** `2026-08-19-aws-target-architecture-design.md` §2.3 (D3, F4, F8, F12, F16)
**Module spec:** [`../superpowers/specs/2026-08-26-shared-platform-modules-design.md`](../superpowers/specs/2026-08-26-shared-platform-modules-design.md) — this document is `platform-outbox`'s LLD
**Depends on:** [`2026-08-27-platform-tenancy-lld.md`](2026-08-27-platform-tenancy-lld.md) (#4) and [`2026-08-26-platform-primitives-lld.md`](2026-08-26-platform-primitives-lld.md) (#1)
**Published:** https://claude.ai/code/artifact/032ad740-95ab-41db-8ade-f660d9a5a7eb

The outbox is the one piece of shared mechanism every service depends on for correctness. This
document specifies it at class level: what lives in `platform`, what each service implements, how a
service adopts it, and the exact path a request takes from HTTP call to consumed message.

---

# Part 0 — Revisions, 2026-08-27

This document was written before the six-module decomposition and before LLDs #1, #3 and #4. It is
**revised, not rewritten**: every conclusion below survives, and the five changes are marked at the
point they apply rather than silently folded in.

| # | Revision | Cause |
|---|---|---|
| **1** | `platform-core` no longer exists. This module declares `platform-tenancy` and `platform-primitives` explicitly | The parent spec's P2. The declaration *is* the structural fix for TB3 |
| **2** | `OutboxWriter` serialises through **`EventJson`**, never an injected or fresh `ObjectMapper` | LLD #1 built `EventJson` as a separately-configured mapper for exactly this wire. TB3 is the bug it exists to prevent |
| **3** | `IdempotentConsumer` binds a **`SystemPrincipal`**, not `new TenantPrincipal(…, "SYSTEM")` | LLD #4's CD3 — `TenantPrincipal` is a sealed interface and the string provenance is gone |
| **4** | **`relay_app` loses `BYPASSRLS`** and gains a role-scoped policy on the outbox table alone | New — see OF6. `BYPASSRLS` is a role attribute that applies database-wide and overrides `FORCE`, which collides with LLD #4's PF14 |
| **5** | ShedLock's table ships as `V902`, and the dependency is declared | New — see OF7. `@SchedulerLock` was specified with no table and no library |

**A sixth change lands on LLD #4 rather than here.** Its CD1 schedules `TenantLoopRunner` to be built
"with the first `@Scheduled` job". The first scheduled job in the system is **this relay**, and it is
the one job that must deliberately *not* bind a tenant. Recorded as OF8; LLD #4's §2.4 is amended
rather than this document's design changed.

---

# Part 1 — Where the code lives

## 1.1 Module

```
platform/
├── platform-primitives/              LLD #1 — EventJson, money, error vocabulary
├── platform-tenancy/                 LLD #4 — TenantContext, SystemPrincipal, TenantScopedEntity
└── platform-outbox/                  NEW  (revision 1: there is no `platform-core`)
    ├── src/main/java/com/easycrm/platform/outbox/
    │   ├── DomainEvent.java              the contract a service implements
    │   ├── Outbox.java                   @Entity, tenant-scoped — the WRITE side
    │   ├── OutboxRepository.java         JPA, write only
    │   ├── OutboxWriter.java             @EventListener, runs in the caller's transaction
    │   ├── OutboxRelay.java              @Scheduled poller, ShedLock, JdbcTemplate — the READ side
    │   ├── OutboxPublisher.java          interface; SnsOutboxPublisher is the impl
    │   ├── EventEnvelope.java            the wire format
    │   ├── ProcessedEvent.java           @Entity, consumer-side dedupe
    │   ├── IdempotentConsumer.java       tenant context + dedupe + handler, one transaction
    │   ├── TraceContextCarrier.java      W3C traceparent capture and link
    │   ├── OutboxProperties.java         @ConfigurationProperties
    │   └── OutboxAutoConfiguration.java
    ├── src/main/resources/db/outbox/
    │   ├── V900__outbox.sql
    │   ├── V901__processed_event.sql
    │   └── V902__shedlock.sql            revision 5 — see OF7
    └── src/main/resources/META-INF/spring/
        └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

This holds D12's line — **`platform` contains mechanisms, never meanings**. `platform-outbox` knows
what an event *is shaped like*; it never knows what a quotation is.

## 1.2 How a service adopts it

Three things, and nothing else.

**1. One dependency.**

```kotlin
// services/sales/build.gradle.kts
dependencies { implementation(project(":platform:platform-outbox")) }
```

`platform-outbox` itself declares what it needs, which is revision 1 and is the whole point of P2:

```kotlin
// platform/platform-outbox/build.gradle.kts
dependencies {
    api(project(":platform:platform-tenancy"))       // Outbox is a TenantScopedEntity; SystemPrincipal
    api(project(":platform:platform-primitives"))    // EventJson — the structural fix for TB3
    implementation("net.javacrumbs.shedlock:shedlock-spring")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template")
}
```

`api` on primitives rather than `implementation`: TB3 is a bug of *omission*, and a dependency a
consumer cannot see is one a consumer can accidentally re-satisfy with a fresh `ObjectMapper`.

**2. Its events implement `DomainEvent`.** The existing events already have the right shape and are
already published on the existing seam:

```java
public record QuotationAcceptedEvent(UUID quotationId, UUID orderId, UUID quotationVersionId,
                                     BigDecimal grandTotal, String orderNo, UUID actorUserId)
        implements DomainEvent {
    public UUID   aggregateId()   { return quotationId; }
    public String aggregateType() { return "Quotation"; }
    public String eventType()     { return "QuotationAccepted"; }
    public short  schemaVersion() { return 1; }
}
```

**3. Configuration.**

```yaml
easycrm.outbox:
  relay:
    enabled: true
    poll-interval: 2s
    batch-size: 100
    lock-at-most-for: 60s        # NOT the sweep jobs' 10m — see parent doc §2.4
  topic-arn: ${SNS_TOPIC_ARN}
spring.flyway:
  locations: classpath:db/migration, classpath:db/outbox
  default-schema: sales
```

Everything else — beans, the relay, the publisher, the second DataSource — arrives through
`OutboxAutoConfiguration`.

## 1.3 OF5 — sharing DDL across five independent Flyway histories

The outbox table is identical in every schema, but each service owns its own migration history
(parent doc §2.8). Copying the DDL into five migration folders guarantees drift; sharing one file
across histories collides on version numbers.

Resolution: `platform-outbox` ships the DDL on the classpath, services add it as a **second Flyway
location**, and a **version namespace** keeps the numbers apart.

| Range | Owner |
|---|---|
| `V1`–`V899` | The service's own migrations |
| `V900`+ | Platform-shipped shared migrations |

The DDL uses Flyway's built-in `${flyway:defaultSchema}` placeholder, so one file resolves correctly
in every schema:

```sql
-- V900__outbox.sql
CREATE TABLE ${flyway:defaultSchema}.outbox (
  id             UUID PRIMARY KEY,
  tenant_id      UUID         NOT NULL,
  aggregate_type VARCHAR(50)  NOT NULL,
  aggregate_id   UUID         NOT NULL,
  event_type     VARCHAR(80)  NOT NULL,
  schema_version SMALLINT     NOT NULL,
  traceparent    VARCHAR(64),
  payload        JSONB        NOT NULL,
  occurred_at    TIMESTAMPTZ  NOT NULL,
  published_at   TIMESTAMPTZ,
  version        BIGINT       NOT NULL DEFAULT 0,
  created_at     TIMESTAMPTZ,
  updated_at     TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished
    ON ${flyway:defaultSchema}.outbox (occurred_at, id)
    WHERE published_at IS NULL;

ALTER TABLE ${flyway:defaultSchema}.outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE ${flyway:defaultSchema}.outbox FORCE  ROW LEVEL SECURITY;   -- LLD #4, PF14
CREATE POLICY tenant_isolation ON ${flyway:defaultSchema}.outbox
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- Revision 4. The relay must read and mark EVERY tenant's rows, and that exemption is
-- scoped by POLICY to this one table rather than by BYPASSRLS on the role. Permissive
-- policies are OR-ed, so relay_app matches through relay_reads_all while easycrm_app
-- still matches only through tenant_isolation. See OF6.
CREATE POLICY relay_reads_all       ON ${flyway:defaultSchema}.outbox
    FOR SELECT TO relay_app USING (true);
CREATE POLICY relay_marks_published ON ${flyway:defaultSchema}.outbox
    FOR UPDATE TO relay_app USING (true);

GRANT SELECT, UPDATE ON ${flyway:defaultSchema}.outbox TO relay_app;
```

`relay_app` holds **no role attribute at all** — it is an ordinary `LOGIN` role whose reach is the
two policies above plus its grants. That matters because `BYPASSRLS` is not a per-table permission:
it applies to every table in the database and it overrides `FORCE`, so the design it replaced left
the relay's containment resting entirely on the GRANT list never widening. A policy cannot widen by
accident.

The partial index is what keeps the relay's poll cheap forever: it covers only unpublished rows, so
it stays small no matter how large the table grows between reaper runs.

**Revision 5 — the lock table.** `OutboxRelay` carries `@SchedulerLock`, and ShedLock's JDBC provider
needs a table that this document never shipped. A service adopting the module exactly as §1.2
describes gets a relay that cannot take its lock. It belongs in the same shared-DDL mechanism as the
other two, in the service's own schema:

```sql
-- V902__shedlock.sql
CREATE TABLE ${flyway:defaultSchema}.shedlock (
  name       VARCHAR(64) PRIMARY KEY,
  lock_until TIMESTAMPTZ NOT NULL,
  locked_at  TIMESTAMPTZ NOT NULL,
  locked_by  VARCHAR(255) NOT NULL
);
GRANT SELECT, INSERT, UPDATE ON ${flyway:defaultSchema}.shedlock TO easycrm_app;
```

**No RLS, and that is correct rather than an omission** — a lock row belongs to a *task*, not to a
tenant, so it is a global table in the LLD #4 sense and would carry `@GlobalTable(reason = …)` if it
were an entity at all. It is not; ShedLock manages it directly. Worth stating because the tenant-
scoping ArchUnit rule is deliberately silent about tables with no entity, and this is the first one.

---

# Part 2 — Class model

## 2.1 `DomainEvent` — the contract

```java
public interface DomainEvent {
    UUID   aggregateId();      // becomes MessageGroupId — the ordering key (parent F5)
    String aggregateType();
    String eventType();        // becomes an SNS message attribute — drives filter policies
    short  schemaVersion();    // additive-only; a breaking change is a new eventType
}
```

Four methods, all metadata. The event's *payload* is the record's own components, serialised
whole — so a service adds a field by adding a component, and nothing in `platform` changes.

## 2.2 `Outbox` — the write side only

```java
@Entity
@Table(name = "outbox")
public class Outbox extends TenantScopedEntity {
    private String  aggregateType;
    private UUID    aggregateId;
    private String  eventType;
    private short   schemaVersion;
    private String  traceparent;
    @JdbcTypeCode(SqlTypes.JSON) private JsonNode payload;
    private Instant occurredAt;
    private Instant publishedAt;
}
```

Extending `TenantScopedEntity` gives `@TenantId`, so `tenant_id` is stamped by Hibernate from the
session tenant and never written by hand — and the ArchUnit tenant-scoping rule passes without an
allowlist entry.

**This entity is never used for reading.** See OF1.

## 2.3 `OutboxWriter` — the seam

```java
@Component
public class OutboxWriter {

    // Plain @EventListener: synchronous, same thread, INSIDE the publisher's transaction.
    // See OF2 — @TransactionalEventListener here would silently restore the dual write.
    @EventListener
    public void on(DomainEvent event) {
        outbox.save(new Outbox(
            event.aggregateType(), event.aggregateId(),
            event.eventType(), event.schemaVersion(),
            trace.capture(),                 // W3C traceparent, see Part 5
            EventJson.tree(event),           // revision 2 — NEVER a fresh or injected ObjectMapper
            Instant.now()));
    }
}
```

This is the whole integration surface. A service publishes a Spring event exactly as
`QuotationService` and `OrderService` already do; the outbox row is a side effect of that publish,
in the same transaction, with no service code aware of SNS.

**Revision 2.** The serialiser is `EventJson`, from `platform-primitives` (LLD #1), and the module
declares that dependency. TB3 — money crossing the wire as a JSON *number*, quietly undoing
challenges #2 and #17 across the whole asynchronous surface — is not a mistake anyone makes on
purpose; it is what happens when a mapper is constructed where no one is looking. LLD #1's R1 already
forbids building one, so this is that rule reaching its most consequential caller.

## 2.4 `OutboxRelay` — the read side

```java
@Component
@ConditionalOnProperty("easycrm.outbox.relay.enabled")
public class OutboxRelay {

    private final JdbcTemplate relayJdbc;      // relay_app DataSource — NOT the app's
    private final OutboxPublisher publisher;

    @Scheduled(fixedDelayString = "${easycrm.outbox.relay.poll-interval:2s}")
    @SchedulerLock(name = "outboxRelay",
                   lockAtMostFor = "${easycrm.outbox.relay.lock-at-most-for:60s}",
                   lockAtLeastFor = "1s")
    public void publishBatch() {
        List<EventEnvelope> batch = relayJdbc.query(SELECT_UNPUBLISHED, ROW_MAPPER, batchSize);
        if (batch.isEmpty()) return;

        PublishOutcome outcome = publisher.publish(batch);   // stops at first failure — OF4
        if (!outcome.publishedIds().isEmpty()) {
            relayJdbc.update(MARK_PUBLISHED, outcome.publishedIds());
        }
    }
}
```

```sql
-- SELECT_UNPUBLISHED
SELECT id, tenant_id, aggregate_type, aggregate_id, event_type,
       schema_version, traceparent, payload, occurred_at
  FROM outbox
 WHERE published_at IS NULL
 ORDER BY occurred_at, id
 LIMIT ?
```

`ORDER BY occurred_at, id` — `id` is a UUIDv7, whose leading bits are a timestamp, so it is a
stable tiebreak for rows written inside the same transaction rather than an arbitrary one.

## 2.5 `IdempotentConsumer` — the consume side

```java
public <T extends Record> void consume(EventEnvelope env, Class<T> type, Consumer<T> handler) {
    // runAs BEFORE the transaction opens. Hibernate resolves a session's tenant once, at
    // session-open, and never re-reads it (challenge #9). Parent doc F4.
    // Revision 3: SystemPrincipal, not a string provenance — LLD #4, CD3.
    // Entitlements are UNRESOLVED here, deliberately: the envelope does not carry them
    // and must not. See OF9.
    TenantContext.runAs(new SystemPrincipal(env.tenantId(), Entitlements.unresolved()), () ->
        tx.executeWithoutResult(status -> {
            try {
                processed.saveAndFlush(new ProcessedEvent(env.eventId()));
            } catch (DataIntegrityViolationException alreadyDone) {
                return;                       // duplicate delivery — a no-op, not an error
            }
            handler.accept(mapper.treeToValue(env.payload(), type));
        }));
}
```

The dedupe insert and the handler share **one transaction** — see OF3.

**Why the entitlements are `unresolved` and not looked up.** LLD #4 put `entitlements()` on the
`TenantPrincipal` interface, so every construction site must supply them — and a consumer restoring
context from a message is the one site that cannot. Adding them to the envelope is the obvious fix
and it is wrong: an event is immutable and entitlements are not, so a message redelivered from a DLQ
a week later would carry a plan the tenant may have since changed, and a replay would be authorised
against the past. The correct reading is narrower and worth stating once: **an entitlement check
belongs to the synchronous, user-initiated write that produced the event, not to the handler
downstream of it.** By the time a message is consumed, the metered thing has already happened. A
handler that genuinely needs a live plan must look it up, and `unresolved()` is what makes that
explicit instead of silently reading as "no entitlements". See OF9.

## 2.6 `ProcessedEvent`

```java
@Entity
@Table(name = "processed_event",
       uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "event_id"}))
public class ProcessedEvent extends TenantScopedEntity {
    private UUID    eventId;      // = outbox.id
    private Instant processedAt;
}
```

Reaped on the same schedule as the outbox. The retention floor is the maximum plausible redelivery
window, not the DLQ age — a message redriven from a DLQ a week later must still deduplicate.

---

# Part 3 — Request flow, end to end

`POST /api/v1/quotations/{id}/send`

| # | Where | What happens |
|---|---|---|
| 1 | CloudFront → ALB → sales-svc | `/api/*` behaviour, uncached, `Authorization` forwarded |
| 2 | `JwtAuthenticationFilter` | Verifies RS256 against the cached JWKS; sets `TenantContext` from the claim |
| 3 | `QuotationService.send` | `TenantAwareTransactionManager.doBegin` runs `set_config('app.current_tenant', …, true)` — transaction-local, so the Proxy never pins (parent F1) |
| 4 | " | Version frozen, quote number assigned via `document_counter … FOR UPDATE` |
| 5 | " | `events.publishEvent(new QuotationSentEvent(…))` |
| 6 | `OutboxWriter.on` | **Same thread, same transaction.** Serialises the payload, captures `traceparent`, saves. `@TenantId` stamps `tenant_id`; the RLS policy doubles as `WITH CHECK` on insert |
| 7 | commit | Quotation state and outbox row become durable **atomically**. HTTP 200 returns. Nothing has touched SNS |
| 8 | ≤2s later, `OutboxRelay` | One task holds the ShedLock row. Reads through `relayJdbc` as `relay_app` — no Hibernate, no `@TenantId`, and an RLS policy written for it rather than a role that ignores RLS (OF1, OF6) |
| 9 | `SnsOutboxPublisher` | `PublishBatch`, ≤10 entries per call. `MessageGroupId = aggregateId`, `MessageDeduplicationId = eventId`, `eventType` as a message attribute |
| 10 | " | Marks the **contiguous successful prefix** `published_at = now()` (OF4) |
| 11 | SNS | Fans out to subscribed SQS FIFO queues, each filtered by `eventType` so a consumer receives only what it handles |
| 12 | `notification-svc` `@SqsListener` | `IdempotentConsumer.consume`: `runAs` → open transaction → insert `processed_event` → run handler → commit |
| 13 | on failure | The transaction rolls back **including the dedupe row**, so redelivery re-processes. After `maxReceiveCount`, the message lands in the DLQ |

**The crash window is step 9→10.** A crash after publishing but before marking leaves rows
unpublished, and the next cycle republishes them. That is deliberate: at-least-once, absorbed
downstream by SQS's five-minute deduplication window and permanently by `processed_event`.

---

# Part 4 — Wire format

```json
{
  "eventId":       "0191f3c2-...",
  "tenantId":      "0191a8b0-...",
  "aggregateType": "Quotation",
  "aggregateId":   "0191e4d1-...",
  "eventType":     "QuotationSent",
  "schemaVersion": 1,
  "occurredAt":    "2026-08-19T09:14:22.481Z",
  "traceparent":   "00-4bf92f...-00f067aa0ba902b7-01",
  "payload":       { "quotationId": "...", "quotationNo": "QT/26-27/0042" }
}
```

| SNS attribute | Purpose |
|---|---|
| `eventType` | **Subscription filter policies.** Each queue declares the events it wants, so consumers are not shipped everything and made to discard most of it |
| `tenantId` | Debugging and per-tenant tracing. **Never a metrics dimension** — parent doc §2.5 |
| `traceparent` | Restored as a span link at consume time |

`tenantId` is an explicit field because SNS and SQS sit outside all four isolation layers: the
consumer cannot recover the tenant from ambient context, only from the message (parent F4).

Contract rule: **additive only.** Never remove or repurpose a field. A breaking change is a new
`eventType` running in parallel until every consumer has migrated.

---

# Part 5 — Trace continuity (parent F8)

The producing HTTP span has ended long before the relay publishes. So:

```java
class TraceContextCarrier {
    String capture();                  // at write:   Span.current() → W3C traceparent string
    Span   linkTo(String traceparent); // at consume: new span with addLink(), NOT a parent
}
```

A parent-child edge would assert that the consumer's work happened *inside* the producing request,
which is false and which CloudWatch Application Signals renders as a lie. A **link** states the
true relationship: caused by, not contained in.

Without this, every trace terminates at the outbox insert and the entire asynchronous half of the
system is invisible.

---

# Part 6 — What keeps it honest

## ArchUnit

| Rule | Prevents |
|---|---|
| `platform-outbox` may not reference any service package | The shared module accumulating domain meaning (D12) |
| **No class anywhere is annotated `@TransactionalEventListener`** | OF2 — the single most likely way to silently break this design |
| Every `DomainEvent` implementation is a `record` | Mutable events, and payloads that serialise differently than they read |
| Every `@Entity` extends `TenantScopedEntity` or is annotated `@GlobalTable` | LLD #4's CR1 (the FQN allowlist is gone); `Outbox` and `ProcessedEvent` both pass without an exemption |
| No `@Scheduled` method outside `platform-outbox` runs without a bound tenant | OF8 — the relay and the reaper are the *only* jobs that legitimately run cross-tenant, and they should be the only exemptions anyone ever has to justify |
| No `ObjectMapper` is constructed anywhere | LLD #1's R1, reaching its most consequential caller — revision 2 |

The second rule is one line and closes a bug that no test would reliably catch.

## Tests

| Test | Asserts |
|---|---|
| Atomicity | Roll the outer transaction back; **no outbox row exists.** This is the property the pattern exists for |
| Relay reads across tenants | Rows written under two tenants; the relay (as `relay_app`) sees both. Catches an OF1 regression |
| Ordering | Two events for one aggregate publish in write order |
| Partial batch failure | Entry 3 of 5 fails → entries 1–2 marked published, 3–5 retried, order preserved |
| Idempotency | Same envelope consumed twice → handler ran once |
| Handler failure | Handler throws → `processed_event` row absent → redelivery re-processes |
| Pinning | The relay's DataSource does not pin the Proxy (`prepareThreshold=0` honoured) |

---

# Part 7 — Test plan

## 7.1 What is genuinely unit-testable

Most of this pattern is *about* transactional behaviour, so most of its value lives in integration
tests. Saying so up front prevents a suite full of mocked repositories that prove nothing.

| Test | Asserts |
|---|---|
| Envelope round-trip | Serialise → deserialise is lossless. **Money is a JSON string, not a number** — see TB3 |
| Envelope redaction | `toString()` never emits `payload`. See TB13 |
| `TraceContextCarrier.capture()` | Valid W3C traceparent with an active span; **null, not a crash, with none** |
| `TraceContextCarrier.linkTo()` | Produces a span with a *link*, not a parent |
| **Publisher batching** | 100 events → 10 `PublishBatch` calls of 10 |
| **Contiguous prefix on partial failure** | Entry 3 of 10 fails → entries 1–2 returned as published, 3–10 not, and publishing **stops** rather than continuing to entry 4 |
| Empty batch | No SNS call is made at all |
| `DomainEvent` → envelope mapping | Every metadata field lands in the right place |

The contiguous-prefix test is the highest-value unit test in the suite: pure logic, a subtle
correctness rule, and a mocked SNS client is enough (OF4).

## 7.2 Integration tests (Testcontainers)

**Setup change:** `IntegrationTest` currently wires two roles — the owner for Flyway and
`easycrm_app` for the application. The outbox needs a **third**: `relay_app`, with `BYPASSRLS` and
grants on outbox tables only. Without it, none of the relay tests are testing the real thing.

### Atomicity — the property the pattern exists for

| # | Test |
|---|---|
| 1 | **Roll the outer transaction back → assert zero outbox rows.** If only one test is written, this is it |
| 2 | Commit → exactly one row, correct `tenant_id`, payload, `traceparent`, `published_at IS NULL` |
| 3 | A listener downstream of `OutboxWriter` throws → **both** the state change and the outbox row roll back |

### Tenant isolation

| # | Test |
|---|---|
| 4 | Written under tenant A, read via `easycrm_app` under tenant B → zero rows |
| 5 | Native insert with a foreign `tenant_id` → rejected by the RLS policy acting as `WITH CHECK` |
| 6 | **Relay sees across tenants.** Rows under A and B; the relay, as `relay_app`, reads both. The regression test for F12 *and* OF1 |
| 7 | **The boundary holds, on every table.** `relay_app` is denied on **each** table backing a `TenantScopedEntity`, generated from the entity set rather than naming `quotation` alone — so a table added later cannot quietly fall outside it. Test 6 without test 7 proves only that a hole exists, not that it is the right size |
| 7a | **`relay_app` holds no `BYPASSRLS`.** Assert `rolbypassrls = false` in `pg_roles`. Revision 4's entire safety argument is that the exemption is per-table; a role attribute granted later in an unrelated migration would silently restore the database-wide bypass and no other test would notice (OF6) |
| 7b | **A second tenant-scoped table is not reachable through the policy.** Insert into `processed_event` under two tenants, read as `relay_app` → only its own tenant's rows, because no `relay_*` policy exists there |
| 8 | Consumer writes land under the envelope's tenant, never the ambient one |

### Ordering

| # | Test |
|---|---|
| 9 | Two events for one aggregate in one transaction → published in write order |
| 10 | Two events for one aggregate in separate transactions → published in commit order |
| 11 | Identical `occurred_at` → deterministic order by `id`. **If this is flaky, `UuidV7` is not monotonic within a millisecond and that is a finding, not a flake** |
| 12 | **Interleaved commit (TB7).** Transaction A opens, B opens later and commits first, relay polls, then A commits. Assert the aggregate-level guarantee still holds |

### At-least-once and failure

| # | Test |
|---|---|
| 13 | Publish succeeds, the `MARK_PUBLISHED` update fails → next cycle republishes → consumer dedupes |
| 14 | Entry 3 of 5 fails → 1–2 marked, 3–5 retried next cycle, order preserved |
| 15 | Same envelope consumed twice → handler invoked **once**, one `processed_event` row |
| 16 | Handler throws → `processed_event` row **absent** after rollback → redelivery re-processes |
| 17 | Raced dedupe: two consumers, same envelope. One wins the unique constraint, one no-ops via `DataIntegrityViolationException`. Provable single-threaded, matching the repo's existing pattern for challenge #15 |

### Locking and schema

| # | Test |
|---|---|
| 18 | Two relay instances fire together → exactly one publishes |
| 19 | Lease expiry — holder "dies", another instance may run after `lockAtMostFor` |
| 20 | Flyway applies both locations cleanly; the `V900` namespace does not collide |
| 20a | `V902` creates the ShedLock table in the service's own schema, and two relay instances actually contend on it (test 18 is vacuous without this — OF7) |
| 21 | The outbox table has the expected shape **in every service schema** — catches hand-edited drift |
| 22 | `EXPLAIN` the relay query → index scan on `idx_outbox_unpublished`, not a seq scan |
| 23 | **Expand/contract:** apply the new migrations to a database seeded at the previous release, then boot the *previous* entity mappings under `ddl-auto: validate`. The regression test for parent F14, and almost nobody builds it |

### Not testable here

Proxy pinning (parent F1/F2) cannot be reproduced in Testcontainers. It belongs in a staging smoke
test asserting `DatabaseConnectionsCurrentlySessionPinned` stays at zero under load.

## 7.3 Bugs you will hit, and the fix

| # | Symptom | Cause | Fix |
|---|---|---|---|
| **TB1** | Events silently stop flowing; outbox lag climbs; no error anywhere | The relay reads through JPA and `@TenantId` filters to zero rows (OF1), or RLS does — and after revision 4 there is a third way in: the `relay_*` policies were never created, or were created on one schema and not another | `JdbcTemplate` on the `relay_app` DataSource, plus test 20's per-schema shape check now covering policies as well as columns. **Detection depends entirely on the outbox-lag alarm existing** |
| **TB2** | Events occasionally missing under load; every test passes | `@TransactionalEventListener` instead of `@EventListener` (OF2). Tests commit, so the dual write never shows | Plain `@EventListener`, plus the ArchUnit ban |
| **TB3** | Money arrives at the consumer as a JSON **number** and re-acquires `double` rounding error | The outbox `ObjectMapper` is a fresh instance without `BigDecimalStringModule` registered | **Revision 2:** `EventJson`, from a declared `platform-primitives` dependency — not the application's mapper either, since the HTTP wire and the event wire are configured separately (LLD #1 §2.3). LLD #1's R1 makes the fresh instance unbuildable |
| **TB4** | `ClassNotFoundException`, or a mapper that ignores every module | Boot 4 ships Jackson under `tools.jackson`, not `com.fasterxml.jackson` (challenge #10) | Import the right package; assert module registration in a test |
| **TB5** | Throughput does not scale; `DatabaseConnectionsCurrentlySessionPinned` > 0 | `prepareThreshold=0` set on the primary DataSource and **forgotten on the relay's** — it is hand-configured, so it is easy to miss | Set it on both. Assert it in a config test |
| **TB6** | One task dies and every event in the system stalls for minutes | `lockAtMostFor` copied from the sweep jobs (10m) | 60s for the relay specifically |
| **TB7** | Events for different aggregates publish out of `occurred_at` order | **Interleaved commits.** Transaction A (earlier `occurred_at`) is still open when B commits; the relay polls and sees only B; A commits afterwards and publishes second. Inherent to *every* polling outbox | **Per-aggregate ordering still holds here, and for a specific reason:** two transactions writing events about the same quotation must both load and modify it, and `@Version` optimistic locking makes the second commit fail with 409 (challenge #26). Concurrent same-aggregate commits cannot both succeed. Cross-aggregate ordering was never guaranteed (F5). The caveat: an event published *without* touching its aggregate under the optimistic lock loses this protection |
| **TB8** | Ordering test flakes; rare real misordering within a transaction | `UuidV7` may not be monotonic inside one millisecond | Verify the generator has a counter. If not, order by an explicit column rather than `id` |
| **TB9** | Cross-tenant writes — the worst outcome available | `runAs` called *after* the transaction opens, or `open-in-view` re-enabled | `runAs` before; `spring.jpa.open-in-view: false` stays load-bearing (challenges #9, #29). LLD #4's CR2 gives this its first mechanical form |
| **TB15** | The relay works in one service and silently publishes nothing in another | The `relay_*` policies live in shared DDL but the **role** does not — `relay_app` is created once per database while the policies are created once per schema, so a schema whose `V900` ran before the role existed has policies referencing a role that was not there | Create the role in the database bootstrap, ahead of every service's Flyway run, and assert its existence in `V900` rather than assuming it. Appendix B item 9 |
| **TB10** | A DLQ redrive a week later re-sends WhatsApp messages to real customers | `processed_event` reaped on the outbox's 7-day schedule | Retention ≥ maximum DLQ retention (14 days), not the outbox reaper's |
| **TB11** | The dedupe `catch` never fires; the whole transaction fails instead | `save()` without `flush()` — the constraint violation surfaces at commit, outside the try/catch | `saveAndFlush` |
| **TB12** | Phone numbers and GSTINs appear in CloudWatch Logs | A consumer logs the whole envelope on error | `EventEnvelope.toString()` redacts `payload`; log `eventId` and `eventType` only. DPDP, not tidiness |
| **TB13** | Relay tests pass alone, fail in a suite | Unpublished rows left by earlier tests, on the shared singleton container | Truncate in `@BeforeEach`, and assert on a specific `eventId` rather than "the batch" |
| **TB14** | Payload stored as a quoted string rather than JSONB | `@JdbcTypeCode(SqlTypes.JSON)` without a Hibernate 7 `FormatMapper` wired to Jackson 3 | Configure the format mapper; assert the column type in a test |

---

# Appendix A — Findings

| # | Finding |
|---|---|
| **OF1** | **`@TenantId` defeats the relay even with `BYPASSRLS`.** Parent F12 fixed the *database* layer with a `relay_app` role. But Hibernate appends its own tenant predicate to every query on a `@TenantId` entity, from the session tenant identifier — which the relay does not have. So a JPA read returns zero rows even as `relay_app`. **The write path uses JPA; the read path must use `JdbcTemplate` on a separate `relay_app` DataSource.** Two layers, two fixes; fixing only the database one leaves the relay just as broken and just as silent |
| **OF2** | `@TransactionalEventListener(AFTER_COMMIT)` writes the outbox row in a *separate* transaction, restoring the dual write the outbox exists to eliminate. It reads as the more correct annotation, which is what makes it dangerous. Plain `@EventListener` is required, and an ArchUnit rule bans the alternative outright |
| **OF3** | The dedupe insert and the handler must share one transaction. Committing the dedupe row separately means a handler failure permanently marks the event processed, and the work is lost with no error anywhere |
| **OF4** | `PublishBatch` reports success per entry. Marking the whole batch published loses the failures; publishing the rest of the batch after a failure breaks per-aggregate ordering. Only the **contiguous successful prefix** may be marked, and publishing stops at the first failure |
| **OF5** | Five independent Flyway histories cannot share a migration file without colliding on version numbers. Resolved by a version namespace — services own `V1`–`V899`, platform-shipped migrations start at `V900` — plus `${flyway:defaultSchema}` so one file resolves per schema |
| **OF6** | **`BYPASSRLS` is a role attribute, not a table permission.** It applies to every table in the database and it **overrides `FORCE`**, so the `relay_app` role as originally specified could not be constrained by LLD #4's PF14 fix, and its containment rested entirely on the GRANT list never widening — a procedural guarantee inside a design whose thesis is that isolation must be structural. Replaced by two role-scoped permissive policies on the outbox table alone (revision 4). Every other conclusion here survives, OF1 included: Hibernate's `@TenantId` still filters a JPA read to zero rows regardless of what the database permits, so the relay still needs `JdbcTemplate` on its own DataSource. **Two layers, two fixes — and now neither of them is database-wide** |
| **OF7** | `@SchedulerLock` was specified with **no lock table and no library**. Test 18 ("two relay instances fire together → exactly one publishes") could not have passed, and a service adopting the module per §1.2 gets a relay that fails at first lock acquisition. `V902` and the two ShedLock dependencies close it. The table is deliberately not tenant-scoped — a lock belongs to a task |
| **OF8** | **The first `@Scheduled` job in the system is the one that must not bind a tenant.** LLD #4's CD1 schedules `TenantLoopRunner` to be built "with the first scheduled job", and that job is this relay, whose whole purpose is to read across tenants. The adapter and its first apparent consumer want opposite things. Resolved by amending LLD #4 rather than this design: the relay and the reaper are named exemptions, and the ArchUnit rule in Part 6 makes any *further* exemption something a reviewer has to approve |
| **OF9** | **A consumer cannot know the tenant's entitlements, and must not be told.** LLD #4 put `entitlements()` on the `TenantPrincipal` interface, so every construction site supplies them — and restoring context from a message is the one site that cannot. Putting them in the envelope looks obvious and is wrong: an event is immutable, a plan is not, and a DLQ redrive a week later would authorise a replay against a plan the tenant no longer has. `Entitlements.unresolved()` states the gap instead of hiding it behind an empty set. **The general rule this exposes:** an entitlement check belongs to the synchronous write that produced the event, never to the handler downstream of it. Feeds back to LLD #4's CF8 |

# Appendix B — To verify

1. Flyway's `${flyway:defaultSchema}` placeholder name and that it resolves inside `CREATE TABLE`.
2. That two Flyway locations merge into one history cleanly, with the version namespace keeping
   ordering sane (OF5).
3. Hibernate 7's exact `@TenantId` predicate behaviour when no tenant identifier is resolvable —
   whether it filters to zero rows or throws. OF1 assumes it filters silently, which is the
   dangerous case; if it throws, the failure is loud and OF1 is less severe.
4. `@JdbcTypeCode(SqlTypes.JSON)` with Jackson 3 (`tools.jackson`) under Boot 4.1 — the Jackson
   package move has already broken assumptions once (challenge #10).
5. SNS FIFO `PublishBatch` ordering semantics within a single `MessageGroupId` across a batch, and
   whether partial failure preserves the order of the succeeded entries.
6. SQS deduplication interaction: `MessageDeduplicationId` gives a five-minute window; confirm
   `processed_event` is genuinely required beyond it rather than merely belt-and-braces.
7. **That permissive RLS policies are OR-ed as revision 4 assumes** — that `relay_app` matching
   `relay_reads_all` grants it every row even though `tenant_isolation` (which has no `TO` clause, so
   it applies to `PUBLIC`) matches none. If Postgres AND-ed them, or if an unqualified policy took
   precedence, the relay reads zero rows and OF6's fix is worse than what it replaced. **Verify this
   first** — it is the load-bearing assumption of the whole revision.
8. **That `FORCE ROW LEVEL SECURITY` on the outbox table does not break the reaper**, which deletes
   published rows across tenants and must therefore have a `FOR DELETE` policy of its own — the
   design names the reaper but never says which role runs it.
9. **When `relay_app` is created relative to each service's Flyway run.** The policies are per-schema
   and the role is per-database; a `V900` executing before the role exists fails or, worse, succeeds
   against a role created later with different attributes (TB15).
10. ShedLock's Boot 4 / Spring 7 compatibility and its exact table DDL for the JDBC provider version
    resolved by the platform BOM — `V902` above is written from the documented schema, not verified.
