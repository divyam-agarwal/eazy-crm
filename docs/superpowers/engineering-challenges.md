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

## Challenge 11 — An audit row must outlive the transaction it audits

**Phase:** Implementation (P0-auth follow-up)

### The problem

Login records a `LOGIN_FAILED` audit event and then throws a generic 401. Both happened
inside the same transaction — so the throw rolled the transaction back, and the
`LOGIN_FAILED` row vanished with it. The audit log silently recorded *nothing* for failed
logins: exactly the events you most want for detecting brute-force or credential-stuffing.
`LOGIN_SUCCESS` was fine (it commits with the successful login); only the failure path lost
its audit, because writing evidence and then aborting are fundamentally in tension when
they share a transaction.

### The solution

Record the failure audit in its **own** transaction: a second `AuditService` method
annotated `@Transactional(propagation = REQUIRES_NEW)`. Spring suspends the outer login
transaction, runs the insert in a fresh transaction (whose `doBegin` re-sets the tenant GUC
from the still-set `TenantContext`, so RLS passes), commits it, then resumes — and the
subsequent `throw` rolls back only the outer transaction. The `LOGIN_FAILED` row is already
durably committed. Success-path audits (`SIGNUP`, `LOGIN_SUCCESS`) stay on the default
`REQUIRES` propagation, so they remain atomic with the operation they describe.

### Lesson

Audit/telemetry writes on a failure path must not share the transaction that fails, or they
roll back with it — "log then throw" inside one transaction logs nothing. Use `REQUIRES_NEW`
for records that must persist independently of the outcome, and keep success-path audits on
`REQUIRES` so they stay atomic. Under RLS, the new transaction still needs the tenant context
set, since it re-establishes the GUC at its own `doBegin`.

---

## Challenge 12 — A primitive `boolean` in a request record silently turns "field omitted" into 400, not a default

**Phase:** Implementation

### The problem

`ContactRequest` is a record deserialized from JSON by Jackson, with an `isPrimary` field
typed as primitive `boolean`. Jackson (via the parameter-names/record module) constructs
records by calling the canonical constructor with whatever it parsed — and for a JSON body
that simply omits `isPrimary` (e.g. `{"name":"Ravi"}`), it tries to pass `null` into a
`boolean` parameter. That fails fast with `HttpMessageNotReadableException`, which Spring
maps to a **400**, before the request ever reaches the controller or service. A test meant
to exercise "unknown customer → 404" instead observed a 400 from JSON binding, masking the
behavior actually under test — the same bug would bite any real client that omits an
optional boolean field expecting it to default.

### The solution

Box the field: `Boolean isPrimary` instead of `boolean isPrimary` in `ContactRequest`. A
missing/null JSON field then binds to `null` cleanly (no primitive-unboxing failure at
deserialization time), and `ContactService` converts it to a primitive with a null-safe,
explicit default: `Boolean.TRUE.equals(req.isPrimary())` — `null` or `false` both become
`false`, `true` stays `true`. The entity's constructor keeps its primitive `boolean primary`
field; the boxing/defaulting lives entirely at the DTO→entity boundary in the service.

### Lesson

Never use a primitive type for an optional field in a Jackson-deserialized request DTO
(record or class) — a primitive has no way to represent "absent," so Jackson's binding
failure (400) preempts your intended default/validation logic. Box optional request fields
and apply the default explicitly at the point you convert to a domain type.

---

## Challenge 13 — Validating a GSTIN checksum (Luhn-mod-36)

**Phase:** Implementation (P1a, Task 2/7)

### The problem

A GSTIN's 15th character is a check digit computed over the first 14 in base-36 (the
GSTN's own algorithm), but a naive validator — "15 characters, right shape per
character class" — accepts a **transposed or mistyped** character as long as the
overall pattern still looks like a GSTIN. That's not a cosmetic bug: `customer.gstin`
is the field the app later **splits** to derive `state_code` (first two characters)
for the intra-state vs inter-state GST calculation, so a silently-wrong GSTIN
corrupts a downstream calculation, not just a display field. And the mistake is
exactly the kind a human keying in a paper form makes — right shape, wrong digit.

### The solution

The `Gstin` value type computes the checksum itself with the GSTN algorithm: iterate
the first 14 characters right-to-left with an alternating factor of 2 then 1, fold
each product with `d / 36 + d % 36` (base-36 digit sum), sum the folds, and the check
character is `(36 − sum % 36) % 36` mapped back through the GSTN's base-36 alphabet.
`Gstin.parse` rejects anything whose computed check character doesn't match the 15th
character — an invalid GSTIN never reaches the DB — and `state_code` is derived from
the now-trusted first two characters. Verified against a known-valid fixture
(`27AAPFU0939F1ZV`) and the same GSTIN with its checksum character swapped
(`…ZZ` in place of `…ZV`), which correctly fails.

### Lesson

Encode a domain check-digit algorithm as a **parse-don't-validate** value type once,
so every caller gets a `Gstin` that is already known-correct rather than a raw string
that might not be — and reuse it everywhere the identifier appears (P1a customer
entry now, the planned P1c bulk import later), instead of re-validating ad hoc at
each entry point. Always pin the fixture pair (one valid, one checksum-broken) in the
test — a shape-only regex would pass both and never catch the regression.

---

## Challenge 14 — The override-rate / discount-percent XOR, and `BigDecimal` equality

**Phase:** Implementation (P1a, Task 12/13)

### The problem

A `PriceListItem` must carry **exactly one** of an absolute override rate or a
discount percent — "both set" and "neither set" are both meaningless states, not
just unusual ones — so the invariant needs enforcing, not just documenting. Separately,
`Product.gstRate` must be one of India's fixed GST slabs (0, 0.25, 3, 5, 12, 18, 28),
and checking membership in that allowed set ran into `BigDecimal`'s scale-sensitive
`equals`: `new BigDecimal("18").equals(new BigDecimal("18.0"))` is **false**, because
`equals` compares scale as well as value. A rate parsed as `"18.0"` (trailing zero
from a form or an import file) silently fails to match `"18"` in an allowed-set check
written with `.equals()` or a `Set.contains()` backed by it — the guard looks correct
and passes code review, but rejects valid input it was written to accept.

### The solution

Enforce the XOR at two independent layers: a Postgres `CHECK
(num_nonnulls(override_rate, discount_pct) = 1)` on the table, and an app-level
`ValidationException` (422) in `PriceListItemService` so the common case gets a
friendly field-level error instead of a raw constraint-violation 500/409. For rate
comparison, use `compareTo(...) == 0` (or a set membership check that normalizes
scale first via `stripTrailingZeros()`/explicit `compareTo` loop) — never `equals`
or an `equals`-backed `Set.contains()` — for any `BigDecimal` value-equality check.

### Lesson

An invariant worth a DB `CHECK` is also worth an app-level 422: the constraint is the
backstop that can never be bypassed, the app-level check is the one that gives users
a usable error instead of a database exception. And treat `BigDecimal.equals` as
scale-sensitive by default — reach for `compareTo` (or explicit normalization)
anywhere you're testing "is this the same *number*," since two textually different
but numerically equal decimals are a routine occurrence wherever rates cross a
form/import boundary.

---

## Challenge 15 — Defence in depth for uniqueness: friendly 409 vs the race the DB alone would allow

**Phase:** Implementation (P1a, Task 7b + throughout)

### The problem

Several P1a entities have a uniqueness rule that matters to the business (e.g. a
customer's GSTIN, a SKU) but isn't the primary key. Checking "does this already
exist?" in the service layer before insert gives a clean, field-attributed 409 for
the overwhelmingly common case — but by itself it's a **check-then-act race**: two
concurrent requests can both pass the pre-check before either commits, and both
insert, leaving a duplicate the app-level check was supposed to prevent. The
update path has the same gap under any concurrent-edit timing.

### The solution

Two layers, not one: a DB-level unique constraint is the fact that can never be
violated regardless of timing, and a global `@ExceptionHandler(
DataIntegrityViolationException.class)` in `ApiExceptionHandler` (added in Task 7b,
ahead of the original plan) catches the constraint violation on the rare race/update
case and still returns a 409 rather than letting it surface as a raw 500. The
app-level pre-check stays as the fast, friendly path; the DB constraint plus handler
is the backstop that makes the guarantee actually hold under concurrency, at the cost
of a less specific error message on the rare race.

### Lesson

A service-layer "does it already exist" check is a UX nicety, not a correctness
guarantee — only a DB constraint is atomic with the insert. Pair the two: check
first for a good error message, constrain always for correctness, and translate the
constraint violation centrally (one exception handler) rather than wrapping every
write site in its own try/catch.

---

## Challenge 16 — Gapless document numbering: why not a DB sequence, and how rollback avoids burning a number

**Phase:** Implementation (P1b, Task 3)

### The problem

Quote numbers (`QT/25-26/0001`) must be gapless per tenant per financial year:
distributors notice a missing number and assume a lost document. Three naive
approaches all fail:

- A Postgres `SEQUENCE` per doc type is the obvious tool, but it doesn't reset
  cleanly per financial year (Apr 1) or per tenant without one sequence object
  per tenant/FY/doc-type combination — an unbounded, hard-to-provision set of
  DB objects — and a rolled-back transaction still permanently burns the
  sequence value it fetched (sequences are non-transactional by design), which
  is exactly the gap we're trying to avoid.
- Reading the counter with a plain `SELECT` then `UPDATE` (optimistic, no lock)
  lets two concurrent sends read the same `next_val`, both increment from it,
  and both format the same quote number — a duplicate, worse than a gap.
- `@Version`-based optimistic locking avoids the duplicate but turns the second
  concurrent sender into a retry-or-fail path, which is unnecessary complexity
  for a row that's contended for microseconds.

### The solution

One row per `(tenant_id, doc_type, fy)` in `document_counter`, read via
`@Lock(LockModeType.PESSIMISTIC_WRITE)` → `SELECT … FOR UPDATE`. The lock is
acquired and released by the **caller's** transaction (`nextQuoteNo` is
`@Transactional` with default `REQUIRED` propagation, so it joins rather than
opens its own tx). That single property gives both correctness properties for
free:

- **Gapless under concurrency:** a second sender blocks on the row lock until
  the first commits or rolls back, so two sends never read the same
  `next_val` — no retries needed, just a short wait.
- **Rollback burns nothing:** if the caller's send transaction rolls back
  (e.g. downstream PDF generation fails), the increment to `next_val` rolls
  back with it and the lock releases — the *same* number is handed to the next
  successful send. Tested directly: `rolledBackSendConsumesNoNumber` opens a
  `TransactionTemplate`, calls `nextQuoteNo`, forces `setRollbackOnly()`, then
  asserts the very next call reuses suffix `0001`.

The FY label itself (`25-26` for any date Apr-2025–Mar-2026) is computed, not
stored redundantly anywhere else — `financialYear(LocalDate)` is a pure static
function so both the counter lookup key and any display logic derive from the
same rule.

