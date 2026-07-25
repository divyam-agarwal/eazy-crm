# EasyCRM — Engineering Challenges Log

A running log of the interesting, non-obvious problems encountered building EasyCRM,
and how we solved them. Captures challenges from **both brainstorming/design and
implementation**. Each entry: the problem, why it's hard, the solution, and the
lesson worth carrying forward.

> This is a **living document** — append new entries as they come up during
> implementation. Newest challenges can go at the bottom; keep the numbering stable.

---

## Challenge 1 — Isolating tenants at multiple levels

**Phase:** Design

### The problem

EasyCRM is multi-tenant on a **shared schema** — every company's data lives in the
same tables, distinguished only by a `tenant_id` column. Tenant A's rows and Tenant
B's rows sit side by side in `quotation`, `customer`, etc. A single query that
forgets its tenant filter leaks one company's data to another. For a CRM, one such
leak is fatal to the business.

Relying on a single mechanism is not acceptable, because every single mechanism has
a bypass:
- Application-level `WHERE tenant_id = ?` can be **forgotten** by a developer.
- ORM-level filtering doesn't cover **raw/native SQL** (reports, scripts).
- Any single check is also a single point of failure for **future** code — the table
  someone adds next year and forgets to protect.

### The solution — defence in depth, four independent layers

Any one layer failing still leaves three standing.

1. **Tenant resolution from the JWT only.** On each request a `JwtAuthFilter`
   validates the signed token and sets `TenantContext(tenantId, userId, role)` — a
   `ThreadLocal`, cleared in a `finally` block (pooled threads must not leak
   context). Tenant is **never** read from a header, query param, or subdomain the
   client could set. This decides *which* tenant you are, un-forgeably.

2. **Hibernate `@TenantId` discriminator.** Every tenant-scoped entity declares
   `@TenantId`; a `CurrentTenantIdentifierResolver` reads `TenantContext`. Hibernate
   then auto-appends `tenant_id = ?` to every query and auto-populates it on insert.
   Developers can't forget it — they never write it.

3. **PostgreSQL Row-Level Security (the net under everything).** A per-table policy
   `USING (tenant_id = current_setting('app.current_tenant')::uuid)` makes the
   *database itself* refuse foreign rows, regardless of how the query is written —
   including native SQL that bypasses Hibernate. The app connects as a role
   **without `BYPASSRLS`**; migrations run under a separate owner role.

4. **Build-time ArchUnit tests.** A rule asserts every `@Entity` under `domain..`
   declares a `@TenantId` field (unless explicitly allowlisted in `GLOBAL_TABLES`).
   A new, unscoped entity **fails the build** — this protects future code, not just
   today's.

### The hard sub-problem: RLS + a connection pool

RLS needs to know "who is the current tenant" on a connection. But connections are
**pooled and reused** across tenants, so we can't set it once. Solution: a custom
`JpaTransactionManager` issues `SET LOCAL app.current_tenant = ?` in `doBegin`.
`SET LOCAL` is **transaction-scoped** — it auto-clears on commit/rollback, so the
setting never leaks back into the pool for the next tenant. Non-transactional reads
are configured to run in a transaction so they, too, get the setting.

### The proof (and the demo)

- Cross-tenant integration test: log in as A, request B's resource → **404, not
  403** (403 would confirm the record exists).
- RLS test: a raw JDBC query with no tenant setting returns **zero rows** — proving
  the *database* enforces isolation, not just the app.

### Lesson

Security-critical invariants should be **structural, not procedural**. Don't rely on
every developer remembering to filter — make the ORM add it automatically, make the
database refuse violations, and make the build fail if a table opts out. Each layer
guards a different bypass (forgetting, raw SQL, future code).

---

## Challenge 2 — Money and numbers without rounding errors

**Phase:** Design

### The problem

