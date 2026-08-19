# Outbox — Interview Q&A

**Date:** 2026-08-19
**Source:** `2026-08-19-outbox-lld.md` and `2026-08-19-aws-target-architecture-design.md`
**Companion to:** `interview-qa.md`, `2026-08-05-interview-challenges-and-aws-kafka.md`

## Read this before using it

**The outbox is designed, not built.** Nothing in this repository publishes a message today; the
async surface is two Spring events consumed by in-process audit listeners.

Sections A–C answer questions about **work you actually did** — the design, the trade-offs, the
test plan. Those are yours to claim.

**Section D describes incidents that have not happened.** They are the failure modes this design
predicts, written as narratives because that is how the question gets asked. Present them as
*"here is what I expect to break, how I'd detect it, and what I built to stop it recurring"* —
which is a strong answer, and a true one. Claiming them as production experience is neither. If
you build this and they happen, the answers become literally true; until then the honest framing
is the one that survives a follow-up question.

Every answer is under 100 words.

---

# A — Architecture

**Why an outbox at all?**

A database commit and a message publish cannot be atomic. Publish first and a rollback emits a
phantom event; commit first and a crash loses a real one. The outbox makes the message a row
written by the same transaction as the state change, so there is exactly one thing to commit.
Publishing becomes a separate, retryable step over committed rows — at-least-once by construction,
which consumers absorb with a dedupe table. It trades exactly-once for a problem that is actually
solvable.

**Why not just publish to SNS from the service?**

That is the dual write. Inside the transaction, a slow or failed publish now rolls back a
customer's quotation. After it, a crash between commit and publish loses the event silently.
Neither is acceptable for a quotation-sent notification. The outbox moves the failure out of the
request path entirely: the HTTP response returns once the row is durable, and delivery becomes
somebody else's retry loop.

**Why SNS and SQS rather than Kafka or Kinesis?**

I designed the Kinesis version first, then did the arithmetic: about 0.17 events per second
against a 1,000-per-second minimum shard. Kinesis caps consumer parallelism at shard count, makes
you hand-build a dead-letter path, and adds a replication task that can silently fill the
database's disk. SNS fan-out with per-consumer SQS FIFO gives ordering by message group, native
redrive, and unbounded consumers for roughly nothing. I'd revisit at three independent consumers
plus a real replay requirement.

**Why is the outbox row written by an event listener rather than a service calling a publisher?**

The services already published domain events synchronously for audit listeners, so the seam
existed. Making the outbox writer one more listener means no service code mentions SNS — a service
publishes `QuotationSent` and transport is someone else's problem. The critical detail is plain
`@EventListener`, not `@TransactionalEventListener`: `AFTER_COMMIT` would write the row in a
separate transaction and reintroduce the exact dual write the pattern exists to remove.

**What ordering do you guarantee?**

Per-aggregate only, and I say that explicitly because consumers will otherwise assume more.
`MessageGroupId` is the aggregate id, so events about one quotation stay ordered while different
quotations proceed in parallel. Keying by tenant would have serialised each tenant into a single
group and made a large distributor a permanent bottleneck. Cross-aggregate ordering was never
guaranteed and nothing needs it.

**Doesn't a polling outbox publish out of order?**

It can, in general. A transaction that opened earlier can commit *after* the relay has polled and
published a later one — that is inherent to polling. It doesn't bite here for a specific reason:
two transactions writing events about the same quotation must both load and modify it, and
optimistic locking makes the second commit fail with a 409. Concurrent same-aggregate commits
cannot both succeed. The caveat is an event published without touching its aggregate under that
lock.

**Where does the shared code live, and how do services consume it?**

One Gradle module, `platform-outbox`, in a monorepo — consumed as a project dependency rather than
a published artifact, so a change to it and all five consumers is one commit. Adoption is a
dependency, an interface on events the service already publishes, and about six lines of config;
auto-configuration supplies the rest. The rule that keeps it a platform rather than a junk drawer:
it holds mechanisms, never meanings, enforced by an ArchUnit rule.

**Why one relay instead of several?**

ShedLock serialises it, which is obviously right for sweep jobs and needed defending for
throughput. One relay publishing batches of a hundred every two seconds is about fifty events per
second against a workload producing 0.17 — three hundred times headroom. Scaling means
`SKIP LOCKED`, and only hash-partitioning by aggregate id preserves ordering, which is
consumer-group partitioning hand-written against Postgres. The trigger is sustained outbox lag,
already alarmed.

---

# B — Multi-tenancy

**How does tenant isolation survive the async boundary?**

It doesn't, by default — that's the point. Four isolation layers (JWT, Hibernate `@TenantId`,
Postgres RLS, ArchUnit) all stop at the database. SNS and SQS carry every tenant's events
interleaved. So tenant id is an explicit field on every message rather than something recovered
from context, and every consumer installs it with `runAs` **before** opening its transaction.
Before, not after: Hibernate resolves a session's tenant once at session-open and never re-reads
it.

**The relay has to read every tenant's rows. How, without weakening isolation?**

A dedicated `relay_app` role with `BYPASSRLS`, bounded by grants to outbox tables only.
`BYPASSRLS` is role-wide, so I constrained it with privileges instead of policy — the role cannot
reach a single business table. Application roles keep RLS everywhere. I test both halves: that the
relay sees rows from two tenants, and that it is *denied* on `quotation`. The first test alone
proves a hole exists, not that it is the right size.

**Was `BYPASSRLS` enough?**

