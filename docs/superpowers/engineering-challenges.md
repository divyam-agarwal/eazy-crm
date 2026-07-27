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

<!-- Append new challenges below. Template:

## Challenge N — <title>

**Phase:** Design | Implementation

### The problem
### The solution
### Lesson
-->