`double`/`float` store numbers in **base-2**, and common decimals (`0.1`, `0.01`,
`18.5`) have no exact base-2 representation — so they're stored as approximations
that compound across arithmetic (`0.1 + 0.2 == 0.30000000000000004`). Harmless in a
physics sim; unacceptable for money. Across dozens of GST line items the drift
reaches a rupee, and then **our quote total ≠ the Tally invoice total** — the exact
trust-break the whole GST design exists to prevent.

### The solution — a base-10 decimal at *every* hop

The number must never be widened to a `double` at any layer. Break the chain
anywhere and the bug returns, so each link has its own guard.

| Hop | Type | Guard |
|-----|------|-------|
| Postgres column | `NUMERIC(18,2)` amounts, `NUMERIC(18,4)` rates | never `double precision` |
| JPA entity field | `BigDecimal` | ArchUnit rule fails build on money-as-`double` |
| Arithmetic | `BigDecimal`, String-constructed, explicit-scale divide, HALF_UP | central `Money` value type; no raw `double` ops exist |
| Rounding point | per-line, then sum | matches Tally exactly |
| JSON wire | serialized as **string**, plain (no sci-notation) | JS numbers are doubles; string dodges re-introduction |
| React | string + display formatting only; server recomputes on save | client is never authoritative |

**Arithmetic rules that matter:**
- Construct from `String`/`NUMERIC`, **never `new BigDecimal(aDouble)`** (that drags
  the binary error back in).
- Division always specifies scale + `RoundingMode.HALF_UP` (Indian invoicing
  convention; matches Tally). Chosen once, centrally.

**Where you round is as important as the type.** Tally rounds **per line, then
sums**. So do we:
```
line:  taxable   = round(qty × rate − discount, 2)
       gstAmount = round(taxable × rate / 100, 2)   ← rounded at the line
       lineTotal = taxable + gstAmount
total: Σ lineTotal                                  ← sum of already-rounded lines
```
Not "sum raw lines then round once" — that drifts a paise/rupee from Tally. For
intra-state, CGST and SGST are each rounded independently to 2 dp, matching Tally's
two half-rate lines.

**The wire is the sneaky part.** Every JavaScript number is a `double`. If money is
serialized as a JSON *number*, `JSON.parse` re-introduces the error we just removed.
So Jackson serializes `BigDecimal` as a **JSON string** with `WRITE_BIGDECIMAL_AS_PLAIN`
(no scientific notation), React treats money as strings and formats for display only
(`toLocaleString('en-IN')`), and **the server recomputes every figure on save and
overwrites the client's numbers** — the browser preview is allowed to be wrong; the
stored/PDF'd/WhatsApped figure is always the server's exact one.

### Lesson

Correctness for money is an **end-to-end property**, not a single type choice. It's
only solved when the value is an exact base-10 decimal at the DB, in Java, on the
wire, and in the browser — with rounding done at the same point and mode as the
system you must reconcile against (Tally). One authoritative computation (server),
never two.

---

## Challenge 3 — Non-durable in-process events + crash/power-loss recovery

**Phase:** Design

### The problem

When a quotation is accepted, other things must happen: an **order is created** and
an **activity is logged**. We wanted these decoupled (the quotation code shouldn't
reach into the order code), so we used Spring's `ApplicationEventPublisher` — an
**in-memory, in-process publish/subscribe**. But in-memory pub/sub is **not
durable**: if the machine crashes mid-handling, the event is simply gone; there is
no queue that redelivers it.

Two failure questions fall out of this, especially on tier-2 India's flaky power and
4G:
1. If we crash partway, can we end up with a **half-done** state (quote accepted but
   no order, or vice versa)?
2. If the user gets no acknowledgement and **resends**, do we create a **duplicate**
   order?

### The solution — atomicity for consistency, idempotency for duplicates