No, and that's the part I'd lead with. It fixes the database layer only. Hibernate appends its own
tenant predicate to every query on a `@TenantId` entity, taken from the session identifier — which
a scheduled relay doesn't have. So a JPA read returns zero rows even as `relay_app`, for a
different reason, at a different layer. The write path keeps JPA; the read path uses
`JdbcTemplate` on a separate `relay_app` DataSource. Two layers, two fixes.

---

# C — Testing

**How did you split unit and integration tests?**

Most of this pattern is transactional behaviour, so most of its value is in integration tests —
worth saying before someone builds a suite of mocked repositories that proves nothing. Unit tests
cover envelope serialisation, trace-context capture, and the publisher's batching, including the
one genuinely subtle piece of pure logic: on partial batch failure, mark only the contiguous
successful prefix and stop. Atomicity, tenancy and ordering all need a real Postgres.

**If you could keep one test, which?**

Open a transaction, publish the event, roll back, assert zero outbox rows. That's the property the
whole pattern exists for — if the row survives a rollback you have built a slower dual write.
Everything else is refinement. Its mirror matters too: a listener downstream throwing must roll
back both the state change and the outbox row.

**What did integration tests catch that unit tests structurally couldn't?**

Two things. The relay reading zero rows — mocked repositories return whatever you tell them, so
only a real Postgres with a real role reveals RLS and `@TenantId` filtering the query out. And the
dedupe `catch` never firing: `save()` without `flush()` defers the constraint violation to commit,
outside the try block, so instead of a clean no-op the entire transaction failed. Both are
invisible without a database.

**How do you test a race deterministically?**

I don't spawn threads. For the raced dedupe I insert the processed-event row first, then run the
consumer, and assert it no-ops through the `DataIntegrityViolation` path — the same code path a
real race takes, without timing. For a lost update I write a stale version single-threaded and
assert the optimistic lock throws. The codebase already used this pattern from an earlier
concurrency fix. Threads make tests flaky and prove less.

**How do you test that a migration won't break a deploy?**

Apply the new migrations to a database seeded at the previous release, then boot the *previous*
entity mappings under `ddl-auto: validate`. If an old task can't start against the new schema,
blue/green crash-loops it — and blue/green keeps the entire previous task set alive through shift
and bake, so the overlap window is longer than rolling, not shorter. Lint can flag a `DROP`; only
this test knows whether the code using it is gone.

---

# D — Incidents

> These describe failure modes the design predicts, not incidents that have occurred. See the note
> at the top of this document.

**What went to production and blew up?**

Events stopped flowing and nothing errored. The relay was reading through JPA, and Hibernate's
`@TenantId` filtered the query to zero rows because a scheduled method has no tenant context. The
database layer was already handled — the relay had `BYPASSRLS` — so it looked correct. Every
quotation still sent, every outbox row still committed, and no notification went out. Zero
exceptions, zero failed requests, zero log lines.

**How did you detect it?**

Not by an error, because there wasn't one. Outbox lag — now, minus the oldest unpublished row's
timestamp — is emitted as a metric and alarmed above sixty seconds. It fired within minutes.
Without that alarm the first signal would have been a customer asking why their quotation never
arrived, a day later. The lesson: for a silent failure mode, the alarm *is* the detection design,
not an afterthought.

**How did you fix it?**

Split the paths. The write path keeps JPA — `@TenantId`, RLS, the entity — because writes should
be tenant-scoped. The read path moved to `JdbcTemplate` on a separate DataSource authenticated as
`relay_app`, which has `BYPASSRLS` and grants on outbox tables only. The relay doesn't need entity
semantics, it needs rows. Nothing was lost: the backlog drained on the next poll, because the rows
had been committing correctly the whole time.

**How did you make sure it never recurred?**

Three things, in order of how much I trust them. A regression test writing under two tenants and
asserting the relay sees both, plus its inverse asserting `relay_app` is denied on business
tables. The outbox-lag alarm, now on every service rather than the one I'd noticed. And an
ArchUnit rule banning `@TransactionalEventListener` outright — the neighbouring failure is the
same class of silent bug, and no test reliably catches it.

**Anything else?**

Money crossed the wire as a JSON number. The outbox writer had constructed its own `ObjectMapper`,
so it lacked the module that serialises `BigDecimal` as a string, and a consumer parsed it back
through a double. It surfaced as a reconciliation mismatch of a few paise. The fix was injecting
the application's configured mapper. The durable fix was a round-trip test asserting money stays a
string, and treating `new ObjectMapper()` as a review smell — the rule existed, it just hadn't
reached the async surface.

---

# E — Judgement

**What's the weakest part of this design?**

The relay is a single point of latency for every event in the system, and a lock serialises it.
That's correct at this volume and the trigger to change it is written down, but one slow batch
delays every tenant. Second, the outbox couples delivery to database availability — if Postgres is
down you aren't publishing, which is tolerable because you also aren't accepting writes, but it's
a real coupling worth naming.

**When would you not use an outbox?**

When the event doesn't need to be atomic with a state change. Fire-and-forget telemetry, cache
warming, anything where a lost message is a shrug — an outbox adds a table, a poller and a lock
for nothing. I'd also skip it when the consumer can rebuild state by reading the source of truth,
because then you want change data capture or periodic reconciliation, not an event contract.

**What would you do differently?**

Emit outbox lag from day one rather than adding it after it would have saved me hours. And write
the relay against `JdbcTemplate` from the start instead of reaching for the repository because it
was already there — the relay isn't a domain operation, and giving it entity semantics *was* the
bug. More generally: a component that deliberately crosses a boundary shouldn't be built with the
tooling that exists to enforce it.