One residual race remains at the very first send for a brand-new
`(tenant_id, doc_type, fy)` combination: two concurrent "first-ever" sends both
find no counter row and both attempt to insert one, the loser hits the unique
constraint and gets a transient 409 via the existing `DataIntegrityViolationException`
handler (challenge #15), then a client retry finds the row already present and
proceeds normally — no gap, no duplicate, just a one-time 409 on the coldest path.

### Lesson

Gapless + concurrent-safe is a row-locking problem, not a sequence-generator
problem, whenever "no gaps" has to survive a rollback — sequences are
deliberately non-transactional (that's what makes them fast), which is the
opposite of what a gapless invariant needs. Let the counter mutation ride
inside the same transaction as the business action that "spends" the number,
and pessimistic-lock the read; the transaction boundary does the rollback-safety
work for free.

---

## Challenge 17 — Money on the wire: `BigDecimal` as a JSON string (Jackson 3 / Boot 4)

**Phase:** Implementation (P1b, Task 1)

### The problem

Challenge #2 specifies that money must be serialized as a JSON **string**, never a
JSON number — a JSON number is re-parsed by JS as an IEEE-754 `double`, which
re-introduces the exact rounding error `BigDecimal` exists to prevent. P1a shipped
first and put `BigDecimal` fields (`Product.gstRate/baseRate`,
`PriceListItem.overrideRate/discountPct`) on the wire as plain JSON numbers,
because the string-wire-format work was explicitly deferred to P1b (see HANDOFF
§4). P1b's quotation responses carry many more money fields (`unitRate`, `taxable`,
`cgst`/`sgst`/`igst`, `lineTotal`, header totals) and cannot repeat that mistake.

### Why it's hard

Spring Boot 4 moved Jackson from 2.x to **Jackson 3**, and Jackson 3 relocated its
entire base package from `com.fasterxml.jackson.*` to **`tools.jackson.*`** (already
seen once, for `ObjectMapper`/`JsonNode`, in challenge #10). The serializer
customization API moved with it: `tools.jackson.databind.ValueSerializer<T>` (not
`com.fasterxml.jackson.databind.JsonSerializer<T>`), registered via a
`tools.jackson.databind.module.SimpleModule`, using
`tools.jackson.databind.SerializationContext` (not `SerializerProvider`) in the
`serialize` method signature. None of the Jackson-2-era serializer tutorials or
cached knowledge apply verbatim. And because the fix has to be global (a single
`ObjectMapper` serves the whole app), it isn't scoped to P1b's new fields — it
**retroactively changes P1a's already-shipped** `Product`/`PriceListItem` money
responses from number to string. That's only safe here because no frontend exists
yet to have coded against the old (wrong) number format; a later module doing the
same fix after a frontend ships would need a coordinated wire-contract change.

### The solution

A `BigDecimalStringModule` (`platform.money`) registers a
`ValueSerializer<BigDecimal>` that writes `toPlainString()` — never scientific
notation, which a raw `BigDecimal.toString()` can produce for very small/large
scales — and exposes itself as a `tools.jackson.databind.JacksonModule` `@Bean`,
which Spring Boot's Jackson auto-configuration discovers and registers on the
shared `ObjectMapper` automatically (no manual `ObjectMapper` wiring needed).
Deserialization needs no matching custom deserializer: Jackson already coerces a
JSON string into a `BigDecimal` target field without help, so the module only
needs to handle the write side.

### Lesson

Serialize money as a string **once, globally, at the framework seam** (a module
bean), not per-DTO or per-field — that is the only way a fix reliably covers every
current and future money field, including ones written before the fix existed.
And when a framework relocates a library's package wholesale (Jackson 2→3 here,
same as Flyway's auto-config split in #4), don't assume a plan referencing the old
package names is wrong — search the resolved jars for the new coordinates and
adjust the API calls to match, the underlying capability is still there.

---

## Challenge 18 — The mutable-DRAFT / frozen-SENT quotation-version invariant

**Phase:** Implementation (P1b, Task 8/9/10)

### The problem

The design spec calls a `quotation_version` an "immutable snapshot," but the same
spec also requires traders to revise a quotation 3–4 times while drafting before
they ever send it — two requirements that are in direct tension if read literally.
"Immutable from the moment it's created" would churn a fresh version row on every
keystroke-level edit during drafting and fight the actual workflow (build, tweak,
build, tweak); "always mutable" would let someone edit a version after it's been
sent, silently rewriting what the customer was actually shown — losing the one
thing an "immutable snapshot" exists to guarantee.

### Why it's hard

Immutability isn't a property of the *row* — it's a property of the row's
**lifecycle state**. A `QuotationVersion` needs to behave completely differently
depending on whether its parent `Quotation` has been sent yet, and that behavior
has to be enforced everywhere a write could happen (`patchHeader`, `replaceItems`,
and later `revise`), not just in one obvious place — miss one path and the
invariant silently breaks under a client that calls a different endpoint.

### The solution

Immutability is a function of `Quotation.status`, not of the version row itself: a
version is mutable only while its parent quotation is `DRAFT`. Every write path
(`patchHeader`/`replaceItems`) funnels through a single `requireDraft` guard that
keys off `Quotation.status` — one choke point, not a check duplicated per method.
`send` freezes the current version by flipping **both** `Quotation.status` →
`SENT` and calling `version.markSent(...)` in **one transaction**, so the two
never disagree about whether the quote has been sent. Revising a `SENT` quotation
doesn't mutate anything — it spawns a brand-new `DRAFT` version `vN+1` that
**copies the frozen items verbatim** (no recompute, no re-resolution of prices),
so the sent snapshot (`vN`) is never touched, and the new draft starts as an exact
copy the trader can then edit.

### Lesson

When a spec's "immutable" and "editable" requirements collide, the fix is usually
to make immutability a **function of lifecycle state** rather than a fixed
property of the object — mutable in one state, frozen in another, with a single
state transition (`send`) as the one moment that flips it. Funnel every write path
through one guard keyed off that state (not a check copy-pasted into each
handler), and prefer **copy-on-revise** over "make the frozen row mutable again"
whenever you need to build on top of a frozen artifact without disturbing it.

---

## Challenge 19 — `send()` on a revised draft silently reassigned `quote_no`

**Phase:** Implementation (P1b, final review)

### The problem

`send(id)` unconditionally called `q.assignQuoteNo(documentNumbers.nextQuoteNo(...))`
before `q.markSent()`. That's correct the *first* time a draft is sent, but
`revise()` deliberately sets `Quotation.status` back to `DRAFT` while **keeping**
the existing `quote_no` (challenge #18) so the trader can edit before re-sending.
Sending that revised draft again re-entered the same unconditional assignment
path: it pulled a brand-new gapless number from the counter (challenge #16) and
overwrote the original one the customer had already seen on the first version —
silently breaking the "quote_no assigned once, retained across revisions"
invariant the spec requires, and burning a counter value for nothing on every
resend.

### Why it's hard

Nothing about `send()`'s code looked wrong in isolation — "assign a number, mark
sent" reads as the obvious happy path, and every test written against a
fresh draft (`create → send`) passed. The bug only appears on the *second* send
of a given quotation's lifecycle (`create → send → revise → send`), a path that's
easy to leave uncovered because `revise` and `send` were built and tested as
separate tasks against fresh drafts, not chained into the full round-trip a real
trader performs.

### The solution

Guard the assignment on absence, not on being in `send()` at all: `if
(q.getQuoteNo() == null) { q.assignQuoteNo(...); }` before `q.markSent()`. A
first-ever send has `quote_no == null` and gets one assigned; a resend after
`revise()` already has a non-null `quote_no` (revise never clears it) and skips
straight to `markSent()`, leaving the original number untouched.

### Lesson

"Assigned once, retained forever" invariants need to be tested across their full
state-machine cycle (`draft → sent → draft → sent`, not just `draft → sent`) —
a single-transition test suite can be 100% green while silently missing the
one transition (re-entering a state) where the bug actually lives. When a field
is meant to be write-once, guard the write with "is it already set?", not with
"which endpoint am I in?".

---

## Challenge 20 — `order` is a reserved SQL word

**Phase:** Implementation (order/accept slice, Task 1)

### The problem

The natural table name for the new `Order` aggregate is `order` — but `ORDER` is a
reserved keyword in the SQL standard (it's half of `ORDER BY`). An unquoted `CREATE
TABLE order (...)` fails to parse, and even if every DDL statement were fixed with
double-quoting (`"order"`), that quoting would have to be repeated correctly
everywhere the identifier appears again: the Flyway migration, the RLS policy SQL,
any hand-written native query, and psql sessions during debugging. Miss one quote
and you get a confusing syntax error instead of an obviously-named bug.

### The solution

Name the **physical table** `sales_order` and keep the **Java class** `Order`:
`@Entity @Table(name = "sales_order")`. The domain vocabulary (`Order`,
`OrderRepository`, `OrderResponse`, `OrderStatus`) stays exactly what the design
spec and code review expect — only the SQL identifier changes, and it changes once,
at the JPA mapping boundary. Every migration (`V18__sales_order.sql`,
`V19__rls_sales_order.sql`), RLS policy, and native query downstream reads
`sales_order` and needs no quoting anywhere.

### Lesson

When a domain noun collides with a SQL/HQL reserved word, don't fight the collision
with quoting discipline that has to be repeated correctly forever — rename the
*physical* identifier once at the ORM mapping (`@Table(name = ...)`) and let the
*domain* name (class, repository, DTOs, docs) stay what the business actually calls
it. The two names are allowed to diverge; only the mapping needs to know about the
divergence.

---

## Challenge 21 — Natural (state-based) idempotency for accept

**Phase:** Implementation (order/accept slice, Task 3/4)

### The problem

Challenge #3 sketched idempotency for "accept a quotation" as a client-generated
idempotency key stored in its own table: the client mints a key per attempt, the
server records `key → order id`, and a retry with the same key returns the existing
order instead of creating a twin. That's the right shape for an action with no
other identity to hang the check on. But it's also more machinery than this
particular action needs — an extra table, an extra column on every write, and a
new failure mode (what if the client reuses a key for a *different* logical
action?) — and building it here would be paying for generality nothing in this
slice requires.

### Why it's hard

The trap is applying the general pattern by default just because it's already
designed and logged. The right question isn't "what's the standard idempotency
pattern?" but "does this specific action already have a natural, unique identity to
key off?" — and for accept, it does: **a quotation can only ever produce one
order.** That's a domain invariant, not an incidental fact, so it's available as
the idempotency key for free.

### The solution

Make "exactly one order per quotation" a structural guarantee instead of a
procedural one, the same way tenant isolation is (CLAUDE.md: "structural, not
procedural"). Two layers:

1. **`UNIQUE(tenant_id, quotation_id)`** on `sales_order` (see challenge #20) — the
   database physically cannot hold two orders for the same quotation, regardless of
   timing.
2. **The quotation's own `@Version`** (optimistic lock, inherited from
   `BaseEntity`) plus a **status check at the top of `accept()`**:
   `QuotationService.accept` first checks `q.getStatus() == ACCEPTED` and, if so,
   returns the *existing* order (`orders.findByQuotationId(...)`) without touching
   anything — the fast, common-case idempotent path. Only a quotation still in
   `SENT` proceeds to create a new `Order` and call `q.markAccepted()`.

A raced double-tap (two requests both read `SENT` before either commits) is caught
by the two backstops together: the loser's `q.markAccepted()` update fails the
optimistic-lock check (`@Version` mismatch) if the winner already committed, or —
if both somehow reach the insert — the `UNIQUE(tenant_id, quotation_id)` constraint
rejects the second `Order` row outright (translated to 409 by the existing
`DataIntegrityViolationException` handler, challenge #15). Either way exactly one
order survives.

### Lesson

Challenge #3's own lesson — "choose the weakest tool that's actually sufficient" —
applies one level up from where it was first used: before reaching for a generic
idempotency-key mechanism, check whether the action already has a domain identity
that makes duplication structurally impossible. Here the quotation id **is** the
idempotency key; a dedicated key table would have been solving a problem the
domain model already solves. Reserve the client-key pattern for actions that
genuinely lack a natural one-to-one identity to key off.

---

## Challenge 22 — `QuotationAcceptedEvent` as a side-effect seam, not a return channel

**Phase:** Implementation (order/accept slice, Task 3/5)

### The problem

The parent design spec describes accept as "the order handler subscribes" to a
quotation-accepted event — implying the event is what *produces* the order:
publish first, an `@EventListener` creates the `Order` in response. Read literally,
that means `QuotationService.accept()` would publish an event and have nothing
concrete to put in the HTTP response until some listener, running after
`publishEvent()` returns, has done the actual creation — which only works if the
listener runs synchronously in the same call stack and the publishing method then
reaches back into whatever the listener produced.

### Why it's hard

Using an event as a de facto return channel inverts the natural data flow and
quietly re-couples the "decoupled" publisher to a specific subscriber's side
effect: the accept endpoint's response (`OrderResponse` with the new order's id,
number, totals) is exactly the thing the event was supposed to not need to know
about. It also fights Spring's own default (`ApplicationEventPublisher` listeners
run synchronously, same-transaction — challenge #3) into doing something it isn't
shaped for: producing a value the caller depends on, rather than reacting to
something that already happened.

### The solution

Deliberately deviate from the spec's wording. `QuotationService.accept()` creates
and saves the `Order` **inline**, in the same command that validates the
quotation's state and flips it to `ACCEPTED` — so the HTTP response has the real
order immediately, with no dependency on listener execution. It **then** publishes
`QuotationAcceptedEvent(quotationId, orderId, quotationVersionId, grandTotal,
orderNo, actorUserId)` carrying everything a subscriber could need, purely for
decoupled *side effects*: `OrderAcceptedAuditListener` writes the `QUOTATION_ACCEPTED`
audit row today, and the same seam is where activity-log and WhatsApp-notify
listeners attach later without `QuotationService` ever knowing they exist. Because
publish happens after the order is saved but still inside `accept()`'s
`@Transactional` boundary, Spring's default synchronous/same-transaction listener
behavior (challenge #3) still gives every subscriber atomicity with the order —
the deviation only changes *who creates the order*, not the transactional
guarantee the spec's event was protecting.

### Lesson

An event is the right tool for "notify other things this happened" (open/closed:
new listeners attach without touching the publisher) but the wrong tool for
"produce the value my caller needs right now" — that coupling should stay a direct
call. When a spec's wording implies the event *is* the mechanism that produces the
primary result, treat that as shorthand for "this transition has a side-effect
seam," not as a literal instruction to route the return value through pub/sub —
keep the command's own return path direct, and let the event carry only what
downstream, decoupled subscribers need.

---

## Challenge 23 — Enforcing "one active enquiry per phone" without an app-level pre-check race

**Phase:** Implementation (enquiry slice, Task 3)

### The problem

The business rule is "a phone number can have at most one *active* (non-terminal)
enquiry at a time, but a new enquiry is allowed once the prior one is CONVERTED or
LOST." A plain unique constraint on `(tenant_id, normalized_phone)` is too strong —
it would permanently block re-enquiry from a returning customer. The Challenge 15
pattern (app-level pre-check + always-on DB unique constraint as backstop) doesn't
fit either: the constraint side of that pattern needs to *stop* applying once the
row transitions to a terminal stage, and an ordinary constraint has no notion of
row state.

### The solution

A **partial unique index** — `CREATE UNIQUE INDEX ... ON enquiry (tenant_id,
normalized_phone) WHERE stage NOT IN ('CONVERTED', 'LOST')` — encodes the invariant
entirely in the index predicate. Postgres only enforces uniqueness among rows that
satisfy the `WHERE` clause, so a row silently drops out of the constraint's scope
the moment `stage` is updated to a terminal value (no separate cleanup, no
soft-delete flag). This still closes the concurrent-insert race the way Challenge
15 wants — two simultaneous "create enquiry for this phone" requests can't both
land while the phone has an active row — but the constraint's membership is itself
state-dependent, verified directly in `EnquiryRepositoryTest` by asserting a second
`active(phone)` insert throws `DataIntegrityViolationException` while the first is
still active, then succeeds once the first is moved to LOST (or CONVERTED) and
flushed.

### Lesson

When a uniqueness rule is conditioned on entity state ("unique while active," not
"unique forever"), reach for a partial index (`UNIQUE ... WHERE <predicate>`)
before reaching for a plain unique constraint plus app-level filtering — it keeps
the invariant atomic with the state transition itself (updating `stage` is what
frees the slot, in the same row, no second write) instead of relying on a
service-layer check that's only as strong as its timing.

---

## Challenge 24 — Combining optional list filters without silently dropping one

**Phase:** Implementation (enquiry slice, Task 5)

### The problem

`EnquiryController`'s list endpoint takes three independent optional filters
(`stage`, `assignedTo`, `source`) that must AND-compose in any combination — zero,
one, two, or all three supplied at once. `OrderService.list` (an earlier slice)
handles its two optional filters with an `if (status != null) ... else if
(customerId != null) ... else findAll(...)` chain. That reads fine for either
filter alone, but it's structurally wrong the moment *both* are supplied: the
`if` branch wins and the `else if` branch — and its filter — is never reached, so
a request for `status=X&customerId=Y` silently returns all of X's orders,
ignoring `customerId`, with no error to signal the filter was dropped. The bug
only shows up when a caller combines filters, which is easy to omit from tests
that check each filter in isolation.

### The solution

Build the query as a single JPA `Specification<Enquiry>` that accumulates one
`Predicate` per non-null filter into a list, then combines them with a single
`cb.and(predicates.toArray(new Predicate[0]))`:

```java
return (root, query, cb) -> {
    List<Predicate> ps = new ArrayList<>();
    if (stage != null)      ps.add(cb.equal(root.get("stage"), stage));
    if (assignedTo != null) ps.add(cb.equal(root.get("assignedTo"), assignedTo));
    if (source != null)     ps.add(cb.equal(root.get("source"), source));
    return cb.and(ps.toArray(new Predicate[0])); // empty -> always-true conjunction
};
```

Every present filter contributes its own predicate to the same conjunction, so
there is no branch where adding a second filter displaces the first — the
"which filters are combined" logic no longer needs to enumerate every subset.
`cb.and()` on an empty array yields an always-true conjunction, so the
zero-filter case (list everything, tenant-scoped by RLS) falls out for free.
`EnquiryListTest.twoFiltersCombineCorrectly` is a regression guard: it creates
three enquiries so that any single-filter match would return more than one row,
and asserts `source=PHONE&assignedTo=A` returns exactly the one row that
satisfies both.

### Lesson

An `if / else if` chain over independent optional filters is a trap: it silently
caps the query at *one* active filter no matter how many the caller supplies,
and the bug is invisible in tests that only ever set one filter at a time. When
filters must AND-compose in any combination, build a list of predicates (one
per non-null filter, unconditionally combinable) and reduce it with a single
`cb.and(...)` — the combination logic falls out structurally instead of needing
a branch per subset. Test at least the two-filter case explicitly; it's the
smallest input that catches this class of bug.

---

## Challenge 25 — Conversion rides the quote-create transaction, so a failed quote un-converts the lead

**Phase:** Implementation (enquiry→quotation conversion slice, Task 1)

### The problem

Raising a quotation "from a lead" has to do two writes at once: flip the source
`Enquiry` to `CONVERTED` and stamp its id onto the new `Quotation`. The obvious
shape — the one the deferred "convert endpoint" wording invited — is a dedicated
`POST /enquiries/{id}/convert` that marks the enquiry converted and *then* kicks
off quotation creation, or worse, two separate calls from the client. Either way
the flip and the quote build land in **different transactions**. That is subtly
wrong: quote creation can still fail its own validation *after* the enquiry is
already flipped (a bad line item, a price-resolution miss), leaving a `CONVERTED`
(terminal, un-editable, un-loseable) lead with no quotation behind it — and,
because `CONVERTED` also drops out of the one-active-per-phone partial index
(challenge #23), the lead is simultaneously "done" and gone from the active
pipeline, recoverable only by re-enquiring. The naive split silently trades a
transient validation error for a permanent bad state.

### The solution

Don't add an endpoint or a second transaction at all. `QuotationCreateRequest`
already carried a nullable `enquiryId`; conversion becomes a few lines *inside*
`QuotationService.create()`'s existing `@Transactional` method — load the enquiry
(`EnquiryRepository.findById`, tenant-scoped by RLS → 404 if not visible), call
`enquiry.markConverted()` (the entity's own terminal guard throws
`ValidationException` → 422 if it's already CONVERTED/LOST), then build the
quotation as before. The enquiry is a managed entity, so the flip flushes on
commit — and because the flip and the whole quote build share **one** transaction,
any downstream failure (`buildItems` rejecting `qty=0`, etc.) rolls the flip back
with everything else: the lead stays exactly as active as it was. This is proven
directly by `QuotationConversionTest.failedQuoteBuildRollsBackTheConversion`,
which fires a create with a valid active `enquiryId` and an invalid item, expects
422, then re-reads the enquiry and asserts it is still `NEW`. Two more guarantees
fall out for free from the same placement: one enquiry converts once (a second
create against the now-terminal enquiry hits `markConverted`'s guard → 422), and
concurrent double-convert is caught by the enquiry's inherited `@Version`
optimistic lock — exactly one create commits, so data integrity holds. (The
race-*loser* currently surfaces as HTTP 500, not a clean 409:
`ObjectOptimisticLockingFailureException` extends `ConcurrencyFailureException`,
not `DataIntegrityViolationException`, so it missed the challenge #15 →409
backstop. That was a standing, codebase-wide gap — the order-accept race relied on
the same `@Version` and would surface identically — **now closed** by a global
`OptimisticLockingFailureException`→409 handler, see challenge #26.)

### Lesson

When one user action must perform two coupled writes and the second can still
fail validation, resist giving each its own endpoint/transaction — put both in a
single transactional command so a late failure can't leave the first write
committed on its own. Here the fix was also the *smaller* change: the create path
already accepted the foreign key, so "wiring the conversion" meant loading and
flipping one entity inside the transaction that was already there, not building a
new convert surface. A terminal state that's expensive to reverse (frees a
uniqueness slot, blocks further edits) raises the stakes: never let it commit
ahead of the work that justifies it.

---

## Challenge 26 — Optimistic-lock is a *sibling* of, not covered by, the data-integrity 409 backstop

**Phase:** Implementation (sales hardening slice)

### The problem

Challenge #15 established a global `@ExceptionHandler(DataIntegrityViolationException)`
→ 409 as the backstop for "a DB constraint rejected this write" (a unique-index
violation that slipped past an app-level pre-check, e.g. a concurrent create
race). It's natural to assume that handler covers *all* "the database said no
because of concurrency" failures. It does not. A lost-update race on a `@Version`
row — two transactions read version N, both write, the second's `UPDATE …
WHERE version = N` matches zero rows — is **not** a constraint violation. Spring
Data translates it to `ObjectOptimisticLockingFailureException`, which lives in a
*different* branch of the `DataAccessException` tree: it extends
`OptimisticLockingFailureException` → `ConcurrencyFailureException` →
`TransientDataAccessException`, whereas `DataIntegrityViolationException` extends
`NonTransientDataAccessException`. Disjoint subtrees. So the existing 409 handler
never matches it, and the race-loser falls through to a raw **500** — even though
data integrity is perfectly intact (exactly one writer won). This surfaced as a
real gap in *two* places at once: quotation `accept` (challenge #21) and
convert-at-create (challenge #25) both rely on `@Version` for their idempotency /
double-submit safety, and both would 500 the loser.

### The solution

Two complementary changes, one procedural and one structural:

1. **A second, sibling 409 handler.** Add
   `@ExceptionHandler(OptimisticLockingFailureException.class)` (Spring's *base*
   `org.springframework.dao` type, so it also catches the concrete `orm`
   `ObjectOptimisticLockingFailureException`) returning 409 with a generic
   "concurrent update; please retry" message — mirroring the data-integrity
   handler but on the transient/concurrency subtree it doesn't reach. Because the
   two exception hierarchies are disjoint, Spring's most-specific-match dispatch
   never has to choose between them; they simply cover different failures.
2. **A structural uniqueness backstop where an invariant was only procedural.**
   "One quotation per enquiry" was enforced only by the entity terminal guard
   (`markConverted()` → `CONVERTED`, then `requireActive()` blocks a second) plus
   `@Version`. Added `UNIQUE(tenant_id, enquiry_id)` on `quotation` (Postgres NULLs
   are distinct, so enquiry-less quotes are unaffected) so the invariant is
   structural, matching challenge #23's philosophy. A raced or guard-bypassed
   second insert now hits the constraint and routes through the *challenge #15*
   handler → 409. So the two handlers together mean every "you lost a
   write race" path — whether it surfaces as a stale-version `UPDATE` or a
   unique-constraint `INSERT` — returns 409, never 500.

Both are proven deterministically without threads: a single-threaded stale-write
test (load v0, bump the DB to v1 in a second transaction, save the stale copy →
`OptimisticLockingFailureException`) and a repo-level duplicate-insert test
(same `(tenant, enquiry_id)` → `DataIntegrityViolationException`).

### Lesson

"Return 409 not 500 on a write conflict" is not one handler — it's coverage of
*two* disjoint `DataAccessException` subtrees, and adding a `@Version` field
silently creates the second one. When you introduce optimistic locking, add (or
confirm) the `OptimisticLockingFailureException`→409 mapping in the same change,
or the very races the lock exists to make safe will 500 their losers. And prefer
belt-and-braces where an invariant matters: a `@Version` lock closes the race
window, but a matching DB unique constraint makes the invariant hold even if the
lock or an app-level guard is ever bypassed — and, conveniently, funnels that
failure into the 409 you already map.

---

## Challenge 27 — A terminal order state versus an idempotent accept

**Phase:** Design

### The problem

Adding `CANCELLED` to `Order` collides with two decisions already baked into the
accept path: accept is **idempotent** — re-accepting an `ACCEPTED` quotation
returns the order that already exists — and `UNIQUE(tenant_id, quotation_id)` on
`sales_order` makes one-order-per-quotation **structural** (challenge #21).

Cancellation therefore has no obvious undo. The naive move — flip the quotation
back to `SENT` so it can be accepted again — cannot work: the cancelled row still
occupies the unique slot, so the second accept's `INSERT` hits the constraint and
surfaces as a 409 no caller can act on. But leaving accept untouched is quietly
worse. It keeps returning **200 with a dead order**, so a client that reasonably
reads accept as "give me the live order for this quote" gets a plausible-looking
response describing an order that no longer exists commercially. Nothing fails
loudly; the contract just stops being true.

### The solution

Keep cancellation order-local and make the dead end explicit. The quotation stays
`ACCEPTED`, the cancelled row stays put, the unique constraint is untouched — and
accept's idempotent branch gains one check: if the existing order is `CANCELLED`,
throw `ValidationException` → **422** with "the order for this quotation was
cancelled; raise a new quotation" instead of returning it.

Reopening a cancelled sale means raising a new quotation, which is also the
commercially honest answer: after a cancellation, price, stock and terms have all
had a chance to move, so silently reviving the old accepted version would be the
wrong default even if the schema allowed it.

### Lesson

Idempotency is a claim about a *result*, not about a status code. "Call it again,
get the same answer" holds only while the resource the call produced is still
valid — and introducing a terminal state downstream breaks that invisibly,
because the endpoint carries on returning 200. When you add a terminal state to
an aggregate, re-read every idempotent path that hands that aggregate back and
decide explicitly what each one now means.

And when a structural constraint removes the option of "just make another one",
that is the constraint doing its job. The fix is to say no clearly, not to relax
the constraint.

### The enquiry-linked dead end this doesn't cover

"Raise a new quotation" is only fully actionable when the cancelled order's
quotation had no enquiry behind it. When it did, `QuotationService.create()`
calls `enquiry.markConverted()`, which routes through `Enquiry.requireActive()`
and throws 422 on an already-`CONVERTED` enquiry — and
`V22__quotation_enquiry_unique.sql`'s `UNIQUE(tenant_id, enquiry_id)` on
`quotation` makes one-quote-per-enquiry structural. So after
`enquiry → quotation → order → cancel`, the operator *can* raise a replacement
quotation, but *cannot* link it back to the original enquiry — the only route is
a fresh quotation with `enquiryId: null`, which silently severs lead
traceability. The remedy — re-opening the enquiry on cancel, or relaxing the
one-quote-per-enquiry rule — is a deliberate open design decision carried
forward, not an oversight of this slice.

---

## Challenge 28 — `PDDocument.setDocumentId()` silently doing nothing

**Phase:** Implementation

### The problem

`PdfEngine.render()` needs two renders of the same XHTML + timestamp to produce
byte-identical PDFs — the design spec's "shown, emailed and WhatsApped output
are the same document" is meant to be an assertable property, not an aspiration.
openhtmltopdf writes the PDF; a post-process step reopens it with PDFBox to stamp
a fixed `PDDocumentInformation` (producer, creator, creation/mod date) derived
from the caller's timestamp, then re-saves.

That alone wasn't enough: `sameInputRendersToIdenticalBytes` kept failing with
byte-identical output everywhere *except* the trailer's `/ID` entry, which
differed on every run. Setting `PDDocument.setDocumentId(timestamp.toEpochMilli())`
before `save()` — the documented way to pin it — had **no effect at all**. Two
back-to-back renders still produced two different random-looking hex `/ID`
pairs.

The naive next move — reading the PDFBox 2.0.24 Javadoc harder — didn't explain
it, because the behavior isn't in the Javadoc. It's in `COSWriter.write(PDDocument,
SignatureInterface)`'s bytecode: before computing anything, it reads the
trailer's *existing* `/ID` entry, and if that's already a 2-element `COSArray` —
which it is, because openhtmltopdf's own first-pass writer already stamped a
random `/ID` into the raw bytes we're re-opening — the method takes an early
branch that **keeps the inherited ID unchanged** and skips the whole
MD5(`documentId` + Info-dictionary-values) computation. `setDocumentId()` only
feeds a code path that never runs when an ID is already present and the save is
non-incremental.

### The solution

Disassembled `COSWriter.class` with `javap -c` to find the actual branch
condition (there is no `PDDocument` API to query it). Confirmed with a temporary
diagnostic in the test itself — printing the first differing byte offset per the
task's own instruction not to weaken the assertion — that the sole divergence was
the `/ID` array, byte offset ~1156 in a 1258-byte PDF.

Fix: explicitly remove the inherited entry before saving —
`doc.getDocument().getTrailer().removeItem(COSName.ID)` — so PDFBox has nothing
to inherit and falls onto its MD5-recompute branch, which then hashes our pinned
`setDocumentId()` value together with the Info dictionary (itself already a pure
function of `timestamp`). With no upstream randomness left in either input to
that hash, the digest — and therefore the whole file — is now identical across
runs.

### Lesson

A "set the field, then save" API can be a no-op if the writer's decision to use
that field is conditional on state that already exists on the object you loaded
— and that condition usually isn't documented, because it's an internal
optimization (avoid rehashing an ID that's presumably already fine), not a
contract. When a setter provably has no effect, don't reach for a different
setter — read the writer's actual control flow (bytecode is fine if source
isn't handy) to find the branch you're not reaching, then remove whatever's
satisfying the branch you don't want, rather than layering more state-setting
on top of a path that's being skipped entirely.

---

## Challenge 29 — Serving a tenant-scoped document to a request that has no tenant

**Phase:** Design + Implementation (quotation PDF/share slice, Tasks 6/8)

### The problem

`GET /public/q/{token}` exists so a customer can open a quotation PDF from a
WhatsApp link with **no login at all**. That collides head-on with every layer
challenge #1 built: no JWT means `JwtAuthenticationFilter` never populates
`TenantContext`, which means `TenantIdentifierResolver` hands Hibernate the nil
`NO_TENANT` UUID, which means `TenantAwareTransactionManager` has nothing to
write into the `app.current_tenant` GUC — so every RLS-scoped query on this
request, by design, returns zero rows. A `share_token` column bolted onto
`quotation_version` would therefore be **unlookupable by construction**: you
cannot `SELECT … WHERE share_token = ?` on a table RLS has already reduced to
"no rows visible," so the very column meant to let the request in is the first
thing RLS hides from it.

### Why it's hard

The problem isn't "add a public endpoint" — Spring Security's `permitAll` does
that trivially. The problem is that *nothing tenant-scoped is reachable* from a
request with no tenant, and the one thing this endpoint needs is precisely a
tenant-scoped row. Any fix that tries to keep the lookup table tenant-scoped is
solving a contradiction: the row can't be both protected by the identity you
don't have yet and findable without it.

### The solution

Move the resolution step **outside** the isolation boundary rather than
weakening it. `share_link` is a global, RLS-exempt table (allowlisted in
`TenantScopingArchTest.GLOBAL_TABLES`, same treatment as `refresh_token`) whose
only job is `token → (tenant_id, quotation_version_id)`. It carries no document
content — no buyer name, no amounts, nothing GST-related — so exposing it to
tenant-less reads exposes nothing worth protecting. The request flow is then:

```
GET /public/q/{token}                          no JWT, no tenant
  → ShareLinkService.resolve(token)             global table, no @TenantId, no RLS
      → 404 if absent or malformed
  → TenantContext.runAs(tenantId, () -> …)       tenant installed HERE
      → QuotationPdfService.renderByVersionId(…) opens its @Transactional now
      → @TenantId + RLS enforce as normal from this point on
```

The ordering — `runAs` wrapping the call, not the other way around — is not a
style choice, it's load-bearing, and it's the same constraint challenge #9
already found the hard way: Hibernate resolves a session's tenant identifier
**once, at session-open**, and `TenantAwareTransactionManager` only reads
`TenantContext` in `doBegin`. If the rendering call's `@Transactional` method
opened before `runAs` installed the tenant, the session would freeze on
`NO_TENANT` and every subsequent read would silently return nothing — not a
crash, just an empty PDF path, all the way to a 404 with no clue why. Putting
`resolve()` before `runAs`, and `runAs` before the call that opens the
transaction, is what makes the tenant available at the one moment Hibernate
will ever look for it.

There's a second, sharper trap buried in the same ordering, caught only
because it was flagged for this log during Task 8: **this endpoint's
correctness depends on `spring.jpa.open-in-view: false`.** With OSIV on, Spring
opens the `EntityManager` in a servlet filter *before the controller method
runs at all* — before `shareLinks.resolve(token)` executes, let alone before
`runAs` installs the tenant. Hibernate would pin whatever tenant resolves at
that point (`NO_TENANT`, since no context exists yet) for the *entire request's
session*, and `runAs` setting the real tenant afterward would change nothing —
the session already froze on the wrong identifier. The failure mode is total
silence: no exception, no log line points at OSIV, just every render coming
back empty. Nothing in the build catches this; it would only surface as "the
share feature doesn't work" in a manual check, because no test spins up the
app with OSIV deliberately re-enabled.

The exception this carves out of "tenant comes from the JWT only" is
deliberately narrow: exactly one table is readable pre-auth, and it holds
nothing but identifiers. Every byte of actual document content — quotation,
version, items, customer, tenant profile — still goes through `@TenantId` +
RLS untouched, with the tenant supplied by `runAs` before any of those reads
can execute.

### Lesson

When a pre-auth endpoint must ultimately reach tenant-scoped data, don't try to
make the tenant-scoped table reachable without a tenant — that's backwards.
Put a single, deliberately minimal resolution table *outside* the isolation
boundary, holding only the identifiers needed to establish tenancy, then
install that tenancy (`runAs`) **before** anything opens a session or
transaction that will read it — session-open timing (challenge #9) applies at
the controller-entry boundary just as much as at the transaction boundary, and
`open-in-view: false` is what keeps those two boundaries at the same place.
Keeping the exception table free of content is what keeps the exception small:
there's nothing in it worth leaking even to an attacker who reads it directly.

---

## Challenge 30 — An isolation test that could never fail

**Phase:** Implementation (quotation PDF/share slice, Task 8 review)

### The problem

The original cross-tenant test on `GET /public/q/{token}` — the app's only
unauthenticated route, and therefore the one place isolation bugs are hardest
to notice from the outside — asserted that tenant B's buyer name never appears
in a PDF rendered from a token that resolves to tenant A's own quotation
version. It passed. It would have passed even if `@TenantId` and the RLS
policy on `quotation`/`customer` had both been deleted, because the token in
the test was **only ever capable of resolving to tenant A's data in the first
place** — B's row was never on the other end of the lookup, so its absence
from the output proves nothing about isolation. It's a negative assertion with
no path by which the positive case could have occurred.

### Why it's hard

This is not a bug in the feature — the feature was correct throughout. It's a
trap in how the *test* was constructed: "assert the forbidden thing is absent"
reads as a genuine security check, and every other cross-tenant test in this
codebase (challenge #1's "log in as A, request B's resource → 404") has real
teeth precisely because the request *could* have reached B's row if isolation
had failed. Here, nothing in the ordinary flow ever constructs a token that
points across tenants — `ShareLinkService.share()` always stores the caller's
own `TenantContext.tenantId()` — so an isolation test built from that ordinary
flow can only ever exercise the case where isolation was never at risk. The
gap is easy to miss because the test *looks* identical in shape to the ones
that do work.

### The solution

Construct the adversarial case directly rather than trusting the app to
produce it. `aForgedShareLinkPointingAnotherTenantAtThisTenantsVersionIs404`
saves a `ShareLink` row straight through `ShareLinkRepository` — bypassing
`ShareLinkService` entirely — with `tenantId` set to tenant B while
`quotationVersionId` belongs to a version owned by tenant A: a row no
production code path can ever create, but exactly the shape `@TenantId`/RLS
must reject if the tenant established by `runAs` doesn't actually gate the
read. The endpoint is asserted to return 404. The re-reviewer additionally
traced the app's `easycrm_app` role (no `BYPASSRLS`, not table owner) to
confirm the 404 is genuinely RLS enforcement and not some other check-first
gate that would return 404 regardless. The original test was kept, renamed to
`happyPathRendersOwnQuotationWithASecondTenantsDataPresent`, with a doc comment
stating plainly what it does and does not prove — it's still useful as a
happy-path/no-leakage-into-formatting check, just not as an isolation
guarantee.

### Lesson

A test that asserts the absence of something is only meaningful if there is a
concrete path by which that something could have arrived — otherwise a
regression that deletes the very protection under test leaves the assertion
green. This bites hardest on isolation checks, because the *natural* flow
through the application almost never manufactures the adversarial input by
itself (the whole point of the isolation layer is that it doesn't let that
input arise) — so proving the layer works means deliberately forging the state
the layer is supposed to prevent, not exercising the happy path and hoping the
forbidden case would have shown up if it could. When reviewing a "must not
leak" test, ask first whether the leaked data could ever, even in principle,
have reached this code path — if the answer is no by construction, the test
needs a forged counterpart before it proves anything.

---

## Challenge 31 — "10% sampling, errors always sampled" is two different sampling stages

**Phase:** Design

### The problem

The observability section of the AWS target architecture carried a one-line
claim: *"Head-based sampling at 10%, with errors always sampled."* It reads as
a single coherent policy, and it is the sentence almost everyone writes. It is
also self-contradictory, and the same document's own trace-continuity design
(F8, per-task ADOT sidecars) makes the second half structurally impossible to
implement.

Head-based sampling runs **in-process, in the SDK, at `startSpan`** — the
`Sampler` returns `DROP` / `RECORD_ONLY` / `RECORD_AND_SAMPLE` before the
handler has executed a single line. At that instant nothing knows whether the
request will call Razorpay, time out, or throw. So a literal 10% head sampler
drops 90% of errors: precisely the traces anyone would open during an
incident. "Always sample errors" needs the *outcome*, which only exists after
the span ends.

### Why it's hard

The naive fix — "so use tail sampling" — collides with a decision made
elsewhere in the same design. Tail sampling buffers every span of a trace for
a decision window and then evaluates policies against the completed trace,
which requires **all spans of one trace to reach the same collector process**.
The design runs ADOT as a **per-task sidecar** (`essential: false`,
`dependsOn: START`, cheap, no extra service to operate). A sidecar sees only
its own task's spans. It is not that tail sampling is unconfigured there — a
sidecar *structurally cannot* tail-sample, no matter what its config says.
Moving to a central collector service means trace-ID-aware load balancing, a
stateful buffer sized to peak trace volume, and a new ECS service on the
critical path of observability.

The second, quieter trap: once errors are retained at 100% and successes at
10%, the surviving traces are **no longer a representative sample**. Counting
them to derive an error rate inflates it by roughly the inverse of the sample
rate — a system failing 1% of the time reports something near 50%. The bias is
invisible because each individual trace is perfectly real.

### The solution

Split the sentence into the two stages it actually describes, and say which
one exists today.

- **Now (head, in the SDK):** `parentbased_traceidratio`. `ParentBased` means
  only a *root* span consults the sampler; children obey the `sampled` bit in
  the inbound `traceparent`, so a trace is kept or dropped **as a unit**
  instead of 10% of the spans in every trace. `traceidratio` hashes the trace
  ID against a threshold rather than flipping a coin, so every service in the
  trace — any language, no coordination — computes the same verdict.
  Per-route rates come from X-Ray's centralized rules (reservoir + rate),
  fetched at runtime, so changing them needs no redeploy; the flat 10% is only
  the catch-all rule.
- **Later (tail, in a central collector):** the `tail_sampling` processor with
  OR-ed policies — `status_code = ERROR`, latency over threshold, and a 10%
  probabilistic baseline. This is what actually delivers "errors always
  sampled," and it arrives with the central collector service, not before.
- **In the interim:** errors falling outside the 10% are covered by structured
  logs and the error-rate metric, not by traces. That gap is the real,
  stated price of staying on sidecars — not a detail to leave implied.
- **Independently of both:** RED metrics come from the `spanmetrics`
  connector, which sits **before** the sampler and therefore sees 100% of
  spans. Traces answer "what happened in this one request"; metrics answer
  "how often." Sampling is the seam where those two get conflated.

### Lesson

A sampling policy is not one setting — it is a decision made at a specific
point in the pipeline, and the point determines what information is available
to decide with. Head sampling is cheap because it decides before doing the
work, which is exactly why it cannot condition on the result of the work; tail
sampling can condition on the outcome only because it pays to buffer and
therefore needs the whole trace in one process. Any requirement phrased as
"sample X%, but always keep the interesting ones" is really two
requirements at two stages, and writing it as one line hides a deployment
topology decision (sidecar vs central collector) inside what looks like a
config value. Related: the moment sampling stops being uniform, sampled data
stops being countable — derive rates from a pre-sampling source, or derive
them wrong.

---

## Challenge 32 — TB3: money crosses a second wire, and it must not share a mapper with the first

**Phase:** Design & Implementation (`platform-primitives`, Task 4)

### The problem

Challenge #17 fixed money on the **HTTP** wire: a `BigDecimalStringModule`
registered as a Spring bean, picked up by Boot's Jackson auto-configuration onto
the one application `ObjectMapper`. That fix is invisible to a second wire that
does not exist yet: the outbox `payload` JSONB column, published to SNS/SQS. TB3
is what happens when a future outbox writer builds its own `ObjectMapper` ad hoc
(`new ObjectMapper()` inside the writer, the obvious thing to type) — it does not
carry `BigDecimalStringModule`, so money reaches SNS as an IEEE-754 double after
the entire rest of the stack was built specifically to avoid that.

The obvious fix — inject the application mapper into the outbox writer — is
worse than the bug it fixes, and only fails months later. HTTP responses and
event payloads are versioned by different owners on different cadences. The day
someone sets `spring.jackson.default-property-inclusion=non_null` to slim an API
response, every subsequent outbox event silently drops its null fields too,
because it is the same mapper instance. A consumer reading that event later has
no way to tell "field absent because the producer predates this field" from
"field present and explicitly null" — exactly the ambiguity an additive-only
contract exists to prevent. The bug is not in the code at the time it's
introduced; it's in the coupling that makes an unrelated, reasonable-looking API
change silently rewrite a wire contract that has to stay readable for years.

### Why it's hard

Two further things made this harder than "write a second `ObjectMapper`":

1. **The fix has to be a separate, independently-owned object, not a shared one
   behind a flag.** `rebuild()`-ing the application mapper with different
   settings still carries the coupling one step later — it would still change
   the instant Boot's defaults change underneath it. The only fix that actually
   removes the dependency is a mapper built from `JsonMapper.builder()` with
   every relevant setting stated explicitly, so a change to Boot's Jackson
   configuration has no path to reach it at all.

2. **Jackson 3 relocated the specific settings this contract depends on.**
   `SerializationFeature.WRITE_DATES_AS_TIMESTAMPS` and
   `.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS` — the Jackson-2-era spellings for
   "timestamps as ISO-8601 strings, not epoch numbers" — no longer exist on
   `SerializationFeature` in Jackson 3.1.4; `javap -p
   tools/jackson/databind/SerializationFeature.class` lists 17 members and
   neither is among them. Both moved to a new, java.time-specific enum,
   `tools.jackson.databind.cfg.DateTimeFeature` (itself a `DatatypeFeature`,
   configured via the builder's `disable(DatatypeFeature...)` overload rather
   than `disable(SerializationFeature...)`). The bytecode for
   `DateTimeFeature`'s static initializer also shows `WRITE_DATES_AS_TIMESTAMPS`
   defaults to `false` and `WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS` defaults to
   `true` in Jackson 3 — so the ISO-8601 behaviour this contract needs is
   already Jackson 3's default, which made it tempting to drop the calls
   entirely. They stayed in, explicit, for the same reason the whole class
   exists: a *future* Jackson or Boot default is exactly what this mapper must
   not silently inherit.

### The solution

`EventJson` (`platform.money`) exposes one `public static JsonMapper mapper()`
built once from `JsonMapper.builder()` with every setting stated: the same
`BigDecimalStringModule` as the HTTP wire (challenge #17, so the one property
that must never diverge doesn't), `DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS`
and `.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS` disabled explicitly,
`changeDefaultPropertyInclusion(...Include.ALWAYS)` so nulls are always written,
and `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES` disabled so a newer
producer's additive field doesn't break an older consumer. Jackson 3 mappers are
immutable and thread-safe post-`build()`, so a single `static final` instance is
correct — no synchronization, no per-call construction.

The design's own open question — "does `JsonMapper.builder()`'s defaults
actually differ from what Boot configures?" — was answered with a test instead
of prose (`EventJsonDivergenceTest`, root project, the only place a
Boot-configured application `ObjectMapper` exists to compare against). All three
assertions passed unchanged: on this codebase, today, the two mappers already
agree that money is a string and timestamps are ISO-8601, and only diverge (by
design) on whether `EventJson` keeps nulls regardless of what
`default-property-inclusion` becomes. Written as an executable test rather than
a comment, a future change to Boot's Jackson configuration that quietly closes
that gap turns the test red instead of leaving the LLD's claim stale.

### Lesson

When two consumers of the same value have different owners and different
change cadences, giving them a shared configurable object — even one that
starts out behaving identically — reintroduces the coupling you built the
second object to remove; the divergence only shows up later, as a silent
side effect of an unrelated, well-intentioned change. And a "the brief's builder
spelling might be wrong" warning is worth taking literally against a major
version bump: Jackson 3 didn't just rename `com.fasterxml.jackson.*` to
`tools.jackson.*` (challenge #10) — it also moved specific enum constants
(`SerializationFeature` → `DateTimeFeature`) to a differently-typed sibling enum,
which no amount of guessing the new package name would have caught. `javap` on
the resolved jar found both the real location and its default, in less time
than a web search would have taken.

---

## Challenge 33 — `noClasses().should(customCondition)` inverts the condition's own events

**Phase:** Implementation (Task 5, ArchUnit rules R1/R2)

### The problem

R1's brief (verbatim) wrote a hand-rolled `ArchCondition<JavaClass>` that calls
`events.add(SimpleConditionEvent.violated(item, ...))` for a class that
constructs a JSON mapper, and wired it up as
`noClasses().that().resideOutsideOfPackage("com.easycrm.platform.money..").should(constructAJsonMapper())`.
It compiled cleanly, and — this is the trap — it *passed* on the first run,
exactly as the brief predicted "both rules will pass on their very first run."
The brief's own required step (Step 6: deliberately add a `JsonMapper.builder()`
call to `CustomerService` and confirm the rule fails) is what caught it: the
rule stayed green with the violation sitting right there in the source. A rule
that cannot fail is indistinguishable from one that imports zero classes — the
exact ArchUnit 1.3.0/Java 25 failure mode this whole task exists to guard
against — except this time the vacuousness was in the condition's polarity, not
the class import.

### Why it's hard

The built-in DSL conditions (`.should().dependOnClassesThat().resideInAnyPackage(...)`,
used by R2) work correctly under both `classes()` and `noClasses()` without the
author ever thinking about polarity, which trains the reasonable expectation
that a hand-rolled `ArchCondition` will too. It doesn't. `archunit-1.4.1-sources.jar`
**is** present in the Gradle cache, and reading it names the actual mechanism:
`ArchRuleDefinition.noClasses()` builds its rule through a private
`negateCondition()` helper — `condition -> never(condition).as(condition.getDescription())`
— where `never(...)` constructs a package-private `NeverCondition` (`com.tngtech.archunit.lang.conditions`).
`NeverCondition.check(item, events)` delegates to the wrapped condition with an
`InvertingConditionEvents` in place of the real `ConditionEvents`; that class's
`add(ConditionEvent event)` calls `delegate.add(event.invert())` — `invert()` on
`SimpleConditionEvent` just flips the `conditionSatisfied` boolean and keeps the
same message. So every event a hand-rolled condition emits under `noClasses()`
is flipped before it reaches the real rule evaluation. Emitting `violated(item, ...)`
for the offending class, the natural-reading choice ("this thing happened, and
it's bad — call it a violation"), gets inverted into a *satisfied* event, and
the rule reports no failure — for every class, regardless of what it does. This
was confirmed empirically by evaluating four variants side by side:
`classes().should(condition)` with `violated()` correctly named both offending
classes; `noClasses().should(condition)` with `violated()` reported zero
violations against the identical import; and `noClasses().should(condition)`
with `satisfied()` correctly named only the one class outside `platform.money`.
The fix is counter-intuitive by name — the "bad" case is reported via
`SimpleConditionEvent.satisfied(...)`, not `.violated(...)` — precisely because
`noClasses()` is going to invert it back.

### The solution

`PlatformPrimitivesArchTest.constructAJsonMapper()` emits
`SimpleConditionEvent.satisfied(item, call.getDescription())` for both the
method-call and constructor-call branches, with a comment at the call site and
a class-level Javadoc paragraph explaining the inversion so a future reader
doesn't "fix" it back to `violated()`. R2 was unaffected — both of its rules are
built entirely from the fluent DSL (`noClasses().should().dependOnClassesThat()...`),
which is why its Step 3 deliberate-violation check failed correctly on the
first attempt with the brief's code exactly as given.

### Lesson

A hand-rolled `ArchCondition` combined with `noClasses()` does not mean "no
class should satisfy this predicate" in the way `violated()`/`satisfied()`
naming suggests — `noClasses()` inverts whatever the condition emits, so the
condition must be written already accounting for that inversion, event by
event. This is invisible from reading the DSL call site and only shows up at
runtime, which is exactly why the brief's Step 6 ("prove R1 can fail") is not
optional ceremony: it is the only thing that would have caught this before
trusting the rule. The general lesson generalizes past ArchUnit — any
API that lets you plug a custom predicate/condition into a "positive" and a
"negated" entry point should be treated as two different contracts until
proven otherwise by making the negated one fail on a known-bad input, not
assumed identical by symmetry of the surrounding DSL. It also isn't confined to
hand-rolled conditions: when R2 was later reworked from an enumeration to a
closure (`onlyDependOnClassesThat(allowedPackages)`), the same experiment —
evaluating it under both `classes()` and `noClasses()` — showed `noClasses()`
inverts that built-in condition too (it fires on every *permitted* dependency
instead of every forbidden one), which is why that rule was written with
`classes()`, not `noClasses()`, even though it lives beside a sibling rule that
correctly uses `noClasses()` for an enumeration. Same DSL, same file, opposite
polarity requirement depending on which shape the condition takes.

---

## Challenge 34 — Structural validation on one entity's field does not extend to a "same-shaped" sibling field

**Phase:** Implementation (Task 7, seller GSTIN/state code at signup)

### The problem

`CustomerService.resolveGstinAndState` already ran a buyer's GSTIN through
`Gstin.parse` (checksum + state prefix) and, when no GSTIN was supplied,
`StateCode.requireValid` on the bare state code. Nobody had done the same for
the *seller* — `SignupRequest.stateCode` carried only `@Pattern("\\d{2}")`
(any two digits pass: `"39"`, `"88"`, `"00"`) and `gstin` was a bare `String`
that `AuthService.signup` never touched. This shipped and ran clean: no
exception, no log line, nothing in a test failure — because
`QuotationService.isInterState` only *compares* `tenant.getStateCode()`
against the customer's to pick CGST+SGST vs IGST; it never asks whether either
side is a real GST state code. An invalid seller state code doesn't throw, it
just silently picks the wrong tax split for every quotation that tenant issues
from day one, and an unvalidated seller GSTIN prints on every PDF letterhead.
There is no error to grep for and no test that was failing — the bug is a
correct-looking computation over a value nobody checked.

### Why it's hard

The buyer and seller GSTIN/state-code pairs look identical in shape (a
2-digit prefix, an optional 13-character checksum), which invites the
assumption that validating one validates the pattern for both. But they enter
the system through two unrelated DTOs (`CustomerRequest` vs `SignupRequest`)
built at different times by different tasks, and Bean Validation's
`@Pattern` on `stateCode` gives a false sense of coverage: it enforces *shape*
("two digits"), which is a strict subset of *validity* ("one of the ~40 real
GST state codes"), and nothing in the type system or the test suite flags that
gap — `@Pattern` compiles, runs, and passes for `"39"` exactly as it does for
`"27"`. The asymmetry is also cross-cutting rather than local: the defect
isn't in any single method, it's in the *absence* of a call that a reviewer
would only notice by tracing forward from `SignupRequest` to
`QuotationService.isInterState` and asking "what guarantees this input is
real" — a question that doesn't arise from reading `AuthService.signup` in
isolation, since the method looks complete on its own terms (build entity,
save, mint tokens).

### The solution

`AuthService.signup` now runs the identical validation shape as
`CustomerService`, but deliberately narrower: `SignupRequest.stateCode` is
`@NotBlank`, so — unlike the buyer path, which must derive a state code from
the GSTIN when the caller left `stateCode` blank — there is never a blank
`stateCode` to derive, so that branch was not ported. What *was* ported is the
part that actually matters: if a GSTIN is supplied, `Gstin.parse` validates
its checksum and state prefix, and the derived state must equal the declared
`stateCode` (`ValidationException("stateCode", "must match the GSTIN state
code")`) — the same "one of the two disagreeing inputs must be rejected, not
silently picked" rule `CustomerService` already enforces for a buyer.
`StateCode.requireValid(stateCode)` then runs unconditionally, so a seller
with no GSTIN still can't register with a two-digit non-code. This also
retired a call that Task 6 had made dead: once `Gstin.parse` validates the
state prefix itself, `CustomerService`'s follow-up
`StateCode.requireValid(derived)` on an already-`parse`-validated value can
never throw — it was deleted, and the comment above it corrected to describe
what `parse` actually guarantees (charset, checksum, *and* state prefix, not
just checksum).

### Lesson

Two fields that look structurally identical (same regex, same "2-digit state
code" description) are not the same guarantee unless the same validation
function runs on both — copy-pasting a DTO shape without copy-pasting its
service-layer validation reintroduces the exact bug the original validation
was written to prevent, just on a different entity. The tell is not a failing
test (there wasn't one) but a downstream consumer — here,
`QuotationService.isInterState` — that treats a field as trustworthy without
itself validating it; that combination (unvalidated input + a decision made
from it with no error path) is a signal to trace every producer of that field
back to its entry point, not just the one already known to be validated. It is
also a reminder that closing a validation gap can retire a validation call
elsewhere: strengthening `Gstin.parse` to check the state prefix made a
sibling `StateCode.requireValid(derived)` call unreachable, and unreachable
defensive code should be removed and its comment corrected, not left to imply
a weaker guarantee than what now actually holds.

---

## Challenge 35 — An auto-configuration that is also component-scanned: two bean names, one class

**Phase:** Implementation (Task 2, `MoneyAutoConfiguration`)

### The problem

Splitting the money Jackson module out of the monolith turned a component-scanned
`@Configuration` (`MoneyJacksonConfig`) into an `@AutoConfiguration` named in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
The reason for the change is MB1: a future `sales-svc` whose
`@SpringBootApplication` sits at `com.easycrm.sales` never scans
`com.easycrm.platform`, so the bean would simply not exist and the only symptom
would be money crossing the HTTP wire as a JSON number — no exception, no log
line, no failing test.

But the class still lives at `com.easycrm.platform.money.MoneyAutoConfiguration`,
and today's single application still scans from `com.easycrm`. So the class is
reachable by **two entirely different mechanisms at once**: the `.imports` file
and component scan. The expectation going in was a
`BeanDefinitionOverrideException` on refresh, or — worse — two
`BigDecimalStringModule` beans registered on the mapper. Neither happened. The
context refreshed cleanly with exactly one module bean.

The interesting part is not that it worked. It is that "it works" is compatible
with two completely different worlds, and the difference between them is the whole
point of the change:

- the `.imports` file was read, and the auto-configuration did its job; or
- the `.imports` file is being ignored (typo in the filename, wrong path, jar
  packaging that drops `META-INF/spring`), the class was picked up by component
  scan alone, and the bean exists **for exactly the reason the refactor was
  meant to stop relying on**.

A test that only asserts "one `BigDecimalStringModule` bean exists" passes
identically in both worlds. It would have gone green on a build where the
`.imports` file was a dead file, and the failure would surface years later, in a
different service, as money silently becoming a JSON number.

### Why it's hard

Nothing in the observable behaviour distinguishes the two worlds. The bean is the
same instance of the same class contributed by the same `@Bean` method on the same
configuration class, and `getBeansOfType(JacksonModule.class)` returns one entry
either way. There is no log line saying which entry point won, and Spring's
de-duplication is silent by design.

The mechanism only becomes visible one level down, in how Spring *names* the bean
definition. Spring uses **two different bean-name generators** for the two entry
points (verified against `spring-context-7.0.2`):

| Entry point | Generator | Name produced |
|---|---|---|
| `@ComponentScan` | `componentScanBeanNameGenerator`, an `AnnotationBeanNameGenerator` | decapitalised short name — `moneyAutoConfiguration` |
| `@Import` / `AutoConfiguration.imports` | a hardcoded `IMPORT_BEAN_NAME_GENERATOR`, a `FullyQualifiedAnnotationBeanNameGenerator` | the FQCN — `com.easycrm.platform.money.MoneyAutoConfiguration` |

The import-side generator is *hardcoded*, not configurable, precisely so that
imported configuration classes cannot collide by short name with scanned ones. And
the de-duplication that keeps the double reachability harmless is not a name
comparison at all: `ConfigurationClassParser` tracks already-processed
configuration classes by **class identity**, so the second arrival of the same
class is folded into the first rather than registered twice. That is structural,
not incidental — which is why no `@ComponentScan` exclusion was needed and adding
one would have been cargo cult.

It also means the naming asymmetry is the *only* externally visible trace of which
route the class actually took.

### The solution

`MoneyModuleWiringTest` asserts two separate things, and the second is the one
that does the real work:

1. `exactlyOneBigDecimalStringModuleBeanIsRegistered` — the count. Guards against
   a duplicate-definition trap on one side and MB1 on the other.
2. `theAutoConfigurationIsWhatRegisteredIt` — asserts the bean **definition** name
   `com.easycrm.platform.money.MoneyAutoConfiguration` is present. Because the
   FQCN name can only be produced by the import path, this passes if and only if
   the `.imports` file was actually read.

Delete the `.imports` line and test 1 still passes (component scan still finds the
class), while test 2 fails — which is exactly the discrimination that was missing.
The auto-configuration itself stays deliberately plain: `@AutoConfiguration`,
`@ConditionalOnClass(JacksonModule.class)`, one `@Bean`, no scan exclusion.

Boot's `JacksonAutoConfiguration` (artifact `org.springframework.boot:spring-boot-jackson`,
class `org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration`)
injects a `Collection<JacksonModule>` into its mapper-builder customizer, so
ordering never needed pinning either: the module is collected by type regardless of
which configuration class declared it.

### Lesson

When one class is reachable by two registration mechanisms, "the context started
and the bean exists" is not evidence that the mechanism you intended is the one
that ran — and the mechanism you intended is usually the entire reason for the
change. Find the observable that differs between the two paths and assert on *that*,
not on the outcome both paths produce. Here the observable was the bean-definition
name, because Spring deliberately generates it differently per entry point; in
other frameworks it will be something else, but the shape of the question is the
same: *what would still be true if the wiring I just added were completely inert?*
If the answer is "everything my test asserts," the test is measuring the old
mechanism.

The corollary is about defensive fixes. The predicted duplicate-registration
failure never materialised because `ConfigurationClassParser` de-duplicates by class
identity. Adding a `@ComponentScan` exclusion "to be safe" would have added a second
thing to keep in sync, hidden the fact that the platform is doing this correctly,
and — worst of all — would have made the double reachability *look* dangerous to the
next reader when it is structurally handled.

---

## Challenge 36 — After a module split, a package name no longer identifies whose bytecode you imported

**Phase:** Implementation (Task 5, R1's non-vacuity guard)

### The problem

`PlatformPrimitivesArchTest` (rule R1 — nobody outside `com.easycrm.platform.money`
constructs a JSON mapper) runs in the **root** project and imports classes with
`new ClassFileImporter().importPackages("com.easycrm")`. This codebase has already
been bitten once by an ArchUnit rule that imported zero classes and therefore passed
while checking nothing (ArchUnit 1.3.0 silently skipping Java 25 bytecode — see
challenge #30), so every rule here carries a companion non-vacuity test. R1's, as
first written, was the obvious one:

```java
assertThat(classes).as("imported classes").isNotEmpty();
```

That assertion was correct before this branch and is nearly worthless after it.
Extracting `platform-primitives` into its own Gradle module put ten classes that
are *also* under `com.easycrm..` into a **jar on the root project's test
classpath**. `importPackages("com.easycrm")` scans the classpath, not the project,
so it now sweeps up two independently-built artifacts that happen to share a
package prefix. If the root project's own bytecode stopped being imported — the
precise failure mode the guard exists for — `isNotEmpty()` would still pass on the
jar's ten classes alone, and R1 would go vacuously green while checking none of
the ~180 classes it is actually about.

So the module split silently converted a working vacuity guard into one that
cannot detect the vacuity it was written for, and nothing about the guard's source
changed to signal it.

### Why it's hard

The failure is invisible from the call site. `importPackages("com.easycrm")` reads
as "my application's classes", and for the entire life of the codebase up to this
branch that is exactly what it meant, because there was only one place
`com.easycrm..` bytecode could come from. The split broke the identity between
*package name* and *compilation unit* without breaking any line of code, and Java's
package system offers nothing to restore it: a package is not owned by a jar, and
two artifacts sharing a prefix are indistinguishable to a package-name filter.
ArchUnit's `ImportOption` vocabulary is about locations and test/main splits, not
"only classes this Gradle project compiled."

It is also the same defect class as the bug found minutes earlier in the same task
(challenge #33's inverted condition), one level up: a *check on the check* that
cannot fail. Both are green rules that assert nothing, and both would be found only
by asking "what would make this assertion false?" rather than "does this assertion
pass?" — which is why the second one survived the first sweep.

### The solution

Assert on a class that exists **only** in the project whose bytecode must be
present, and cannot come from any jar sharing the package prefix:

```java
assertThat(classes.contain("com.easycrm.EasyCrmApplication"))
    .as("root project's own bytecode (not just the platform-primitives jar also on "
      + "this classpath) was imported")
    .isTrue();
```

`EasyCrmApplication` is the root project's `@SpringBootApplication` class; the
primitives jar has no such class and structurally cannot (R2 forbids it any Spring
dependency at all). So its presence is a witness for "this project's own bytecode
was parsed," which is the property the rule actually depends on. The
`isNotEmpty()` assertion is kept above it — it still distinguishes "imported
nothing at all" from "imported something" — with a comment in place explaining why
it is insufficient on its own, so a later reader does not delete the stricter line
as redundant.

The sibling rule R2 needed no equivalent: it runs inside `platform-primitives`
itself, where `importPackages("com.easycrm")` sees only the module's own classes,
because the dependency edge is root → subproject and never the reverse.

### Lesson

Splitting a monolith into modules invalidates every piece of tooling that used a
package name as a proxy for "code I own" — classpath scanners, ArchUnit imports,
reflection-based registries, coverage filters, `@ComponentScan` bases. None of them
break loudly; they just start including a second artifact's classes, and any rule
whose failure mode is *emptiness* becomes unfalsifiable at the same moment. The
audit to run after a split is not "does everything still pass" (it will) but "which
assertions were relying on a package prefix identifying exactly one build output?"

The narrower rule for vacuity guards: `isNotEmpty()` is only a real guard while
there is exactly one possible source for the elements. As soon as there are two,
name a specific witness that can only originate from the source you care about.

---

## Challenge 37 — The test harness structurally cannot reproduce the isolation failure it is guarding against

**Phase:** Implementation

### The problem

Row-Level Security was `ENABLE`d on all fourteen tenant tables and `FORCE`d on none.
`ENABLE` does not bind a table's **owner**: PostgreSQL exempts the owner from its own
policies unless the table is additionally forced. So layer 3 of the four-layer
isolation was, in practice, resting on layer 3.5 — the fact that the application
happens to connect as the non-owner `easycrm_app` role. One deployment, one set of
credentials, and it holds. Issue any second process the owner role and tenant
isolation disappears with no error, no log line and no failing test.

The `ALTER TABLE ... FORCE` is trivial. The hard part is the guard that stops the
next table from shipping half-installed, and the guard ran straight into a wall:
**the failure cannot be reproduced in the test harness at all.** Testcontainers
creates the container's user (here `owner`) as a PostgreSQL **superuser**, and
superusers ignore `FORCE ROW LEVEL SECURITY` unconditionally — it is not a policy
decision, it is a privilege check that never runs. A behavioural test of the form
"connect as owner, expect zero cross-tenant rows" therefore fails **before** the fix
and fails **after** it, for a reason that has nothing to do with the fix. There is no
arrangement of `SET ROLE` that rescues it either: `FORCE` is evaluated against the
table's owner, and the tables are owned by the superuser that ran Flyway.

The deeper trap is that this is invisible unless you go looking. Write the obvious
behavioural test, watch it fail, and the natural conclusion is "the migration didn't
work" — when in fact the migration is correct and the harness is lying.

### The solution

Assert the **catalog**, not the behaviour, and then prove the assertion can fail.

`RlsCoverageIntegrationTest` asks `pg_class` for every base table in `public` carrying
a live `tenant_id` column and requires three facts of each: `relrowsecurity`,
`relforcerowsecurity`, and at least one row in `pg_policy`. The trigger is the
**column**, not the `@TenantId` annotation, which is what makes it a genuine layer-3
guard rather than a second reading of layer 2 — the exact gap being closed is a table
that has the annotation and lacks the SQL. Violations accumulate into one message that
names each offending table and the statement that fixes it, so a future failure reads
as an instruction instead of `expected true, was false`.

A catalog assertion buys correctness at the cost of falsifiability: a query that
silently matched nothing would pass forever. Two things keep it honest. A witness
assertion requires `product` — a table present since V9 — to appear in the result set,
distinguishing "everything passed" from "nothing was checked". And a second test
creates a throwaway `rls_guard_probe(tenant_id uuid)` with no RLS at all, runs the
guard's own query against it, and asserts all three checks trip. That one needs DDL,
which the app role cannot do (`USAGE` on the schema, no `CREATE`), so `IntegrationTest`
grew an `ownerConnection()` helper rather than leaking the container credentials to
callers.

The allowlist deliberately mirrors `TenantScopingArchTest.GLOBAL_TABLES` name for name.
`refresh_token` and `share_link` both carry a `tenant_id` and both are pre-auth tables
that resolve a tenant rather than being scoped by one; `tenant` needs no exemption at
all, because it has no such column and the query never sees it. A third assertion
fails if an allowlisted table stops existing, so a stale exemption surfaces instead of
quietly exempting nothing.

### Lesson

Before writing a security test, ask what privilege the **test harness** runs with —
convenience defaults in test infrastructure are chosen to make setup easy, and "easy"
usually means "privileged". Testcontainers' superuser, an in-memory database with no
role system, a test fixture wired to an admin API key: each of them silently exempts
the code under test from the exact mechanism being verified. The test does not fail
loudly; it becomes unfalsifiable, in whichever direction its assertion happens to point.

When the mechanism genuinely cannot be exercised, asserting on declared state is a
legitimate substitute — but only if you separately prove the assertion can go red.
State assertions fail open by nature: a query matching zero rows and a system that is
perfectly configured are indistinguishable in the passing case. Pair every one with a
deliberately broken input (here, a probe table) or a named witness that must appear.
Both were cheap; without them the guard would have been decoration.

---

## Challenge 38 — A `@ConfigurationProperties` record can't carry a derived, uncompilable field

**Phase:** Design

### The problem

`RateLimitPolicy` needs a compiled `PathPattern` alongside its four configured fields
(`name`, `path`, `capacity`, `refillPeriod`), and compiling that pattern once — in the
constructor — instead of per request matters, because this type sits in front of every
request the application serves. The obvious design is a fifth record component,
`PathPattern compiled`, populated by a non-canonical constructor that calls
`PathPatternParser.defaultInstance.parse(path)`.

That design is fine for a value type built directly in Java (`new RateLimitPolicy("test",
path, 10, Duration.ofMinutes(1))` happily resolves to the 4-arg constructor). It breaks
the moment the same record is also a `@ConfigurationProperties` binding target.
Spring Boot's relaxed binder always binds records through their **canonical**
constructor — the one matching all declared components, `compiled` included — because
that is the only constructor reflection can locate unambiguously. For a YAML list of
policies, there is no `compiled:` key in configuration and no `Converter<String,
PathPattern>` registered to produce one from a string that also isn't there. The bind
fails, and it fails at the exact boundary the type exists to feed: `RateLimitProperties.
policies`.

### The solution

Drop `compiled` as a record component entirely and resolve it lazily, keyed on `path`,
through a `static final ConcurrentHashMap<String, PathPattern>` and `computeIfAbsent`.
The record goes back to four components — `name`, `path`, `capacity`, `refillPeriod` —
so the canonical constructor is the same one hand-written test code already calls, and
the binder now has a real value for every component it needs to populate.

The cache is safe to make static and unbounded-in-practice because its key space is
bounded by construction, not by runtime input: `path` comes only from configuration
(the policies a deployment operator writes into `application.yml`), never from a
request. A caller cannot grow this map — the number of distinct keys tops out at the
number of configured policies, typically single digits. `computeIfAbsent` gives the
same one-compile-per-pattern behaviour the discarded `compiled` field was chasing,
without the field.

### Lesson

A type that is both a domain value object *and* a `@ConfigurationProperties` binding
target must stay bindable through its canonical constructor — derived, non-serializable
fields (compiled patterns, parsed regexes, opened resources) don't belong as record
components no matter how natural they look next to the data they're derived from. Push
the derivation into a method backed by a cache keyed on the actual configuration value,
and audit any such cache's key source before trusting it to stay bounded: "keyed by
something only an operator writes" and "keyed by something a request supplies" look
identical in the code until an attacker discovers the difference.

---

## Challenge 39 — A bucket cache keyed on the client alone lets one policy drain another's allowance

**Phase:** Implementation

### The problem

`InMemoryRateLimitStore.tryConsume(String key, RateLimitPolicy policy)` caches one
Bucket4j `Bucket` per cache key, built lazily via `Cache.get(key, k ->
newBucket(policy))`. The natural-looking implementation uses the caller-supplied
`key` (a bare client IP, e.g. `"1.2.3.4"`) as that cache key directly. It compiles,
and it passes the single-policy tests (capacity, refill, per-IP isolation) without
any hint of a problem — the bug only shows up the moment a *second* policy asks
about the *same* client.

`Cache.get(key, mappingFunction)` only invokes the mapping function on a cache
miss; once a key is present, every subsequent `get` — regardless of which
`RateLimitPolicy` is passed alongside it — returns the same cached `Bucket`. So a
client that had already exhausted the `auth` policy's 3-per-minute bucket for
`1.2.3.4` would, on its very next request against the unrelated `public-share`
policy for the same IP, be handed back that same exhausted bucket and denied —
even though `public-share` has never seen this client before. One rate limit
silently drains an unrelated one, purely because they share a client address.

### Why it's hard

Nothing about `buckets.get(key, k -> newBucket(policy))` looks wrong locally: it
reads as "get-or-create the bucket for this key," and `newBucket(policy)` clearly
*does* build a bucket shaped for the right policy — on the first call. The mistake
is invisible until a test exercises two policies against one identical client
key, because every other test in the suite (capacity, refill, distinct clients)
only ever varies one axis (requests over time, or client identity) and happens to
pass regardless of whether the cache key includes the policy. The interface's own
javadoc already stated the intended key shape — `policyName + '|' + clientIp` —
but nothing enforced that the *implementation* actually built the key that way;
the compiler has no way to check "this string was assembled from both an IP and a
policy name."

### The solution

Build the Caffeine cache key inside `InMemoryRateLimitStore` by combining both
axes — `policy.name() + '|' + key` — instead of trusting the raw `key` parameter
as-is. Every `(policy, client)` pair now maps to its own `Bucket`, so
`separatePoliciesDoNotShareABucket` (three `auth`-policy consumes exhaust
`1.2.3.4`, then a `public-share`-policy consume for the same `1.2.3.4` must still
be allowed) passes for the right reason instead of by coincidence.

### Lesson

When a cache's real identity is a *composite* of several inputs, build the cache
key from all of them explicitly inside the component that owns the cache — never
from whichever single argument happens to look identifier-shaped, and never by
assuming a caller will pre-compose the key correctly just because a javadoc says
so. And write the one test that varies the axis the implementation is most likely
to have dropped (here: same client, different policy) — the axis a naive
single-parameter cache key silently collapses is exactly the one that every
single-axis test is structurally unable to catch.

---

## Challenge 40 — `@DynamicPropertySource` in a subclass loses to its own superclass

**Phase:** Implementation

### The problem

`RateLimitIntegrationTest` needs the limiter turned ON with its own tiny, test-scoped
policies, but its superclass, `IntegrationTest`, turns the limiter OFF for every other
test in the suite via a `@DynamicPropertySource` method (`registry.add("easycrm.rate-
limit.enabled", () -> "false")`) — necessary so 62 unrelated integration test classes
sharing one cached context don't accumulate MockMvc's fixed loopback-address traffic
into one bucket and blow the auth policy partway through the run. The obvious fix —
give `RateLimitIntegrationTest` its own `@DynamicPropertySource` method that adds
`"easycrm.rate-limit.enabled" -> "true"` — compiles, looks correct, and is exactly what
Spring's own reference docs seem to promise ("dynamic properties take higher precedence
than `@TestPropertySource`, ... regardless of declaration order or class hierarchy").
It fails anyway: every 429 assertion in the class saw 404/401 instead, because the
limiter was still off. `RateLimitProperties.enabled()` resolved to `false` even though
the subclass had explicitly, unconditionally registered `true`.

### Why it's hard

That Spring doc quote is about `@DynamicPropertySource` **vs.** `@TestPropertySource` —
a real, one-directional guarantee — not about ordering **among multiple
`@DynamicPropertySource` methods within one class hierarchy**, which the docs don't
spell out and which behaves the opposite of the intuitive "subclass overrides
superclass" mental model borrowed from method overriding. Tracing
`DynamicPropertiesContextCustomizerFactory` into `MethodIntrospector.selectMethods` →
`ReflectionUtils.doWithMethods` shows the actual mechanics: methods are collected
**leaf-class first, then superclass**, into one ordered set, and
`DynamicPropertiesContextCustomizer.customizeContext` invokes them in that same order
into a single `Map<String, Supplier<Object>>` (`DynamicValuesPropertySource`) where
`registry.add(name, supplier)` is a plain `map.put` — last write for a given key wins.
So for any property key both a subclass and its superclass register, **the superclass's
call always runs last and always wins**, unconditionally — the exact inverse of what
"a subclass overrides its superclass" would lead you to expect, and invisible from
reading either method in isolation. `ClassUtils.getMostSpecificMethod` doesn't collapse
a same-named override either: it explicitly treats static methods as non-overridable
(`NON_OVERRIDABLE_MODIFIER` includes `Modifier.STATIC`), so even declaring an
identically-named, identically-signed method in the subclass still yields two distinct
`Method` objects, both invoked, superclass still last.

### The solution

Don't fight the ordering — sidestep it. Move the *default* out of `IntegrationTest`'s
`@DynamicPropertySource` method and into a class-level `@TestPropertySource(properties
= "easycrm.rate-limit.enabled=false")` instead. That default is no longer a
`@DynamicPropertySource` registration at all, so there is no same-key race to lose.
`RateLimitIntegrationTest` then registers `"easycrm.rate-limit.enabled" -> "true"`
through its own `@DynamicPropertySource` method — and per the *actually-applicable*
Spring guarantee (`@DynamicPropertySource` unconditionally outranks
`@TestPropertySource`, regardless of which class in the hierarchy declares which), that
override wins deterministically, with no shared mutable state and no dependency on
reflection enumeration order.

### Lesson

"Dynamic property sources beat `@TestPropertySource`, hierarchy be damned" is a real,
documented, one-directional rule — but it says nothing about precedence **between**
multiple `@DynamicPropertySource` methods in one hierarchy, and that gap is exactly
where intuition (subclass overrides superclass, like method dispatch) points the wrong
way. When a subclass needs to override a superclass's `@DynamicPropertySource` value,
don't add a second `@DynamicPropertySource` registration for the same key and assume
declaration order helps you — verify the actual invocation order for the framework
version in use (here: leaf-first collection, so superclass wins last), and if it cuts
against you, move the default to a strictly lower-precedence mechanism instead of
trying to out-order the higher-precedence one.

---

## Challenge 41 — A security control keyed on attacker input can be turned against itself, twice

**Phase:** Design & Implementation

### The problem

The per-IP rate limiter (challenges #38–#40) exists to stop one class of abuse — a
client hammering `/public/q/{token}` or the auth routes — but its own design gave an
attacker two separate ways to turn the limiter itself into the weapon, and both slipped
past every test written against the feature's stated purpose before anyone asked "what
can the *attacker* make this component do?"

The first: `RateLimitStore` caches one Bucket4j bucket per client key, and the client
key is a bare IP address the caller controls the volume of, not the identity of. A
`ConcurrentHashMap`-backed cache with no bound grows by exactly one entry per distinct
IP an attacker presents. Rotating source addresses — trivial from a botnet, a proxy
pool, or plain IPv6 — costs the attacker nothing and costs the server one live
`Bucket` object forever. The feature meant to defend against resource exhaustion would,
implemented naively, become an unbounded allocator driven directly by attacker input:
a memory-exhaustion vector wearing a rate limiter's clothes.

The second, independent from the first: the filter decides *which* IP a request came
from before it can decide whether to allow it. `X-Forwarded-For` looks like the more
correct source for that decision — it is, after all, what a load balancer sets to carry
the real client address through a proxy hop — but the header ships in the HTTP request
itself, and nothing about receiving it proves who's in front of the socket. Any direct
caller may set it to whatever it likes, without a proxy in the loop at all. A limiter
that trusts it lets a single attacker mint a fresh, full bucket on every single request
just by varying one header value, which is strictly worse than not rate-limiting at
all: it looks like protection while providing none, and the attacker doesn't even need
a second IP address to defeat it — one socket, an infinite header, done.

### Why it's hard

Both mistakes make the component *look* more correct while removing its protection,
and both leave every obvious test green. An unbounded cache passes every capacity,
refill, and per-client-isolation test that exercises a handful of clients — the failure
mode only exists at a cardinality no unit test runs at. Reading `X-Forwarded-For`
"to be more accurate about the real client" is the natural next thought once you know
requests may arrive through a proxy, and a test written from the defender's assumptions
(one client, one header value, does the bucket correctly track it) confirms the code
does exactly what it was asked to do — it just never asks who's allowed to set that
header. In both cases the review question that actually catches the bug isn't "does
this work?" but "what happens if the input this control keys on is chosen by the
attacker specifically to defeat it?" — a question orthogonal to functional correctness,
and easy to never ask because the code that would fail it reads as the more careful,
more accurate implementation.

### The solution

Bound the thing the attacker can grow. `InMemoryRateLimitStore` caches buckets in a
Caffeine `Cache` with `maximumSize(50_000)` and `expireAfterAccess(Duration.ofHours(2))`
instead of an unbounded map. Eviction under this design is safe to be aggressive about,
because an evicted bucket and a bucket that simply refilled while idle are
indistinguishable to any caller: both mean "this client currently has its full
allowance." There is no state a legitimate client can lose by being evicted — eviction
only ever gives back capacity, never takes it away — so capping the cache trades
unbounded memory for, at worst, a slightly-early refill for the coldest 0.002% of
tracked clients, not a correctness or fairness regression.

Don't trust the header. `RateLimitFilter` keys exclusively on
`HttpServletRequest.getRemoteAddr()` — the actual TCP peer address, which nothing the
client sends can override — and never reads `X-Forwarded-For` itself. Getting the real
client address through an actual reverse proxy is Spring's job, not application code's:
`server.forward-headers-strategy: framework` tells the servlet container itself to
rewrite `getRemoteAddr()` (and the request's scheme/port) from the forwarded headers,
*before* any filter — including this one — ever sees the request. The property is left
off by default with a comment in `application.yml` explaining why: nothing trusted sits
in front of this app today, so honouring the header would only be handing the attacker
what they were asking for. The moment a real reverse proxy is deployed, turning the
property on is the entire fix, applied once, for every consumer of the socket address —
not a per-filter judgment call about which headers to believe.

### Lesson

When a security control's cache key or trust decision is built from attacker-controlled
input, the design review has to include the question a purely functional review never
asks: *what can the attacker make the control itself do* — not just what can it fail to
stop? An identifier the caller supplies volume or content for (a rotatable IP, a
spoofable header) is a lever on the control's own resource usage or trust boundary,
not just a dimension of the traffic it's watching. And prefer a framework-level
mechanism that states a *deployment fact* (`forward-headers-strategy` says "a trusted
proxy terminates in front of me, so believe its headers") over application code that
*guesses* the same fact from a header value with no way to verify who sent it — the
framework's version is an assertion the operator makes deliberately at deploy time; the
application's version is a default trust decision baked into code that runs identically
whether or not the assumption holds.

---

## Challenge 42 — A whole-branch review found two more ways the rate limiter goes quiet while every test stays green

**Phase:** Implementation (post-merge fix wave)

### The problem

A final review of the rate-limiting branch (challenges #38–#41) found two further
defects in the same failure family as #41: each one turns the control into a no-op
under a condition no existing test exercises, so the entire suite — including the
tests specifically written to catch a misordered or ineffective limiter — stays green.

The first: `InMemoryRateLimitStore`'s Caffeine cache hardcoded
`expireAfterAccess(Duration.ofHours(2))`, and its own javadoc claimed entries are
evicted only after "at least twice the longest configured refill period." Nothing
enforced that relationship — the `2h` was just a number that happened to equal twice
the shipped `public-share` policy's `1h` refill period. `application.yml`'s own
comments actively invite an operator to retune `refill-period` to something longer
(there's a worked comment about credential-stuffing thresholds right next to it). Retune
`public-share` to `6h` and the eviction window is still `2h`: an attacker burns the
60-request allowance, waits two hours (not six), and the bucket has been evicted and
recreated full. The configured 60-per-6-hours cap silently becomes 60-per-2-hours, with
no code change, no failing test, and a javadoc comment that is now simply false.

The second: `RateLimitFilter` matched policies against `request.getRequestURI()`, which
**includes** the servlet context path. Set `server.servlet.context-path=/crm` — a
one-line, entirely ordinary deployment configuration change — and every request path
becomes `/crm/public/q/...`, `/crm/api/v1/auth/...`, etc. None of the configured
`RateLimitPolicy` patterns (`/public/q/*`, `/api/v1/auth/**`) match a URI with that
prefix, `policyFor(...)` returns empty for every request, and the entire limiter
becomes a permanent no-op — not degraded, not misconfigured-but-present, just gone.
No test in the suite sets a context path, so nothing catches it.

### Why it's hard

Both bugs are invisible to the exact kind of test the branch already had discipline
about writing. `RateLimitIntegrationTest.limiterRunsBeforeSpringSecurity` proves the
filter is *positioned* correctly; it says nothing about whether the filter still
*matches* anything once an orthogonal piece of configuration (context path) changes
the string it matches against. `InMemoryRateLimitStoreTest.refillsAfterThePeriodElapses`
proves the bucket refills correctly for a fixed test policy; it says nothing about
whether the eviction window *stays correct* when that policy's refill period is later
retuned in production configuration the test never sees. In both cases the defect
lives in the gap between "this policy" (what the unit test hardcodes) and "any policy
this configuration could describe" (what production actually runs) — a gap unit tests
that construct their own fixed `RateLimitPolicy` structurally cannot see, no matter how
thorough they are about the one policy they did construct.

### The solution

For the eviction window: stop hardcoding it. `InMemoryRateLimitStore.evictionWindowFor`
derives the window from the live `RateLimitProperties` — twice the longest configured
`refillPeriod` across all policies, floored at `MIN_EVICTION_WINDOW` (10 minutes) so a
deliberately tiny test policy can't produce an absurdly short window. `RateLimitConfig`
now constructs the store with `new InMemoryRateLimitStore(properties)` instead of the
no-arg constructor, so production always ties the two together; the no-arg and
`TimeMeter`-only constructors are kept, but explicitly scoped to unit tests that supply
no configuration and therefore get a fixed fallback with no configuration-tracking
promise attached to it.

For the context path: match on the path *within* the application, not the raw URI.
`RateLimitFilter` now resolves the match target via
`UrlPathHelper.defaultInstance.getPathWithinApplication(request)` instead of
`request.getRequestURI()` — the same framework helper Spring MVC's own routing uses
internally to strip the context path before pattern-matching, rather than
hand-rolling a `substring(getContextPath().length())` that would need to independently
get empty-context-path and trailing-slash edge cases right.

Both fixes came with a test built specifically to fail on the *previous* code: one
constructs an `InMemoryRateLimitStore` from a `RateLimitProperties` with a `6h` policy
and asserts the resulting eviction window is `12h`, not the old fixed `2h`; the other
drives `RateLimitFilter` with a `MockHttpServletRequest` carrying `setContextPath("/crm")`
and a matching `setRequestURI("/crm/public/q/tok")`, and asserts the request still hits
its policy.

### Lesson

"Every test passes" proves a control works for the inputs its tests hardcode, not for
the space of configuration the control is supposed to keep working across. A javadoc
comment describing an invariant ("evicted after twice the longest refill period") is
not the same as code that maintains it — if the number and the description can drift
independently, they will, the moment someone acts on the config file's own invitation
to retune a value. And any control that matches or keys on a request property derived
from more than one source (a URI plus a context path, a header plus a trust boundary,
per challenge #41) needs a test that varies the *other* source, not just the one the
control's happy path exercises — because "no test sets a context path" is not evidence
the context path doesn't matter, it's the specific blind spot an attacker or an ordinary
deployment change will eventually land in.

---

<!-- Append new challenges below. Template:

## Challenge N — <title>

**Phase:** Design | Implementation

### The problem
### The solution
### Lesson
-->

## Challenge 43 — `Specification.and(null)` is not the null-safe no-op the plan assumed

**Phase:** Implementation

### The problem

`VisibleFinder`'s paging methods AND a policy-derived `Specification` onto a
caller-supplied filter that is routinely `null` — "list everything visible to me" has
no user filter to combine with. The implementation plan asserted this combinator was
safe: "`Specification.and(null)` is null-safe in Spring Data JPA 3+." Written naively
as `policy.customers().and(filter)`, the very first paging test — the one exercising
exactly this no-filter case — threw `IllegalArgumentException: Other specification
must not be null` at `Specification.and`, not a wrong result.

The plan's assumption was about the wrong axis: whether `and(null)` is safe is a
property of the Spring Data JPA *version on the classpath*, not of the major line
("3+"). This project is on Spring Boot 4.1.0, and the `Specification.and` it ships
still asserts its argument non-null. A behavior claim tied to a library version needs
verifying against the actual dependency in the actual build, not against a general
belief about where a change landed — the same failure mode as trusting a changelog
without pinning the version it describes.

### The solution

Push the null check into `VisibleFinder` itself rather than onto every call site or,
worse, onto the test: a private `and(Specification<T> base, Specification<T> filter)`
helper returns `base` unchanged when `filter` is `null` and only calls `base.and(filter)`
otherwise. The test that exercises the no-filter path (`pageCustomers(null, ...)`) was
kept as originally written — TDD is what caught the false assumption, so weakening the
test to route around it would have discarded the exact signal the plan needed.

### Lesson

A plan's "this is null-safe in library version X" is a claim about the dependency that
is actually resolved in *this* build, not about the library's changelog in the
abstract — verify it by running the test that exercises the null case, not by reading
what the version bump was supposed to have done. When a combinator can receive an
absent operand as a routine, expected input (an unfiltered list view, not an edge
case), guard for it at the one place that builds the combination, so every future
caller inherits the safety instead of having to remember to re-derive it.

## Challenge 44 — the two derived queries the visibility filter must NOT touch

**Phase:** Implementation

### The problem

`CustomerService.find` and `EnquiryService.find` both got re-pointed at `VisibleFinder`
in this slice, and that pattern is repetitive enough that the natural next move is to
apply it everywhere a service touches its repository — including
`EnquiryRepository.findByNormalizedPhone`, the pre-check backing "at most one active
enquiry per phone." That would be wrong in a way that does not show up as a wiring
error or a compile failure: it produces working-looking code that silently reintroduces
the invariant violation the pre-check exists to prevent.

The pre-check is check-then-act: look for an existing active row, then save if none is
found. If it were filtered to the caller's visible rows, exec A and exec B could each
run the check against their own view, each see nothing, and each successfully attempt to
create an active enquiry for the *same* phone number — because neither rep's filtered
view contains the other's row. The invariant would only get caught later, by the partial
unique index and the `DataIntegrityViolation`→409 handler (challenge #15), at whatever
moment a background job or an unrelated retry happened to trip it — a confusing
database-level conflict instead of the clean, immediate, field-level 409 the pre-check
is supposed to produce.

A broken implementation that filtered the pre-check and then fell through to that index
would *also* return 409 to the caller, so the HTTP status code alone cannot distinguish
"the pre-check caught it" from "the backstop caught it." The tempting next assumption —
that the *row count* can make up the difference, since a broken pre-check would let a
duplicate row actually get inserted — is also wrong, and finding out why is the real
substance of this entry. The unique index rejects the second row at commit, and a
constraint violation rolls back the *entire enclosing transaction*, not just the failed
`INSERT`. `create()`'s pre-check, build, and `save()` all run inside one `@Transactional`
method, so a broken pre-check that let the insert through still ends with the duplicate
row gone and the count back at 1 — identical to the correct behavior. A test asserting
only `countActiveEnquiriesFor(phone) == 1` would pass whether the implementation was
correct or silently broken, which defeats the point of writing it. The only place the two
paths actually differ is the error *message*: the pre-check's `ConflictException` carries
"an active enquiry already exists for this phone," while the index backstop's handler
returns the generic "the request conflicts with existing data"
(`ApiExceptionHandler.dataIntegrity`). `dedupeStillTripsAgainstAnInvisibleEnquiry` asserts
on that message, with the row count kept only as a secondary sanity check, not the
discriminator.

### The solution

Leave `requireNoActiveDuplicateExcept` calling `enquiries.findByNormalizedPhone` directly
on the raw repository, never through `VisibleFinder`, with a comment at the call site
stating why so the next reader does not "fix" it into conformance with the rest of the
service. The parent spec (§8) calls for a later guard test,
`VisibilityScopingArchTest`, to allowlist this method (and `CustomerRepository.findByGstin`,
the GSTIN-uniqueness equivalent) **by name** — that test is a separate task's deliverable
and does not exist yet in this tree, so today the call-site comment is the only thing
stopping a future "cleanup" from routing it through the filter. Once the guard test lands,
a future attempt to do that — or a new uniqueness check added without consulting this
lane — will have to argue its way past an explicit allowlist entry rather than silently
pass because nothing noticed.

This is a genuine trade-off, not a free lunch: the unfiltered 409 discloses to exec A
that *someone* in the tenant already holds that phone number, even though exec A cannot
see whose record it is or anything else about it. That is accepted because the
alternative — a uniqueness invariant that silently breaks under concurrent use by two
reps who cannot see each other's records — is worse than the disclosure.

### Lesson

A uniqueness pre-check and a visibility filter answer different questions — "does this
value already exist anywhere in the tenant?" versus "can this caller see this record?" —
and conflating them by routing the former through the latter converts a correctness
guarantee into a per-user view. The failure mode is not silent data corruption — the
database-level unique index is still there and still rolls back the offending
transaction, so no duplicate row survives either way. The failure mode is a *worse
error experience at an unpredictable moment*: a clean, immediate, field-level 409 from
the pre-check degrades into a generic database-conflict 409 from the backstop, thrown
from wherever the second `save()` happens to land, with no guarantee that's even in the
original request path (a retry, a batch job, a queued write). When a visibility layer is
introduced as a blanket rule ("filter every read of these repositories"), the exceptions
to that rule need to be found by asking "which queries feed a uniqueness check rather
than return a record to the caller," and then locked down with an allowlist and a
same-file comment — not left to be rediscovered the next time someone "cleans up" the
one service that still calls its repository directly.

A second, sharper lesson sits inside the first: when two code paths (a correct one and a
broken one) both end in a 409 and both leave the row count unchanged, a test that only
checks the row count is not a regression test for the difference between them — it is a
regression test for the database constraint, which was never in question. The database
guarantees the *count*; only the application guarantees *which layer caught the problem
and what it tells the caller*. A test meant to prove "the app-level check is still doing
its job, not just riding on the schema's coattails" has to assert on something the schema
can't produce on its own — here, the distinct error message each path emits. Whenever a
test's purpose is to catch an app-level check being silently bypassed in favor of a
database-level backstop, checking that the operation *failed* (or that a side effect
count is unchanged) is not enough if the backstop fails and unwinds the same way the
correct path does; identify what's true in the correct case but not in the degraded one,
and assert on that specifically.

## Challenge 45 — Truncating a "unique-looking" ID for a test fixture, when the ID is time-sortable

**Phase:** Implementation

### The problem

`QuotationOrderVisibilityTest` seeds three quotations and three orders in one
`@BeforeEach`, each needing a distinct `quote_no` / `order_no` to satisfy
`uq_quotation_tenant_no` / `uq_order_tenant_no`. The obvious fixture shortcut —
`"Q-" + quotation.getId().toString().substring(0, 8)` — first failed on `value too long
for type character varying(32)` (a full UUID plus prefix overruns the column; an easy,
expected fixture bug, fixed by truncating). The truncated version then failed
differently and far more surprisingly: `duplicate key value violates unique constraint
"uq_quotation_tenant_no"`, even though the three source UUIDs were all distinct
`UUID.randomUUID()`-style values as far as the test author assumed.

They weren't fully random. `BaseEntity.id` is generated with
`@UuidGenerator(style = UuidGenerator.Style.TIME)` — a UUIDv7-style, time-sortable ID
whose leading bytes encode a millisecond timestamp specifically so IDs sort and index
well by creation order. Three quotations created in the same `@BeforeEach`, milliseconds
apart, share that timestamp prefix; only the *trailing* bits carry the per-record
randomness. Truncating to the first 8 hex characters (`substring(0, 8)`) kept exactly
the part that repeats across near-simultaneous inserts and discarded the part that
doesn't — turning a "these are UUIDs, collisions are astronomically unlikely" intuition
into an almost-guaranteed collision for anything seeded in a tight loop.

### The solution

Stop deriving the fixture's uniqueness from the entity's own ID at all. Use an explicit
per-test sequence counter (`docSeq`, incremented once per seeded quotation/order) to
build `"Q-SEED-" + seq` / `"O-SEED-" + seq`. This is both simpler and actually correct:
uniqueness now comes from a value the test controls directly, not from a substring of an
ID whose internal structure the test wasn't reasoning about at all.

### Lesson

"It's a UUID, so any slice of it is unique enough" is only true for names generated by
`Style.RANDOM`. A time-sortable ID generator (UUIDv7, ULID, Snowflake, and this
project's `Style.TIME`) makes exactly the opposite trade deliberately — front-loading
shared, low-entropy bits (a timestamp) in exchange for index/sort locality — and every
one of them concentrates that low-entropy region in the same place: the front. Never
truncate a generated ID for a "just needs to be unique enough" fixture value without
first checking how that ID is generated; if it's time-ordered, either use the whole
value, take entropy from the *tail*, or — the more robust fix, used here — stop
depending on the ID's structure at all and mint uniqueness from something the test
owns outright, like a counter.

## Challenge 46 — An allowlist is a forcing function; a blocklist is a decaying guarantee

**Phase:** Implementation

### The problem

`VisibilityScopingArchTest` closes the layer this slice built: every read of
`CustomerRepository`, `EnquiryRepository`, `QuotationRepository`, and `OrderRepository`
must go through `VisibleFinder`, or it silently returns rows the caller cannot see. The
obvious way to write that guard is a blocklist — enumerate the known-dangerous read
methods (`findById`, `findAll`, `findOne`, ...) and fail on those. It would have passed
today, with the same green result as the allowlist this entry is about. The difference
only shows up a year from now, when someone adds `EnquiryRepository.findByAssignedTo`
for a new report. A blocklist doesn't know that method exists; it passes silently, and
the new query bypasses the finder from the day it's written, with no test, no reviewer
comment, and no build failure to say so — the exact decay this guard exists to prevent.

Phrased as an allowlist instead, that same new method defaults to **forbidden**. The
build breaks the moment it's added, and the failure message names the call site. Its
author is forced to open this file and argue, in a comment, which lane the new query
belongs in — the same forcing function `TenantScopingArchTest.GLOBAL_TABLES` already
provides for tenant scoping. The guard's value isn't in today's four allowlisted method
names; it's in what happens to the *next* name nobody has thought of yet. A rule that
protects against unknown-future cases has to default to "forbidden, argue your way in,"
not "permitted, argue your way out" — that's the whole design decision, and it's
invisible if you only look at which rule passes on the classes that exist right now.

That same argument applies recursively to the allowlist's own contents: this task's
brief carried a `deleteAll` entry the design spec's authoritative allowlist (§8) does
not have, and grepping the actual call sites turned up zero callers of `saveAndFlush`,
`delete`, or `deleteAll` on any of the four guarded repositories today — only `save`,
`findByGstin`, `findByNormalizedPhone`, and `findByQuotationId` are actually reached.
An allowlist is only a forcing function if every entry in it was argued for, not carried
over from a template; an unused or undocumented entry is a silent door left ajar in the
same way a missing one is, just in the other direction. The spec's list was followed as
written and `deleteAll` was dropped, rather than trusting the brief as pre-verified. The
whole-branch review that closed out this slice re-ran the same grep against `saveAndFlush`
and `delete` and found the same answer — still zero callers — so both were dropped from
`ALLOWED_METHODS` too; the allowlist now names exactly the four methods that are actually
reached: `save`, `findByGstin`, `findByNormalizedPhone`, `findByQuotationId`.

### The solution

Write the condition as an allowlist keyed on method name, matched against calls whose
target owner is one of the four guarded repository interfaces, with a comment at each
allowlist entry stating the specific invariant that requires it to stay unfiltered
(GSTIN uniqueness, phone dedupe, or the quotation→order same-customer no-op) — not
merely "this one's fine." Then prove the rule can actually fail: temporarily route
`CustomerService.find` back through `CustomerRepository.findById` directly, rerun the
test, and confirm the reported violation names `CustomerService` and `findById` before
reverting. The literal condition object here is the one Challenge 33 already worked out
in detail — `noClasses().should(condition)` inverts every event the condition emits, so
the "bad" case has to be reported as `SimpleConditionEvent.satisfied(...)`, which reads
backwards until you've internalized the inversion — and this task reused that fix
rather than rediscovering it. What's new here is *why* the drill matters even when you
already trust the polarity: a guard's forcing function is only as real as the last time
someone watched it actually stop a bad change, and an allowlist that has never been
proven to reject anything is a syntax tree, not a guarantee.

### Lesson

An allowlist and a blocklist can produce an identical pass/fail result for every case
that exists today and still be opposite in what they guarantee about tomorrow — the
difference is which side of "unknown new case" the design defaults to, and that
choice has to be made deliberately, not backed into by whichever enumeration was
easier to write first. Anywhere a structural guard exists specifically to catch a
mistake nobody has made yet (a new derived query, a new entity, a new global table),
default to forbidden-until-argued, matching the pattern this repo already established
in `TenantScopingArchTest.GLOBAL_TABLES`. And because `noClasses().should(customCondition)`
silently inverts a hand-rolled condition's events (Challenge 33), a guard like this one
is not "known correct" from a passing run alone — the mandatory prove-it-can-fail drill
is the only check that would catch either bug: an inverted condition that vacuously
passes, or an allowlist copied from a template with an entry nothing calls and a spec
entry silently dropped.

---

## Challenge 47 — A `+1hr` test fixture is a hidden bet on when the suite runs

**Phase:** Implementation

### The problem

`FollowUpVisibilityTest` seeds two follow-ups both due `Instant.now().plusSeconds(3600)`
and asserts the owner's dashboard summary reports them as `upcoming`. The task brief
that specified this test got that assertion right for the scope's *definition* —
`UPCOMING` is `due_at >= endOfTodayIST` — but wrong for what a fixed one-hour offset
actually produces: whether `now + 1hr` lands before or after `endOfTodayIST` depends
entirely on what wall-clock time IST it is when the suite runs. Run it at 16:09 IST
(as first attempted here) and `now + 1hr` is 17:09 IST — same day, well before
midnight — so the row is `DUE_TODAY`, not `UPCOMING`, and the assertion fails. The
same fixture only passes if the suite happens to run inside the ~1-hour window before
midnight IST. That is not a flaky test in the usual sense (nondeterministic under
concurrency); it is a **deterministic function of local time of day** that happens to
be wrong on every single run except one hour in twenty-four — worse than ordinary
flakiness, because it fails identically and reproducibly for whoever's timezone or CI
schedule doesn't line up, and NOTHING in the test's own code reveals that dependency.

The same brief, one file over, had already solved this exact class of problem for
`FollowUpEndpointTest` — using `±172,800` seconds (2 days) specifically because that
offset is unambiguous no matter what time of day CI runs, per that file's own comment.
The fix did not travel to the sibling test that needed it just as much.

### The solution

Change the fixture's offset from `+3600` seconds to `+172,800` seconds (2 days) —
long enough that `now + offset` cannot land inside the current IST day regardless of
what the current IST time is, matching the convention already established in
`FollowUpEndpointTest`. The assertion (`upcoming == 1`) needed no other change: it was
always the fixture's magnitude that was wrong, not the scope logic being tested.

### Lesson

A time-window boundary test (`OVERDUE` / `DUE_TODAY` / `UPCOMING`, or any midnight- or
period-edge classification) cannot be exercised safely with an offset whose size is
comparable to the window itself — "due in one hour" is not a stable proxy for "due
later today" when the day itself is defined in a fixed timezone independent of when
the test executes. The only offsets that are safe test fixtures for this shape of
scope are ones proven to fall unambiguously on one side of every boundary the scope
cares about, for every possible run time — which for a day-granularity boundary means
an offset measured in multiple days, not hours. When a design already worked this out
once in one file (the `±172,800`-second convention here), a reviewer copying the
pattern to a sibling file needs to copy the *offset*, not just the shape of the test —
the two are easy to pull apart without noticing, because both compile and both read as
"due in the future."


---

## Challenge 48 — A completion note that logs as `SYSTEM` can never be corrected

**Phase:** Implementation

### The problem

`FollowUpService.complete` optionally writes an `Activity` when the request carries
an activity `type` — closing a task and recording what happened are one user
intention. `ActivityService` already had exactly one writer for activities the
service itself produces without the create-time subject gate: `logSystem`, built for
`QuotationAcceptedActivityListener`. Reusing it here was the path of least
resistance — `complete` had already loaded and gated the follow-up's subject through
`find(id)`, which is precisely the situation `logSystem`'s own doc comment describes
as safe to skip re-gating for.

Reusing it would have been wrong anyway: `logSystem` calls `Activity.system(...)`,
and `Activity.edit` unconditionally rejects any row whose `source` is `SYSTEM`
("a system-logged activity cannot be edited"). A completion note is not something
the application observed — a person typed it into the request body. Logging it as
`SYSTEM` would make it permanently uneditable, so a user could never fix a typo in
their own words. The endpoint test only asserts `type` and `body` on the written
row, so it passes identically whichever source is used — nothing in the test suite
would catch the wrong choice.

### The solution

Added `ActivityService.logManualForGatedCaller`, a second skip-the-gate writer that
calls `Activity.manual(...)` instead of `Activity.system(...)`. It is identical to
`logSystem` in the one property that matters for its existence (the caller has
already gated the subject, so no second `requireVisibleSubject` query is needed) and
different in the one property that matters for correctness (`MANUAL` source, so
`Activity.edit` will accept a later correction from whoever logged it).
`FollowUpService.complete` calls this new method, never `logSystem`.

### Lesson

"Already gated, skip the second query" and "who gets to edit this row later" are two
independent axes — a helper that is safe on the first axis is not automatically safe
on the second. When a new call site's content originates from a human typing into a
request body, its `source` has to reflect that regardless of which existing
already-gated helper looks structurally closest, because a test asserting only the
fields it happened to check (`type`, `body`) cannot distinguish the two paths — only
reading the aggregate's own edit-guard (`Activity.edit`'s `SYSTEM` rejection) surfaces
the consequence.

---

## Challenge 49 — Fail-fast validation would have made the atomicity test tautological

**Phase:** Implementation

### The problem

`ActivityService.create` now optionally writes a `FollowUp` alongside the `Activity`
in one request (`POST /api/v1/activities` with a nested `nextFollowUp`), and the
whole point of doing both in one `@Transactional` method is that a bad assignee must
not leave an orphaned `Activity` behind. The obvious, more conventional shape is to
validate first and save second: call `assignableUsers.require(next.assignedTo())`
before `activities.save(...)`, so a bad assignee never touches the database at all.

That shape is a trap for the one test that matters here,
`aBadAssigneeRollsBackTheActivityToo`. If validation runs before the `Activity` is
ever saved, the test passes whether or not `@Transactional` rollback works at all —
the activity row was simply never written, which is a much weaker claim than "the
transaction rolled it back." A reviewer (or a future refactor) could delete
`@Transactional` from `create` entirely and this test would keep passing, silently
losing its entire reason for existing.

### The solution

`create` saves the `Activity` first, *then* validates the assignee and saves the
`FollowUp`. A bad assignee now means one wasted `INSERT` on a rare error path, which
`@Transactional`'s default rollback-on-`RuntimeException` discards along with
everything else the transaction touched. I verified the ordering actually earns its
keep rather than assuming it, by two separate mutations of the same code, each run
against the untouched `LogAndScheduleEndpointTest`:

1. First attempt: extract the activity save into a private method annotated
   `@Transactional(propagation = REQUIRES_NEW)`, called via `this.method(...)`. The
   test still passed — a false negative. Self-invocation bypasses Spring's
   proxy, so the new annotation was silently never applied and the method ran
   inside the same outer transaction as before, proving nothing.
2. Second attempt: remove `@Transactional` from `create` entirely (no
   self-invocation involved). `aBadAssigneeRollsBackTheActivityToo` went red
   immediately — the `Activity` row survived the `ValidationException` because
   `ActivityRepository.save` got its own implicit transaction from Spring Data and
   committed before the exception was ever thrown. Restoring `@Transactional`
   turned it green again.

### Lesson

A rollback test is only as strong as the failure mode you proved it catches, and
"it currently passes" is not that proof — a broken version of the code can pass a
correctness test tautologically if the code path never reaches the state the test
is supposed to catch. Break the invariant on purpose and confirm the test goes red
before trusting it. And when the chosen way to break it is "remove propagation
scope," self-invocation is a second, independent way for that experiment to lie to
you: an `@Transactional` (or any AOP-advised) method called via `this.foo()` from
inside the same class runs with no proxy in the call path at all, so the annotation
is silently a no-op — the safe way to force a genuinely separate transaction for a
test is to remove the surrounding boundary, not to add a nested one through
self-invocation.

---

## Challenge 50 — The polymorphic-subject visibility gate: two tables, two strategies, one structural guard

**Phase:** Implementation (activity/follow-up slice)

### The problem

`activity` and `follow_up` both point polymorphically at one of four already
visibility-filtered aggregates (`Customer`, `Enquiry`, `Quotation`, `Order`). An
activity or follow-up hanging off an enquiry I cannot see must be unreachable —
the visibility layer challenge #46 hardened for the four existing aggregates has to
extend to two new tables that don't look like the existing four at all.

### Why it's hard

The two tables look symmetrical — same polymorphic `(subject_type, subject_id)`
pair, same four possible subjects — but they are not. `follow_up` has its own
`assigned_to`; `activity` does not. So one table has *intrinsic* visibility (filter
on a column it owns) and the other has *derived* visibility (its access is only as
visible as whatever it points at) — the same row shape, two genuinely different
answers to "who can see this."

The derived half is the one that bites. The obvious guard for "every read must
resolve through the subject's own visibility" is an ArchUnit rule over
`ActivityRepository`'s *declared* methods — and that guard does not work, for
exactly the reason challenge #46's own `VisibilityScopingArchTest` had already
recorded from the other side: a `JpaRepository` sub-interface *inherits*
`findById`/`findAll`/`findAllById` whether or not it declares them, and a rule that
only inspects declared methods is blind to everything inherited. `activities
.findById(id)` would compile, run, and never touch `requireVisibleSubject` at all —
the exact bypass the gate exists to prevent — while a declared-methods-only rule
stayed green throughout. This is not a hypothetical: it is the same empirical
finding `VisibilityScopingArchTest`'s own comment records about a method
*reference* to an inherited `CrudRepository` method resolving its ArchUnit target
owner to the Spring Data supertype rather than to the local interface, and so
escaping any owner-name check built over the local interface. Challenge #46 met
this from the "guard a repository that has real inherited methods" side; this slice
meets it from the "design a repository so it has none to inherit" side — same root
cause, opposite fix.

The gap doesn't stop at the obvious inherited CRUD methods either. `JpaRepository`
is not the only supertype that smuggles in unscoped reads: mixing
`QueryByExampleExecutor` or `QuerydslPredicateExecutor` in alongside a bare
`Repository` marker brings its own inherited `findAll(Example)`/`findOne(Example)`
or `findAll(Predicate)` — invisible to a declared-method rule for the identical
reason, and easy to add later without anyone noticing it reopens the hole. This gap
was not caught while writing the guard; it surfaced in review, and closing it meant
listing those two executors alongside `JpaRepository` in the forbidden-supertype
set, not just naming the one obviously dangerous interface.

### The solution

`follow_up` joins the existing guarded set with a plain `Specification` on its own
`assigned_to` column — one line, because the property it needs (intrinsic
ownership) is exactly what `VisibilityPolicy`'s existing shape already provides.

`activity` cannot join that set — there is no column to filter on — so it is gated
at its subject instead: `VisibleFinder.requireVisibleSubject(SubjectType, UUID)`
switches over the four existing `findX` methods and throws `NotFoundException` on
an empty result, the same 404-not-403 contract every other visibility check uses.
The gate is made *unbypassable* rather than merely documented: `ActivityRepository`
extends the bare `Repository<T, ID>` marker, not `JpaRepository`, and declares
exactly three methods, none of which is a by-id-alone lookup. There is nothing to
inherit, so there is nothing left for a declared-method rule to miss —
`ActivityRepositoryScopingArchTest` asserts the supertype list first (no
`JpaRepository`/`CrudRepository`/`QueryByExampleExecutor`/`QuerydslPredicateExecutor`
anywhere in it) and only then asserts every declared method takes a
`(SubjectType, UUID)` pair. The first assertion is load-bearing; the second is
worthless without it.

### Lesson

When a guard has to hold a property, prefer removing the capability over policing
its use — a repository that only has three methods, none of them unscoped, needs no
rule cleverness at all. And check what a rule can actually *see* before trusting it:
an ArchUnit assertion over declared members is blind to everything inherited, and
that is exactly where the dangerous methods live — not just the obvious
`JpaRepository` CRUD surface, but any mixed-in executor interface that brings its
own inherited finder along with it. This is the same lesson challenge #46 logged,
now confirmed from the design side rather than the guard side: the two are the same
finding, met twice, because the codebase now has one repository built the
conventional way (many inherited methods, policed by an allowlist) and one built to
have nothing to police.

---

## Challenge 51 — `OVERDUE` as a predicate, not a status

**Phase:** Implementation (activity/follow-up slice)

### The problem

The parent spec promises `follow_up` is "first-class, with its own reminder
scheduler," and a scheduled job needs somewhere to send the reminder it computes.
There is nowhere: WhatsApp is a `wa.me` deep link with no push channel behind it,
email exists but is the channel these users read least and a nudge needs delivery
tracking and dedupe no spec has scoped, and there is no frontend yet to receive an
in-app notification. Building the reminder scheduler anyway would be building a job
that fires into a void.

### Why it's hard

The tempting move is to build the machinery regardless, because it's the part of
the promise that *looks* like the feature: an `OVERDUE` status column, a scheduled
job that flips `PENDING` rows past their `due_at` to `OVERDUE`, maybe an index to
make the sweep cheap. It compiles, it demos, and "you never lose a follow-up" reads
as delivered. It is also strictly more moving parts for strictly less truth: a
status column maintained by a job can fall behind, crash mid-sweep, or simply not
run — and then the row's own `status` column actively lies about whether it is
overdue, which is worse than not tracking the concept at all, because a lying flag
is trusted by definition.

### The solution

Don't store it — compute it. `OVERDUE` is `status = PENDING AND due_at < now()`,
evaluated at read time by `DueWindow`, with `OVERDUE` / `DUE_TODAY` / `UPCOMING`
defined as three disjoint, exhaustive scopes over `PENDING` rows in a fixed
timezone (IST) day boundary — so the dashboard's three counts always sum to the
total `PENDING` count, by construction, not by convention. No column, no job, no
sweep to fall behind on. When a real notification channel eventually exists, the
scheduled job that sends into it reads the exact same predicate this slice already
implements; nothing here has to be migrated or undone, only wrapped in a sender.

### Lesson

A denormalised flag maintained by a job is a row that can lie about itself — if the
derived form is fast enough to compute at read time (one index on
`(tenant_id, assigned_to, status, due_at)` made it so here), the flag is a
liability, not an optimisation, because it adds a failure mode (the job falls
behind or dies) without adding any information the predicate didn't already carry.
Also worth checking deliberately, not assuming: three scopes that each look obvious
in isolation (`OVERDUE`, `DUE_TODAY`, `UPCOMING`) can still silently overlap or
leave a gap at their boundary — verify a partition actually partitions before
trusting that three counts sum to the whole.

---

## Challenge 52 — A scheduled job has no JWT, and getting the ordering wrong is silent

**Phase:** Implementation (quotation auto-expiry slice)

### The problem

Every existing tenant-scoped read in this codebase runs behind `JwtAuthFilter`, which
resolves `TenantContext` before anything else touches the request. A nightly job has no
request and no token — nothing establishes `TenantContext` the way the filter does, and
nothing forces it to be set at the right moment relative to the transaction. Get the
ordering wrong and the result is not an exception; it's silence.

### Why it's hard

`TenantAwareTransactionManager.doBegin` reads the `TenantContext` ThreadLocal to set the
`app.current_tenant` Postgres GUC, and Hibernate resolves a session's tenant discriminator
**once, at session-open** (challenge #9) and never re-reads it. So context set *after* the
transaction has already opened binds to nothing: `doBegin` finds no context, returns early
with the GUC unset, and every RLS-scoped table then returns **zero rows instead of
raising**. A job built this way looks, from the log line, exactly like a job that correctly
found nothing to expire — there is no stack trace to Google, no failed assertion, just an
empty sweep every single night.

There is a second, independent door into the identical silent failure, and it is easy to
walk through while trying to fix the first one. The instinctive fix for "no per-request
transaction boundary" is `@Transactional` on the per-tenant unit of work — but a
`@Transactional` method called from the runner's own loop is a **self-invocation**: the
Spring proxy is bypassed, so the "transaction" is whatever the caller already has (typically
none), and nothing new opens. The next instinct is to inject `TransactionTemplate` and call
`tx.execute(...)` explicitly — except Boot's autoconfigured `TransactionTemplate` bean is
`PROPAGATION_REQUIRED`. If the runner is ever invoked from *inside* an existing transaction
(a caller already holding one), `tx.execute` **joins** it rather than opening a new one:
`doBegin` never runs, the GUC stays unset, and the read returns zero rows — the exact same
symptom as the ordering bug, reached from the opposite direction. Self-invocation and a
joining caller are two different bugs that produce one indistinguishable failure mode.

### The solution

`TenantJobRunner` owns both halves of the ordering so no future job has to re-derive
either: `runInTenant` wraps `tx.execute(...)` **inside** `TenantContext.runAs(...)`, never
the reverse, and the template it uses is built by the runner itself —
`new TransactionTemplate(transactionManager)` with
`setPropagationBehavior(PROPAGATION_REQUIRES_NEW)` explicitly set — rather than an injected,
autoconfigured bean. `REQUIRES_NEW` closes the joining-caller trap the same motion that
owning the template closes the self-invocation trap: every call always gets its own
transaction, regardless of what, if anything, is already open on the calling thread.
`TenantJobRunnerTest.eachTenantsBodySeesOnlyItsOwnRows` is the test that proves the ordering
half fires: it is verified to fail (zero rows, no exception) when `runAs` and `tx.execute`
are inverted. A second test in the same class,
`opensItsOwnTransactionEvenWhenTheCallerAlreadyHasOne`, independently pins the
`REQUIRES_NEW` half: it uses Boot's autoconfigured, `PROPAGATION_REQUIRED`
`TransactionTemplate` bean as the **caller's** outer transaction -- standing in for a
caller that already holds one -- and asserts the runner's own per-tenant work still sees
its tenant's rows, which only holds if the runner truly opens its own transaction rather
than joining the caller's.

### Lesson

When the failure mode is "returns nothing" rather than "throws," the test that proves the
guard fires is worth more than the guard — a correct-looking ordering with no test behind
it is one refactor away from being silently wrong again. And a fix for one silent-zero-rows
trap can open a different door to the identical symptom: closing "context after the
transaction" is not the same fix as closing "transaction joins the caller's," even though
both present as an empty result set with no error. Diagnose by asking what specifically
guarantees a *new* transaction with the *right* context bound before it opens, not just
whether a transaction exists at all.

---

## Challenge 53 — An IST calendar date compared against a `LocalDate` column

**Phase:** Implementation (quotation auto-expiry slice)

### The problem

`Quotation`'s `validUntil` is a `LocalDate` a user typed while thinking in IST. The
server's clock is `Clock.systemUTC()`. IST is UTC+5:30, so the IST calendar day rolls over
at 18:30 UTC the previous day — for roughly six hours of every day, "today" in UTC and
"today" in IST disagree. The nightly sweep needs an `asOf` date to compare against
`validUntil`, and deriving it from the wrong zone gets the direction of the bug backwards
from what most people would guess.

### Why it's hard

The naive instinct is that a UTC-derived date is somehow "neutral," and the intuitive fear
is that it would expire quotations *early*. It's the opposite. A UTC date is **never later**
than the IST date on the same instant (UTC is always behind or equal, never ahead) — so
comparing `validUntil < asOfUtc` matches **fewer** rows than `validUntil < asOfIst`, and the
naive job **delays** expiry rather than hastening it. Concretely: the job fires at 00:30
IST, which is 19:00 UTC the previous day. A quotation with `validUntil` = today's IST date
is due to expire once the IST calendar flips past that date — but `asOfUtc` at that instant
is still *yesterday's* UTC date, so the comparison sees the quotation as not-yet-due and
skips it for an entire extra night.

The bug is also easy to miss in the one place most people would look: a test written and
run during the UTC morning (roughly 00:00–18:30 UTC) sees IST and UTC agree on "today," so
it passes regardless of which zone the code actually uses. Only a test instant at or after
18:30 UTC — the IST-day rollover — discriminates the two, and nothing about the naive
implementation signals that this is the one window that matters.

### The solution

`DueWindow.todayDate(Instant)` sits beside the existing `DueWindow.today(Instant)` IST
window arithmetic (challenge #51) — one home for the zone constant, not two independently
maintained ones. It returns `now.atZone(IST).toLocalDate()`, and the sweep compares with a
strict `validUntil < asOf`, so a quote stays valid through the entirety of its stated day.
`QuotationExpiryJob` itself pins `@Scheduled(cron = "...", zone = "Asia/Kolkata")` so the
job's *fire time* is also IST-correct regardless of the deploying server's system timezone
— getting the comparison right and then running it at the wrong wall-clock instant would
just trade one zone bug for another.

### Lesson

A date column a user enters in a local zone must be compared against a date computed in
that same zone; "the server runs in UTC" is not a neutral default, it is a different
answer, and the difference is not symmetric — it can only make a UTC-derived `asOf` earlier
than or equal to the IST one, never later, so the bug it introduces always points in the
*delay* direction, not the early-expiry direction most people would guess. When a
day-boundary bug is timezone-dependent, write (or at least reason through) the test case
at the actual rollover instant, not merely at a convenient time during your own working
day — a test that only runs correctly during a specific window of the clock is not
exercising the property it claims to.

---

## Challenge 54 — Revoking an invitation needs the one hand-written tenant filter in the codebase

**Phase:** Implementation (user-invitations slice, pending list and revoke)

### The problem

`CLAUDE.md` states a hard rule: never hand-write `WHERE tenant_id = ?`; tenant isolation
is structural, via Hibernate `@TenantId` plus Postgres RLS. `InvitationService.revoke(UUID
id)` does exactly the thing the rule forbids:

```java
Invitation inv = invitations.findById(id)
    .filter(i -> i.getTenantId().equals(TenantContext.tenantId()))
    .orElseThrow(() -> new NotFoundException("invitation not found"));
```

`invitation` is a deliberately GLOBAL table (like `refresh_token` and `share_link`):
accepting an invite is pre-auth and has to resolve *which* tenant the opaque token
belongs to before any tenant context exists, so neither `@TenantId` nor an RLS policy can
apply to it — there is no tenant to filter by until the row has already been read. That
means the structural mechanism the rest of the codebase relies on is simply absent here,
and the tenant check has to be written by hand or not exist at all. Without it, any
authenticated owner could revoke any tenant's invitation just by guessing or enumerating
UUIDs, since `findById` alone has no tenant awareness whatsoever.

### The solution

Treat the global table as the deliberate, narrow exception the structural rule already
anticipates, and defend it in the two ways a `@TenantId` column would otherwise give for
free:

1. Filter in code, immediately after `findById`, before the id is trusted for anything —
   `.filter(i -> i.getTenantId().equals(TenantContext.tenantId()))`. This is the only
   hand-written tenant comparison in the codebase, and it is load-bearing precisely
   because it is the *only* mechanism, not a redundant belt-and-braces check.
2. Collapse "wrong tenant" and "does not exist" into the same response: both fall through
   to `NotFoundException` (404), never `ForbiddenException` (403). A 403 would leak that
   the id is valid *somewhere*, just not in the caller's tenant — turning `DELETE
   /invitations/{id}` into an oracle for enumerating other tenants' invitation ids.

### Lesson

A blanket rule like "never hand-write a tenant filter" is shorthand for "tenant isolation
should be structural wherever a structural mechanism can apply" — it is not a claim that
every table has such a mechanism available. A table that must be readable before a tenant
is known (pre-auth acceptance, password reset, anything resolved from an opaque token)
sits outside `@TenantId`/RLS by construction, and the right response is to name the
exception explicitly and give it the same rigor a missed structural check would have had,
not to bend the table into looking tenant-scoped or to skip the check because "the rule
says not to." And once a check exists only to keep one tenant's data invisible to
another, its failure mode must not distinguish "belongs to someone else" from "does not
exist" — any status code that does is itself a small cross-tenant information leak.

## Challenge 55 — A defensive entity invariant became the enumeration oracle it was meant to prevent

**Phase:** Implementation (user-invitations slice, pre-auth accept)

### The problem

`Invitation.accept(userId, when)` carries its own precondition — it re-asserts `PENDING`
and throws `ConflictException` otherwise — for the same reason `Quotation.expire()`
re-asserts `SENT`: an entity should not trust the caller to have checked. That is good
design in isolation, and it is exactly what makes the naive `accept` wrong.

The naive service method reads the invitation by token hash, builds the user, and calls
`inv.accept(...)`, leaving the entity's guard as the only state check. The four ways an
accept can be rejected then split by construction:

- unknown token → `NotFoundException` → **404**
- revoked or already accepted → the entity's `ConflictException` → **409**
- expired → whatever an explicit expiry check throws, typically its own status

Those distinct responses are a token-enumeration oracle for an unauthenticated caller.
A 409 says "this token is real, you are just late"; a 404 says "this token never
existed." Since the accept endpoint is `permitAll` and the token is the entire credential,
that difference tells a prober which of their guesses are worth attacking further — and it
confirms that a particular workspace has been issuing invitations at all. The leak arrives
precisely *because* the invariant is well-placed: the entity is right to refuse, and its
refusal is a different refusal from "no such row."

### The solution

Give the service one rejection point that all four states funnel into, and demote the
entity's guard to what it should be — a concurrency backstop, not the primary check:

```java
Invitation inv = invitations.findByTokenHash(hasher.sha256Hex(rawToken))
    .filter(i -> i.getStatus() == InvitationStatus.PENDING)
    .filter(i -> !i.isExpired(Instant.now()))
    .orElseThrow(() -> new NotFoundException("invitation not found"));
```

Because `filter` on an `Optional` collapses "absent" and "present but wrong state" into
the same empty `Optional`, unknown / revoked / accepted / expired all reach the *same*
`orElseThrow`, and therefore the same status code and the same body bytes. No per-state
message, no per-state status — a helpful "this invitation has expired" would undo the
whole thing.

**Two things the branch review corrected here, both worth carrying.** First, that chain
existed *twice* — once in `accept`, once in `preview` — which made the codebase's most
leak-sensitive property a remember-to-update-both convention. It is now a single
`requireLive(rawToken)` both public methods call. Second, a **fifth** state joined the four:
a valid invitation whose *tenant* is `SUSPENDED`. `AuthService.login` refuses a suspended
tenant explicitly, and `accept` is the only other entry point that resolves a tenant from
something other than an existing JWT, so it has to refuse one too — but through the same
`throw`, because a distinct status there would reopen the oracle from a new direction.
`preview` refuses it as well: a preview that succeeded where the accept fails would name
the workspace and confirm the token, which is exactly the asymmetry the shared endpoint
contract exists to prevent.

`Invitation.accept()`'s `ConflictException` still exists, but be precise about what it is
now for: it is a defensive entity precondition, **not** the mechanism that stops a double
accept. Under Postgres READ COMMITTED the loser of a race on one live token normally
re-reads a snapshot in which the row is still `PENDING` — the winner has not committed yet
— so the entity's guard does not fire at all. What actually stops the second accept is
**`@Version` optimistic locking at commit**, inherited from `BaseEntity` on the claim the
transaction writes back: the loser's update matches no row at its expected version, and
the resulting `OptimisticLockingFailureException` becomes a 409 through the global handler.
Behind that sits the **uniqueness of `(tenant_id, email)` on `app_user`**, which is the
layer that catches the case optimistic locking cannot see — two *different* invitations to
one address racing to accept, where each transaction claims its own row and neither
conflicts with the other. Two indexes now enforce it: `uq_user_tenant_email` on the raw
column (V6) and `uq_user_tenant_email_lower` on `(tenant_id, lower(email))` (V32). The
second is not redundant — the raw constraint reads `ravi@shop.in` and `Ravi@shop.in` as
different strings, so without it the same race spelled two ways produced two `ACTIVE` users
for one human, possibly with different roles. `InvitationService.accept`'s inline comment states this
correctly; the entity guard's job is to stop a future caller that bypasses the service from
writing a nonsense state, not to win the race. Either way the loser is someone who already
holds a valid token, so a distinguishable 409 on that path leaks nothing.

The property is asserted rather than described: each rejection state gets a test that
compares its response body byte-for-byte against the response for a token that never
existed, on **both** public endpoints. A future "helpful" message fails that test rather
than shipping quietly.

That last sentence is also where this entry originally over-claimed. Only the *consumed*
state was compared as bytes; revoked and expired asserted `isNotFound()` and stopped there,
which is precisely the assertion a per-state message survives. Writing "assert it as bytes"
in a lesson does not assert it — the branch review is what caught the gap. If a property is
worth a challenge entry, check that the tests pin every case the entry claims, not the one
that was convenient to write first.

### Lesson

Two individually correct decisions — an entity that defends its own invariants, and a
handler that maps each exception type to its most accurate status — compose into an
information leak on any endpoint where the request itself is the credential. On a pre-auth
route, "the most accurate error" and "the safe error" are different goals, and accuracy
is the one that has to give: the caller who legitimately holds a good token never sees any
of these responses, so precision buys nothing and costs enumeration. Prefer collapsing at
the point of *lookup* (`Optional.filter` before a single `orElseThrow`) over catching and
rewriting several exceptions downstream — the first shape makes indistinguishability
structural and leaves the entity's invariant intact as a backstop; the second is
a list someone must remember to extend. And assert it as bytes, not as intent: response
bodies are exactly the thing a prober diffs.

## Challenge 56 — Two pre-auth tokens in one codebase, with opposite storage rules

**Phase:** Design (user-invitations slice)

### The problem

By the time invitations were designed, this codebase already minted two kinds of opaque
pre-auth token, and they are stored in **opposite** ways:

- `refresh_token.token_hash` — SHA-256 at rest, single-use, rotated on every refresh.
- `share_link.token` — **plaintext**, permanent, deliberately so. Its javadoc defends the
  choice, and the defence is correct: the token only renders a frozen quotation from rows
  in this same database, and storing it plaintext is exactly what makes
  `ShareLinkService.share()` idempotent — resharing a version hands back the *same* link,
  so a URL already sitting in a customer's WhatsApp thread keeps working.

The invitation token needed a rule, and both precedents were sitting right there. Reaching
for the nearer one is the natural move: `share_link` is the most recent token this codebase
added, it is also a global pre-auth table resolved from an opaque string, and its plaintext
storage comes with a written justification rather than looking like an oversight. Copying it
would have been a real vulnerability — a database read, a leaked backup, or a log line
containing a `SELECT *` would hand the reader a working credential that **creates an
authenticated principal with a role inside a tenant**.

### Why it's hard

The hazard is that the wrong precedent is the *better-documented* one. A reasoned
justification attached to an existing decision reads as a house rule, and house rules are
what a new slice is supposed to follow. Nothing structural distinguishes the two cases:
both tables are global, both are `permitAll`, both hold a random opaque string, both are
pasted into WhatsApp by a human. Neither an ArchUnit rule nor a schema constraint can tell
them apart, because the difference is not in the shape of the data at all.

Worse, the second half of `share_link`'s argument actively points the wrong way. Plaintext
buys idempotency, and idempotency sounds like an unambiguous good — until you notice that
for an invitation, "the same link keeps working" is precisely the failure mode. A resend
must invalidate the old link, not reissue it.

### The solution

Derive the storage rule from what the token **grants**, not from what the codebase already
does with a superficially similar token:

| Token | Grants | Stored | Consumable |
|---|---|---|---|
| `share_link` | a read of one frozen document | plaintext | repeatedly, forever |
| `refresh_token` | an authenticated principal | SHA-256 | once, rotated |
| `invitation` | an authenticated principal **with a role in a tenant** | SHA-256 | once |

So `invitation` follows `refresh_token`: 256 bits from `SecureRandom`, base64url-encoded,
stored as `TokenHasher.sha256Hex`, returned exactly once in the 201 body and never
retrievable again. "Resend" is therefore not an endpoint — it is revoke + re-invite, which
mints a new token and kills the old one, which is the semantic that was wanted anyway. The
plaintext value is treated as the bearer credential it is: never logged, and the entity gets
no `toString()`, matching `ShareLink`.

### Lesson

When a codebase already contains two contradictory precedents, the question to ask is not
"which one is nearer to what I am building" but "which invariant made each one correct".
`share_link`'s plaintext storage is not a house style; it is a conclusion that depends
entirely on the token granting a **read** of already-persisted data, and it stops being
correct the moment a token grants **capability**. A well-argued precedent is the most
dangerous kind to copy, because its reasoning travels with it and gives borrowed authority
to a case it was never about. Write the *criterion* down next to the decision, not just the
decision — the next token-shaped feature (password reset is the obvious one, and is named
out of scope in this slice's spec §10) will face this exact fork, and the criterion is the
only part that transfers.

## Challenge 57 — Two tables disagreed about what an email address *is*

**Phase:** Implementation (user-invitations slice, whole-branch review fix wave)

### The problem

Two uniqueness rules, written a year apart, quietly disagreed about identity.

`app_user` has carried `uq_user_tenant_email UNIQUE (tenant_id, email)` since V6 — a
constraint on the **raw** column, so `ravi@shop.in` and `Ravi@shop.in` are two different
users. `invitation` (V31) folds case: its partial unique index is on
`(tenant_id, lower(email))`, and the service's duplicate pre-check folded too, because an
invitation to a case variant of a live invitation is obviously the same invitation.

Both decisions are defensible in isolation. Together they open a hole that neither table
can see, because it only exists on the path *between* them:

1. `ravi@shop.in` is already an `ACTIVE` member.
2. The owner invites `Ravi@shop.in`. The membership check was `findByEmail` — exact —
   so it misses. The pending pre-check folds case but finds no pending row. The partial
   index sees no clash. **201.**
3. The invitee accepts. `accept` inserts `User("Ravi@shop.in", ...)`, and
   `uq_user_tenant_email` compares raw strings, sees something new, and allows it.

The tenant now has two `ACTIVE` users for one human — plausibly with *different roles*,
since the invitation names the role — and `AuthService.login`'s exact-match lookup returns
whichever spelling you happen to type. Nothing throws. Nothing is logged. The only symptom
is a permissions bug that appears to depend on how someone capitalised their own email.

What makes this hard to catch is that no single component is wrong. Each table's rule is
internally consistent; the bug lives in the *seam*, and a test of either table alone
passes.

### The solution

Fix both layers, because they fail differently.

**The service check** becomes `users.findByEmailIgnoreCase(...)`. That is what produces the
readable 409 for the ordinary case, and it is also a check-then-act window — it cannot
help the case where two *different* invitations, spelled differently, are accepted
concurrently. Neither accept is an invite, so no pre-check runs at all.

**The database** closes that window: `V32` adds
`CREATE UNIQUE INDEX uq_user_tenant_email_lower ON app_user (tenant_id, lower(email))`,
deliberately **in addition to** `uq_user_tenant_email` rather than replacing it. A
functional unique index is the only artifact that can enforce "one address, one member"
against writers that never see each other.

One implementation trap surfaced while fixing the sibling finding in the same block, and it
is worth its own paragraph because the symptom impersonates the bug. Expiry is lazy by
design, so an expired invitation stays `PENDING` forever and blocks its own address from
being re-invited; the fix is for `invite` to revoke the dead row and carry on, freeing the
partial index inside the same transaction. Written the obvious way — `existing.revoke()`,
`save`, then `save` the new invitation — it fails with a unique-violation on the index it
was supposed to have freed. **Hibernate's `ActionQueue` executes every `EntityInsertAction`
before any `EntityUpdateAction`**, regardless of the order the code called `save` in, so
the new `PENDING` row reaches the database while the old one is still `PENDING` on disk.
The index is a plain `CREATE UNIQUE INDEX`, not a constraint, so it is not deferrable and
there is nothing to postpone the check to commit. `saveAndFlush` on the revoke forces the
`UPDATE` out first. The failing form was confirmed by reverting the flush and watching the
test go red, not by reasoning alone.

### Lesson

Uniqueness is a statement about **identity**, and identity has to be defined once for the
whole system, not per table. When two tables key on the same real-world thing — an email
address, a phone number, a GSTIN — and normalise it differently, the gap between them is
reachable by any flow that reads one and writes the other, and it is invisible to tests
that exercise either table alone. The tell is a `lower()`, `trim()` or `ignoreCase` that
appears on one side of a boundary and not the other.

And when the fix is "compare it case-insensitively", a service-level check is only the
error-message half. Anything that must hold across concurrent writers belongs in a
constraint or index; Postgres will index an expression, so a functional unique index costs
one line and turns a convention into a fact. Keep the older raw constraint alongside it —
it is subsumed, not contradicted, and dropping it widens what a future migration may
silently do to the column.

Finally: Hibernate's flush order is not your call order. Any transaction that frees a
unique index by an `UPDATE` and then re-fills it with an `INSERT` needs an explicit flush
between the two, or it will fail with the exact error the change was meant to prevent.

---

## Challenge 58 — SpotBugs's `baselineFile` had to be decompiled to trust, and a `--` in an XML comment silently broke it

**Phase:** Implementation

### The problem

Task 4 (build-hygiene slice) wires SpotBugs + find-sec-bugs into both Gradle projects,
gated so only *new* findings fail the build; the first-ever run produces 32 pre-existing
findings (29 root, 3 `platform-primitives`) that must be baselined rather than fixed. The
plan deliberately left the baseline *mechanism* open, because it depends on what the
resolved plugin version actually supports: "if the task exposes a `baselineFile`
property, use it; if not, fall back to a generated exclude filter."

`SpotBugsExtension`/`SpotBugsTask` in spotbugs-gradle-plugin 6.5.11 do declare a
`baselineFile: RegularFileProperty`. That alone is not evidence it does anything — a
declared-but-dead property is exactly challenge #33's ArchUnit rule shape (a check that
looks wired and passes vacuously). Trusting it on the strength of autocomplete would have
been the same mistake in a different tool.

Verifying by *reading the docs* wasn't an option either: the brief already flagged that
`plugins.gradle.org/api/gradle/1.0/search` is dead, and the plugin's own site documentation
for `baselineFile` is thin-to-absent for 6.x. The only authoritative source was the
plugin's own bytecode.

Once wired, a second failure showed up that looked unrelated: with `ignoreFailures =
false` and a hand-assembled `baseline.xml` (see Solution), the gate still failed on every
pre-existing finding — as if the baseline were being ignored entirely, with no diagnostic
in the default log output explaining why.

### The solution

Decompiled the plugin jar (`javap -p -c` on the extracted classes) rather than guessing
from the property's existence. `SpotBugsRunner`'s bytecode shows `getBaselineFile()`
read exactly like `getIncludeFilter()`/`getExcludeFilter()` and pushed onto the SpotBugs
CLI args behind the literal string `-excludeBugs` (confirmed by the constant pool entries
next to it: `-exclude`, `-excludeBugs`, `-onlyAnalyze`). `-excludeBugs` is a real,
long-standing SpotBugs core flag: it reads a prior bug-collection XML and suppresses any
finding whose `instanceHash` matches an entry in it. So `baselineFile` is not a Gradle-
plugin invention — it is a thin pass-through to a mechanism SpotBugs itself has always
had, and it works per the plan's preferred branch.

Because one `baselineFile` value is shared by both projects via the convention plugin, the
baseline had to cover both: root's and `platform-primitives`'s `build/reports/spotbugs/
main.xml` were merged (concatenating their `<BugInstance>` elements into one
`<BugCollection>`, via a small Python script rather than hand-editing) into
`config/spotbugs/baseline.xml`. This works because matching is by hash, not by project —
a hash that belongs to the other project's classes simply never matches, so a merged file
is safe for both.

The second failure — gate still red with the baseline in place — turned out to be
unrelated to Gradle or the merge logic entirely. Running with `--info` surfaced the real
executor's stderr, buried under the Worker API's own summary: `org.dom4j.
DocumentException: Failing reading .../baseline.xml`. SpotBugs's baseline reader
(`dom4j`) was silently failing to parse the file and — critically — not treating that as
a load-bearing error worth surfacing at the default log level, so `-excludeBugs` matched
nothing and every original finding still failed the build, with no explanation of *why*
the baseline had no effect. The cause was banal: the hand-written provenance comment at
the top of the generated `baseline.xml` used `--` as a prose dash ("32 findings -- see
...", "make the gate pass -- new findings must be..."). `--` is illegal *anywhere* inside
an XML comment body, not just as a delimiter — `python3 -c "import
xml.etree.ElementTree as ET; ET.parse(...)"` caught it in one line where staring at the
file did not. Swapping the dashes for semicolons fixed it immediately.

### Lesson

A build-tool property existing on an extension is not evidence it is consumed —
decompile (or otherwise trace) the call site before committing a whole task's design
branch to it, the same discipline #33 established for assertions. Here it happened to be
real and well-behaved, which is worth stating plainly since the alternative (silently
inert) was equally plausible going in.

Separately: a tool that reads a config file with a lenient XML library and then treats a
parse failure as "zero entries" rather than "abort" produces a gate that fails *open* —
here in the safe direction (every finding still blocks), but the class of bug generalizes
to filters that fail open the *unsafe* way. When a generated exclude/baseline/allowlist
file silently has no effect, check whether the file parsed at all before re-deriving the
content; `xml.etree.ElementTree.parse()` (or equivalent) is a one-line well-formedness
oracle that is cheaper than re-reasoning about hashes. And never hand-write `--` into an
XML comment — it is invisible in most editors' comment styling and invalid regardless of
position within the comment body, not just adjacent to `<!--`/`-->`.