**Make the pub/sub synchronous and same-transaction.** `ApplicationEventPublisher`
is synchronous by default and, by default, listeners run in the **same transaction**
as the publisher. We keep it that way deliberately: publishing
`QuotationAcceptedEvent`, creating the order, and logging the activity all commit or
roll back **together**. This turns "non-durable event" from a bug into a non-issue:
if anything fails, the whole transaction rolls back and there is no dangling
half-order. The event doesn't need to be durable because it never outlives its
transaction. (We keep the async/real-broker seam available for later, when a listener
genuinely should survive a crash — but we don't pay for it before we need it.)

That leaves two distinct crash windows:

| Crash moment | Order saved? | User retries → | Safe because |
|--------------|--------------|----------------|--------------|
| **Before commit** | No | creates the order fresh | Transaction **atomicity** — nothing was half-done |
| **After commit, no ack reached user** | Yes | returns the **same** order | **Idempotency key** dedupes the action |

The second window is the subtle one: the transaction committed (order really exists),
but the server crashed / the connection dropped **before the success response reached
the phone**. The user sees no ack, assumes failure, and resends — which would create
a twin order.

**Idempotency key.** The client generates a unique key for the *action* (not the
order) and sends it with quotation-accept / order-create. The server records
"key `abc-123` → order #500". A retry with the same key returns the **existing**
order #500 instead of creating a new one. So the user can retry zero, one, or five
times and always ends with exactly **one** order — which is exactly the behaviour you
want on unreliable connectivity, where "no ack, so resend" is the correct thing for
the user to do.

### Lesson

Choose the **weakest tool that's actually sufficient** — synchronous same-transaction
events give you clean module decoupling *and* dodge the durability problem entirely,
without operating a message broker. Then close the two remaining gaps precisely:
**atomicity** handles the pre-commit crash (consistency), **idempotency** handles the
post-commit-no-ack retry (no duplicates). Together: "the user can retry as much as
they want and always end up with exactly one order."

---

## Challenge 4 — Spring Boot 4 split auto-config: Flyway silently absent

**Phase:** Implementation (P0, Task 2/3)

### The problem

The Testcontainers harness booted, but `HarnessBootTest` failed with
`FATAL: password authentication failed for user "easycrm_app"` — even though the
V1 migration that creates that role was present. Two red herrings made this
confusing: (1) PostgreSQL's default `scram-sha-256` auth returns "password
authentication failed" for a **non-existent** role too (it deliberately hides
whether a role exists, to prevent username enumeration), so the message did *not*
mean "wrong password"; and (2) the surface error was a Hibernate "Unable to
determine Dialect" — a downstream symptom of the failed connection.

The real cause: **Flyway never ran.** A full startup log showed *zero* Flyway
lines, and the app-role HikariPool did `checkFailFast` and failed before any
migration executed. `flyway-core` was on the classpath (v12.4.0), yet
`FlywayAutoConfiguration` was absent.

### The solution

**Spring Boot 4.0 split its auto-configurations out of the monolithic
`spring-boot-autoconfigure` jar into per-integration modules.** Having the
third-party library (`flyway-core`) on the classpath no longer brings the Spring
Boot auto-configuration that wires it up — that now lives in a separate module.
Fix: depend on **`spring-boot-starter-flyway`** (which bundles the
`spring-boot-flyway` auto-config module + `flyway-core`) instead of `flyway-core`
directly.

### Lesson

On a new major framework version, "the library is on the classpath" is not the
same as "the framework auto-configures it." When an integration silently does
nothing, check whether its auto-config moved modules before debugging the
integration itself. And read database auth errors literally: `scram` hides role
existence, so "password authentication failed" often means "no such role." Only a
real integration test against real PostgreSQL (Testcontainers) surfaces both of
these — an in-memory H2 test would have hidden the Flyway/role/RLS layer entirely.

---

## Challenge 5 — Testcontainers flakiness: container-per-class vs singleton

**Phase:** Implementation (P0, Task 9)

### The problem

Each integration test passed in isolation, but the **full suite** failed with
`java.net.ConnectException` (connection refused to Postgres). The harness used the
common `@Testcontainers` + `@Container static` pattern, which starts a **separate
Postgres container per test class**. Running several integration classes spun up
several containers; combined with a slow/hanging `docker-credential-desktop` helper
on macOS Docker Desktop (a 30s auth-lookup timeout), one container wasn't reachable
when its test ran.

### The solution

Switch to the Testcontainers **singleton-container pattern**: one
`PostgreSQLContainer` started once in a `static {}` block on the shared
`IntegrationTest` base class, reused by every subclass, never explicitly stopped
(ryuk reaps it at JVM exit). Combined with Spring's test **context caching** (same
`@SpringBootTest` config → one cached `ApplicationContext` across classes), the
whole suite now runs against a single container and a single Flyway migration pass —
dropping from N container starts to 1. Result: reliable *and* the suite went from
~1 minute to ~4 seconds.

### Lesson

`@Container static` scopes the container to the *class*; for a suite it multiplies
startups and multiplies exposure to any docker-daemon flakiness. When many test
classes need the same backing service, one shared singleton is more reliable and far
faster. Faster tests are also more reliable tests — less time in flight means fewer
chances to hit a transient daemon hiccup.

---

## Challenge 6 — RLS policy: a custom GUC resets to '' , not NULL

**Phase:** Implementation (P0, Task 11)

### The problem

With RLS enabled and the policy
`tenant_id = current_setting('app.current_tenant', true)::uuid`, the raw-query test
failed with `ERROR: invalid input syntax for type uuid: ""`. The assumption was that
`current_setting('app.current_tenant', true)` returns **NULL** when no tenant is set
(so `NULL::uuid` = NULL → no rows). But it returned an **empty string**.

Two related surprises:
- A **custom** GUC (`app.current_tenant`) that has been *referenced* becomes a
  registered placeholder whose default is `''`, not unset/NULL. So after a
  transaction-local `set_config(..., true)` reverts, `current_setting(...)` yields
  `''`, and `''::uuid` throws.
- The policy also governs **INSERT** (its `USING` doubles as `WITH CHECK` when no
  explicit `WITH CHECK` is given), so before the transaction manager set the GUC, the
  insert itself was rejected — the failure was upstream of the count assertion.

### The solution

Wrap the read in `NULLIF`: `NULLIF(current_setting('app.current_tenant', true), '')::uuid`.
Empty string → NULL → `tenant_id = NULL` → no rows; a real tenant value is a valid
uuid and matches. The `TenantAwareTransactionManager` sets the GUC via
`set_config('app.current_tenant', :tid, true)` (bindable, transaction-local) so
inserts pass `WITH CHECK` and reads see only the tenant's rows.

### Lesson

`current_setting(custom_guc, true)` is not guaranteed to be NULL when "unset" — a
referenced custom GUC defaults to `''`. Always `NULLIF(..., '')` before casting a GUC
to a non-text type in an RLS policy. And remember an RLS `USING` clause silently
becomes the `WITH CHECK` for writes, so a missing tenant blocks inserts, not just
reads. Only a real-Postgres integration test surfaces this — no mock would.

---

## Challenge 7 — ArchUnit silently skipped Java 25 bytecode

**Phase:** Implementation (P0, Task 15)

### The problem

The tenant-scoping ArchUnit rule failed on the *current* codebase — but not with a
real violation. The error was ArchUnit's `failOnEmptyShould` safeguard: after
filtering, **zero `@Entity` classes matched**, even though `DemoRecord` clearly
qualifies. ArchUnit 1.3.0 (bundled ASM) could not parse Java 25 class files
(bytecode major version 69) and silently imported nothing, so every rule evaluated
against an empty set.

### The solution

Upgrade to `archunit-junit5:1.4.1`, which understands Java 25 bytecode. The rule
then imported the real classes and passed. Verified it actually *bites* by
temporarily adding a `LeakyRecord extends BaseEntity` (not tenant-scoped) — the build
went red flagging exactly that class — then deleting it.

### Lesson

A green — or here, misleadingly red-for-the-wrong-reason — architecture test proves
nothing if the analyzer skipped your classes. On a new JDK, bytecode-parsing tools
(ArchUnit, ASM, ByteBuddy, coverage agents) are the first to lag; pin versions that
declare support for the JDK in use. And always confirm an architecture rule fails on
a known-bad input, so an empty/again-empty import can never masquerade as "passing."

---

## Challenge 8 — Derived repository queries silently return zero rows under RLS

**Phase:** Implementation (P0-auth, Task 3)

### The problem

`UserRepositoryTest` saved a user under tenant A, then called the derived finder
`users.findByEmail("owner@acme.test")` — and got back `Optional.empty()`, even though
the row was there. The confusing part: in the *same* test, `users.findAll()` returned
the row (size 1) and `saved.getTenantId()` was correct. So the data existed, the
tenant column was right, and one query saw it while the other didn't.

### Why it's hard

Nothing in the query is wrong. SQL logging showed `findByEmail` generating exactly
`where tenant_id = ? and email = ?` with both parameters bound correctly (tenant A,
the right email). It *should* match. The divergence only shows up when you log the
`set_config('app.current_tenant', ...)` calls: the GUC is set before `save` and before
`findAll`, but **not** before `findByEmail`.

The cause is a Spring Data + RLS interaction. `save`/`findAll`/`findById` are concrete
methods on `SimpleJpaRepository`, annotated `@Transactional`, so they open a
transaction → `TenantAwareTransactionManager.doBegin` runs → the GUC is set. **Derived
query methods** (`findByEmail`) are *not* wrapped in a transaction by Spring Data by
default, so `doBegin` never runs, the GUC stays `''`, the RLS policy resolves
`NULLIF('', '')::uuid → NULL`, and `tenant_id = NULL` matches nothing. It fails *safe*
(empty, never cross-tenant), which is exactly why it's easy to miss — no error, no leak,
just a silently empty result.

Production never hits this: `findByEmail`/`findById` are always called from
`@Transactional` service methods (login/signup) that have already set the tenant context
before the transaction (see #9), so the GUC is set by the outer transaction. Only an
isolated repository test that calls the finder with no surrounding transaction exposes it.

### The solution

Annotate the derived finder with `@Transactional(readOnly = true)`
(`UserRepository.findByEmail`, `AuditLogRepository.countByAction`). It joins the caller's
transaction when there is one (`PROPAGATION_REQUIRED`) and starts its own — through the
`@Primary` `TenantAwareTransactionManager`, which sets the GUC — when called standalone.
This is a deliberate, minimal deviation from the plan's verbatim repository interfaces.

### Lesson

RLS makes a missing tenant GUC look like "no matching rows," not an error — so a
derived repository finder that runs outside a transaction silently returns empty.
Spring Data only auto-wraps the CRUD methods it declares, not the query methods it
derives. On any RLS-backed table, either guarantee every read runs inside a
tenant-bound transaction, or annotate the finder `@Transactional` so the transaction
(and thus the GUC) always exists. And always confirm an isolation test bites: here the
"pass" would have been a false empty if we hadn't cross-checked `findAll` against
`findByEmail`.

---

## Challenge 9 — Provisioning a tenant-scoped row in the transaction that creates its tenant

**Phase:** Implementation (P0-auth, Task 7 & 10)

### The problem

Signup must be atomic: create the tenant AND insert its first OWNER user in one
transaction, so a crash can't leave a tenant with no way in. But the owner is a
tenant-scoped entity — its `tenant_id` is filled by Hibernate `@TenantId` from the
current tenant, and its insert must pass Postgres RLS `WITH CHECK` (the GUC
`app.current_tenant` must equal the row's `tenant_id`). At the moment the transaction
begins, the tenant does not exist yet, so neither the tenant context nor the GUC is set.

The plan's first design was a `TenantBinder` that, mid-transaction, set the tenant
context and re-issued `set_config('app.current_tenant', ...)` after the tenant row was
created. It failed: the owner insert was rejected with *"new row violates row-level
security policy for table app_user"* — `@TenantId` had written the wrong tenant.

### Why it's hard

Hibernate resolves a session's tenant identifier **once, when the session opens**, via
`CurrentTenantIdentifierResolver`, and never re-reads it (confirmed in the Hibernate
docs: the current tenant is "specified when opening a session"). In a Spring
`@Transactional` method the `EntityManager`/session is opened at transaction begin —
before the tenant exists — so it freezes as the NIL `NO_TENANT`. `TenantBinder` updated
the `TenantContext` ThreadLocal and the GUC, but the already-open session kept its frozen
tenant, so `@TenantId` still wrote `NO_TENANT` while the GUC was the real tenant →
`WITH CHECK` mismatch. Setting the context *after* the session opened is simply too late.

The symptom is easy to misread: `save()` only calls `persist()` and doesn't flush, so the
INSERT (and the RLS failure) is deferred to commit/flush — until then everything looks
fine and the in-memory `@TenantId` field is just `null`.

### The solution

Turn it around: set the tenant context **before** the transaction opens, so the session
resolves the correct tenant at open. That requires the tenant id to be known up front, so
`Tenant` uses an **application-assigned UUIDv7** id (generated in its constructor via
`UuidV7`) instead of a Hibernate-generated one. Because a pre-set id makes Spring Data's
`save()` take the merge/UPDATE path (and `em.persist` reject it as "detached"), `Tenant`
implements `Persistable<UUID>` with a transient `isNew` flag (cleared on
`@PostPersist`/`@PostLoad`) so `save()` issues a straight INSERT. Signup then:

1. constructs the `Tenant` (id assigned now), 2. sets `TenantContext` to that id,
3. runs tenant + owner inserts in one `TransactionTemplate` transaction — whose `doBegin`
sets the GUC from the context and whose session opens already bound to the tenant.

`@TenantId` fills the real tenant, RLS `WITH CHECK` passes, and both rows commit together.
`TenantBinder` was deleted.

### Lesson

You cannot re-tenant an open Hibernate session; the tenant is fixed at session-open. When
a tenant-scoped write must happen in the same transaction that creates the tenant, make
the tenant id knowable *before* the transaction (application-assigned id) and set the
context first — don't try to rebind mid-flight. And an application-assigned id needs
`Persistable.isNew()`, or Spring Data/Hibernate will treat the entity as detached and
UPDATE (or reject) instead of INSERT. Deferred flush also hides RLS violations until
commit — force a flush when you want the check to bite in a test.

---

## Challenge 10 — Spring Boot 4 ships Jackson 3, under a new package

**Phase:** Implementation (P0-auth, Task 14)

### The problem

A controller test that imported `com.fasterxml.jackson.databind.ObjectMapper` /
`JsonNode` (to pull a field out of a JSON response) failed to compile: *"package
com.fasterxml.jackson.databind does not exist."* Confusing, because the app clearly
serializes and deserializes JSON fine at runtime (every endpoint returns JSON; `AuditLog`
maps a `Map` to `jsonb`). So Jackson is obviously present — just not where the import
expected.

### The solution

Spring Boot 4 upgrades to **Jackson 3**, which moved its entire base package from
`com.fasterxml.jackson` to **`tools.jackson`** (the dependency is
`tools.jackson.core:jackson-databind:3.x`). `com.fasterxml.jackson.*` imports no longer
resolve. Two ways forward: update imports to `tools.jackson.databind.*` (and note some
`JsonNode` accessors were renamed in 3.x, e.g. `asText()`), or — as we did — avoid the
mapper in tests and extract fields with jayway `com.jayway.jsonpath.JsonPath.read(body,
"$.field")`, which is already on the test classpath (it backs MockMvc's `jsonPath()`).

### Lesson

Same lesson as the Flyway auto-config split (#4), different library: on a new major
Spring Boot, a dependency being "on the classpath and working at runtime" says nothing
about which package/coordinates its API now lives under. When an import "does not exist"
but the feature plainly works, suspect a group/package rename before anything else —
`./gradlew dependencies` shows the real coordinates (`tools.jackson…`, not
`com.fasterxml…`). For test JSON assertions, JsonPath sidesteps the mapper API entirely.

---

<!-- Append new challenges below. Template:

## Challenge N — <title>

**Phase:** Design | Implementation

### The problem
### The solution
### Lesson
-->
