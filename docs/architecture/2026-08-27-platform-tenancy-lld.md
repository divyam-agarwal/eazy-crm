# `platform-tenancy` — Low-Level Design

**Date:** 2026-08-27
**Status:** Design only. Zero code changed.
**Code baseline:** `80e74a3` — `platform/tenancy` + `platform/persistence` is 11 files, 9 tests
**Parent:** [`../superpowers/specs/2026-08-26-shared-platform-modules-design.md`](../superpowers/specs/2026-08-26-shared-platform-modules-design.md)
**Depends on:** [`2026-08-26-platform-security-lld.md`](2026-08-26-platform-security-lld.md) — LLD #3, for token verification only
**Constrained by:** LLD #3's SR4 (the filter's exception contract) and SF2 (provenance ≠ role)
**LLD #4 of 6 — the large one.**
**Published:** https://claude.ai/code/artifact/fb129561-a8e4-4616-aed9-9e2c4c146b93

**Label prefixes.** `CD` decisions, `CR` rules, `CF` findings, `CB` bugs — `C` for *context*, the
thing this module exists to bind. `TB` is **not** available: it belongs to the outbox LLD's bug list
(TB1–TB14), and this module is referenced from that document.

---

# Part 0 — What changed from the parent spec

The parent (§2.4) scoped this module as `TenantContext` sealed, the transaction manager, the resolver,
three config classes, three persistence base types, and six auto-configured entry adapters. Reading
the source moved four things — one of which is a factual correction, and one of which is the most
serious finding in any of the four LLDs so far.

## Half the entry points have nothing to bind

There is **no `@Scheduled`, no `@Async`, and no SQS listener anywhere in the codebase.** `AsyncConfig`
wires `TenantAwareTaskDecorator` into a `ThreadPoolTaskExecutor` that nothing ever submits to. So of
the six adapters P1 requires:

| # | Adapter | Real call sites today |
|---|---|:-:|
| 1 | `JwtAuthenticationFilter` — HTTP | 1 (every authenticated request) |
| 2 | `PublicTokenEntryPoint` — `/public/q/*` | 1 (`PublicShareController`, inline `runAs`) |
| 3 | `CredentialBoundScope` — signup / login / refresh | 3 (hand-written `try`/`finally` in `AuthService`) |
| 4 | `TenantLoopRunner` — `@Scheduled` | **0** |
| 5 | `TenantAwareSqsListener` — SQS | **0** |
| 6 | `TenantAwareTaskDecorator` — `@Async` | **0** — the decorator exists; nothing is `@Async` |

P1 was written without this being visible, and it sits awkwardly against P5's rule (*a mechanism used
by exactly one service is not a platform mechanism*) — a mechanism used by *zero* services is a
harder case, not an easier one.

**CD1 — specify all six now; build each with its first consumer.** P1 is not reopened: `platform`
still owns every entry point, and no service may write its own. What changes is the schedule. Adapters
1–3 are built in sub-project 1 because they have call sites that can exercise them. Adapters 4–6 are
specified here as contracts and built alongside the consumer that first needs them — SQS with
`platform-outbox` (sub-project 6), the scheduled loop with the first scheduled job, `@Async` with the
first `@Async` method.

**Amended 2026-08-27, by LLD #5's OF8.** "The first scheduled job" is the **outbox relay**, and it is
the one job in the system that must deliberately *not* bind a tenant — it reads across all of them by
design. So `TenantLoopRunner` is **not** built with the first `@Scheduled` job after all; it is built
with the first *tenant-scoped* one. The relay and the outbox reaper are named, permanent exemptions,
and the ArchUnit rule that every other `@Scheduled` method goes through the loop runner lives in
`platform-outbox`'s LLD Part 6, so any further exemption is something a reviewer has to approve
rather than something a developer can quietly assume.

The reason is not economy, it is provability. An adapter whose only exercise is a test written to
exercise it proves that the adapter runs, never that the *real* path goes through it — and PF7 already
records that no test proves an adapter was used rather than bypassed. Building adapter 5 now is
actively worse than waiting: it would exist beside `IdempotentConsumer`, which already does this job
on paper, and the parent's own Part 7 instructs this LLD to align the two rather than duplicate them.

## Adapter 5 is not a class, it is a primitive that `IdempotentConsumer` composes

The outbox LLD's `IdempotentConsumer.consume` already opens with `runAs` before the transaction and
then does dedupe-and-handle in one transaction. A separate `TenantAwareSqsListener` would either
wrap it or race it.

**CD2.** `platform-tenancy` owns the *binding* primitive — `runAs(principal, body)` with its
before-the-transaction ordering guarantee — and `platform-outbox` composes it with deduplication.
There is no sixth class. The parent's adapter table gains a column saying so, and the outbox LLD's
revision #3 is thereby discharged: alignment means composition, not a shared base class.

## `TenantPrincipal` becomes a sealed interface

Three forces want to change the crown-jewel type, and 137 call sites (122 in tests across 54 files,
10 `set`/`clear` and 5 `runAs` in main — PF4's count confirmed exactly) mean it can be changed once
or not at all:

| Force | Wants |
|---|---|
| PF2 | entitlements on the principal, so B7's check stays local |
| LLD #3's SF2 | `"SYSTEM"` and `"PUBLIC"` to stop sharing a field with real roles |
| The source itself | `userId` to stop being null for exactly those two provenances |

**CD3 — provenance becomes the type.** A sealed interface with three implementations. `"SYSTEM"` and
`"PUBLIC"` cease to be strings a role check could ever match — not by convention but because
`SystemPrincipal` has no `role()` to compare. And `userId` exists only where it is real, which deletes
the two defensive `.map(TenantPrincipal::userId).orElse(null)` call sites in `OrderService` and
`QuotationService` that exist solely to survive it.

This is affordable **only** because P3's seal migration is already touching all 137 sites. Done in
that migration it is nearly free; done later it is a second sweep of the same files. There is no third
opportunity.

## The parent is wrong about ids, and there are two of them

§2.4 lists `BaseEntity — UUIDv7 id, @Version, timestamps`. `BaseEntity` uses
`@UuidGenerator(style = TIME)`, which is Hibernate's own time-based strategy — **not** RFC 9562
version 7. The `UuidV7` class beside it *is* RFC 9562, and has exactly one consumer in the entire
codebase: `Tenant`, which needs its id before insert so the context can be bound before the
provisioning transaction opens (challenge #9).

So the database holds two id formats, and neither the parent nor the module's own naming says which
is which. Recorded as **CF1**; the recommendation is to correct the description rather than unify the
formats, because both are time-sortable and a migration buys nothing.

---

# Part 1 — Where the code lives

```
platform/
└── platform-tenancy/
    ├── build.gradle.kts
    ├── src/main/java/com/easycrm/platform/tenancy/
    │   ├── TenantContext.java               sealed: runAs public, set/clear package-private (P3)
    │   ├── TenantPrincipal.java             NEW — sealed interface (CD3)
    │   ├── UserPrincipal.java               NEW
    │   ├── SystemPrincipal.java             NEW
    │   ├── PublicPrincipal.java             NEW
    │   ├── Entitlements.java                NEW — placeholder shape for PF2/B7
    │   ├── TenantAwareTransactionManager.java   unchanged — the GUC bridge
    │   ├── TenantIdentifierResolver.java        unchanged — NO_TENANT matches nothing
    │   ├── TenancyAutoConfiguration.java    NEW — replaces component scan
    │   ├── persistence/
    │   │   ├── BaseEntity.java              moved package; id strategy unchanged (CF1)
    │   │   ├── TenantScopedEntity.java      the @TenantId carrier
    │   │   ├── GlobalTable.java             NEW — replaces the FQN allowlist (CR1)
    │   │   └── UuidV7.java                  one consumer today; stays (CF2)
    │   └── entry/
    │       ├── JwtAuthenticationFilter.java     built now  · @ConditionalOnClass(Servlet)
    │       ├── PublicTokenEntryPoint.java       built now  · @ConditionalOnClass(Servlet)
    │       ├── CredentialBoundScope.java        built now
    │       ├── TenantLoopRunner.java            specified; built with first @Scheduled job
    │       └── TenantAwareTaskDecorator.java    specified; built with first @Async method
    │                                            (SQS is not a class here — CD2)
    └── src/testFixtures/java/com/easycrm/platform/tenancy/
        └── TenantContextTestSupport.java    P9 — same package, reaches the seal
```

**Package moves.** `com.easycrm.platform.persistence` → `com.easycrm.platform.tenancy.persistence`.
The base types are meaningless without `@TenantId`, and one package cannot span two Gradle modules.
Unlike LLD #2's `ApiExceptionHandler` move this one is caught by the compiler — every entity imports
these — so it is loud, not silent.

## 1.1 Build file

```kotlin
dependencies {
    api(project(":platform:platform-security"))       // VerifiedClaims, TokenVerifier — nothing else
    api("jakarta.persistence:jakarta.persistence-api")
    implementation("org.hibernate.orm:hibernate-core")
    implementation("org.springframework:spring-orm")
    implementation("org.springframework.data:spring-data-jpa")

    compileOnly("jakarta.servlet:jakarta.servlet-api")   // adapters 1–2 only
    compileOnly("software.amazon.awssdk:sqs")            // reserved for CD2's composition point
}
```

`api` on `platform-security`, not `implementation`: the filter maps `VerifiedClaims` to a
`UserPrincipal`, and a service reading the principal should see the claim type through this module
rather than re-declaring the dependency.

**It does not take `platform-primitives`.** Nothing here needs money, GST or the error vocabulary —
`TenantIdentifierResolver` returns `NO_TENANT` rather than throwing, and the filter's only exception
handling is LLD #3's `InvalidTokenException`. Keeping the crown jewels at the bottom of the graph is
the point of P2, and this is where that gets tested.

## 1.2 Auto-configuration

Everything here is `@AutoConfiguration`, registered in `.imports`. Today `HibernateTenancyConfig`,
`TransactionManagerConfig` and `AsyncConfig` are `@Configuration` classes found by the application's
component scan, and `JwtAuthenticationFilter` is a `@Component`. Once this is a jar, **none of them
are found**, and the failure is silent in the worst possible way: no `MULTI_TENANT_IDENTIFIER_RESOLVER`
means Hibernate stops filtering, no `@Primary TenantAwareTransactionManager` means the GUC is never
set, and RLS then sees an unset `app.current_tenant`. Layers 2 and 3 both disappear at once, and the
application starts cleanly.

**Ordering matters and must be declared**, not inferred:

```java
@AutoConfiguration(before = HibernateJpaAutoConfiguration.class)
public class TenancyAutoConfiguration { … }
```

`HibernatePropertiesCustomizer` must be registered before Boot builds the `EntityManagerFactory`, and
`TenantAwareTransactionManager` must win `@Primary` over Boot's own `JpaTransactionManager`.

---

# Part 2 — Class model

## 2.1 `TenantContext`, sealed

```java
public final class TenantContext {
    private static final ThreadLocal<TenantPrincipal> HOLDER = new ThreadLocal<>();

    public static Optional<TenantPrincipal> get() { … }
    public static UUID tenantId() { … }                       // null → NO_TENANT downstream

    public static void runAs(TenantPrincipal p, Runnable body) { … }
    public static <T> T runAs(TenantPrincipal p, Supplier<T> body) { … }

    static void set(TenantPrincipal p) { … }                  // package-private (P3)
    static void clear() { … }                                 // package-private (P3)
}
```

The `finally` is written once, here, instead of at 137 call sites. P3's argument in one line: **a
service that calls `set()` no longer compiles.** `ScopedValue` (JEP 506) was considered and rejected
in the parent — same migration cost plus a primitive rewrite, for a failure mode the seal already
closes. Nothing found in the source changes that.

`runAs` restores the *previous* principal rather than clearing, which is what makes nesting safe —
`TenantLoopRunner` iterating tenants inside a request context depends on it, and the existing
`runAsRestoresPrevious` test already pins it.

## 2.2 The principal (CD3)

```java
public sealed interface TenantPrincipal
        permits UserPrincipal, SystemPrincipal, PublicPrincipal {

    UUID tenantId();
    Entitlements entitlements();          // PF2 — per-tenant, so it lives on the common part
}

public record UserPrincipal(UUID tenantId, UUID userId, String role, Entitlements entitlements)
        implements TenantPrincipal {}

/** Credential flow and event consumers: a tenant is known, no user is acting. */
public record SystemPrincipal(UUID tenantId, Entitlements entitlements)
        implements TenantPrincipal {}

/** A share link: a tenant is known, there is no user and no session. */
public record PublicPrincipal(UUID tenantId, Entitlements entitlements)
        implements TenantPrincipal {}
```

**What this buys, concretely.** `TenantService.requireOwner()` today reads
`TenantContext.get().map(TenantPrincipal::role)` and compares to `"OWNER"`. Under CD3 it becomes:

```java
if (!(TenantContext.get().orElse(null) instanceof UserPrincipal u && "OWNER".equals(u.role())))
    throw new ForbiddenException("only an owner may change the business profile");
```

A `SystemPrincipal` cannot reach the comparison at all. That is SF2 closed structurally rather than
by everyone remembering not to name a pseudo-role `"OWNER"`.

**`role` stays a `String`** inside `UserPrincipal`, per LLD #3's SD1 — the vocabulary belongs to the
RBAC slice, and this module has no more claim on it than `platform-security` did.

**`Entitlements` is specified now and empty until sub-project 10** (PF2's instruction: specify the
field now rather than discover it during billing). A record with a `Set<Metric>` and a `Map<Metric,
Integer>`; `platform-entitlement` reads it, this module only carries it. It is on the *interface*, not
on `UserPrincipal`, because a plan is a property of the tenant and a scheduled job running as
`SystemPrincipal` must be as entitlement-limited as a user.

## 2.3 The GUC bridge, and the one ordering rule

```java
protected void doBegin(Object transaction, TransactionDefinition definition) {
    super.doBegin(transaction, definition);
    UUID tenantId = TenantContext.tenantId();
    if (tenantId == null) return;                       // GUC unset → scoped tables see zero rows
    em.createNativeQuery("SELECT set_config('app.current_tenant', :tid, true)") …
}
```

Unchanged from today, and the reasoning is worth restating because everything else rests on it:
`set_config(..., is_local => true)` rather than `SET LOCAL` because `SET LOCAL` cannot take a bind
parameter, and `is_local` clears the value at commit or rollback so nothing leaks back into the
pooled connection.

**CR2 — the context must be bound before the transaction opens.** Hibernate resolves a session's
tenant once, at session-open, and never re-reads it (challenge #9); `doBegin` reads the context to set
the GUC. A `runAs` *inside* an open transaction therefore changes neither. This is why
`spring.jpa.open-in-view: false` is load-bearing rather than a preference (challenge #29) — with OSIV
the `EntityManager` opens in an interceptor before the controller runs, and `/public/q/{token}` would
read under no tenant with nothing failing.

The rule has a mechanical form for the first time — see Part 4.

## 2.4 The six entry points

Every one of them is the same three lines: obtain a tenant, `runAs`, let the transaction open inside.
What differs is where the tenant comes from and what happens when it is absent.

| # | Adapter | Tenant from | Principal | Absent tenant → | Status |
|---|---|---|---|---|---|
| 1 | `JwtAuthenticationFilter` | `TokenVerifier.verify` → `VerifiedClaims` | `UserPrincipal` | unauthenticated → 401 (**loud**) | build now |
| 2 | `PublicTokenEntryPoint` | the global `share_link` row | `PublicPrincipal` | 404, uniform (**loud**) | build now |
| 3 | `CredentialBoundScope` | resolved by slug, before a token exists | `SystemPrincipal` | 401, generic | build now |
| 4 | `TenantLoopRunner` | iterates the `tenant` table | `SystemPrincipal` | **silent** — job processes nothing | with first *tenant-scoped* job — **not** the outbox relay (OF8) |
| 5 | *(composition, not a class)* | the event envelope | `SystemPrincipal` | **silent** — handler sees no rows | with `platform-outbox` |
| 6 | `TenantAwareTaskDecorator` | captured from the submitting thread | inherited | **silent** — task sees no rows | with first `@Async` |

Adapter 1's contract is set by LLD #3's SR4 and is a review-blocking rule: catch
`InvalidTokenException` and proceed unauthenticated; let `KeyUnavailableException` propagate to a 503;
catch nothing else. Today's `catch (RuntimeException)` makes a JWKS outage indistinguishable from
every user mistyping their password.

Adapter 3 replaces `AuthService`'s three hand-written `try`/`finally` blocks (PF3), which are the only
places in business logic that touch the raw `set`/`clear` — and which stop compiling under the seal,
so this adapter is not optional in sub-project 1.

**PF7 stands unresolved and is worth restating plainly:** for adapters 4–6 an unbound context produces
*no error at all* — a job that processes nothing looks exactly like a job with nothing to process. The
parent's answer is a bound-context metric per adapter, alarmed on absence. This LLD adds nothing
better and does not pretend to.

## 2.5 `persistence/` — and the id that is not a v7

`BaseEntity` (id, `@Version`, `@CreatedDate`/`@LastModifiedDate`) and `TenantScopedEntity` (the
`@TenantId` carrier, `updatable = false`, no setter — Hibernate populates it) both move package and
change nothing else.

`UuidV7` has one consumer, `Tenant`, and by P5's rule ought to follow `PasswordConfig` into
`identity-svc`. **It does not** (**CF2**), and the distinction is worth being explicit about because
this LLD applied that rule strictly one document ago: `PasswordConfig` is a mechanism that happens to
have one user, whereas `UuidV7` exists *because of* the tenancy ordering rule in 2.3 — an id must
exist before the insert so the context can be bound before the transaction. Any service that ever
provisions a tenant-scoped aggregate root needs it for the same reason. P5's test asks whether a
mechanism belongs to one service; this one belongs to the *rule*, and the rule is this module's.

## 2.6 The layer that is not Java

Layer 3 is 14 hand-written migration blocks:

```sql
ALTER TABLE customer ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON customer
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
```

`NULLIF` because a referenced custom GUC resets to `''` rather than NULL, and a `USING` clause also
acts as `WITH CHECK` for inserts. Both correct, both already in the challenge log.

Two gaps, and the first is the most serious finding in any of the four LLDs.

### CB1 — RLS is not `FORCE`d, so the whole layer rests on a role assignment

**Fourteen tables `ENABLE ROW LEVEL SECURITY`. Zero `FORCE ROW LEVEL SECURITY`.** In Postgres a table's
owner is exempt from its own policies unless the table is `FORCE`d. Today that is fine and deliberate:
Flyway connects as `easycrm_owner`, the application connects as `easycrm_app`, and `V4`'s comment says
so out loud.

Under the split that becomes **five services, five sets of database credentials, provisioned by
Terraform and handed out through Secrets Manager or IAM auth.** If any one of them is issued the owner
role — the single most ordinary infrastructure mistake available — every RLS policy silently stops
applying for that service. There is no error, no log line, and no failing test, because the test
harness wires both roles correctly by construction. Layer 3 evaporates and layers 2 and 4 keep
passing.

**Recommendation: `FORCE ROW LEVEL SECURITY` on every tenant-scoped table**, so isolation is a
property of the schema rather than of who happens to be connecting. The cost is real and must be
planned for: a Flyway migration that backfills tenant-scoped data can no longer do so as owner without
either binding the GUC first or an explicit `BYPASSRLS` grant for the migration role. That is a
constraint on `platform-db` (the Flyway-ordering section of the parent), not a blocker — and it is the
right direction, because it turns "we deployed with the correct role" from a hope into a non-issue.

### CB2 — nothing checks that a policy exists

ArchUnit fails the build when an `@Entity` lacks `@TenantId` (layer 4 guarding layer 2). **Nothing
guards layer 3.** A new table that declares `@TenantId` and forgets the two SQL lines passes every
test in the suite — Hibernate filters it, so behaviour is correct — and quietly ships with one of the
four isolation layers missing. That is precisely the defence-in-depth the design exists to provide,
lost silently.

It is testable, and cheaply, because the database knows:

```java
// for every table backing a TenantScopedEntity
select relrowsecurity, relforcerowsecurity from pg_class where relname = ?
select 1 from pg_policy p join pg_class c on c.oid = p.polrelid
 where c.relname = ? and p.polname = 'tenant_isolation'
```

**CR3** in Part 4. It is an integration test rather than an ArchUnit rule because the fact lives in
Postgres, not in bytecode.

---

# Part 3 — How a service adopts it

```kotlin
dependencies {
    implementation(project(":platform:platform-tenancy"))
    testImplementation(testFixtures(project(":platform:platform-tenancy")))
}
```

No `@Configuration`, no filter registration, no transaction-manager bean, no `@EnableAsync`. A service
writes entities that extend `TenantScopedEntity`, ships migrations that enable and force RLS, and is
done. **The six entry points are not adoptable surface** — that is P1's whole point, and it is the
reason this module is `implementation` for a service but `api` for nothing.

## 3.1 The seal migration — 137 sites, once

This is the largest mechanical change in the platform split, and CD3 rides along with it because there
will not be a second sweep.

| Where | Sites | Becomes |
|---|---|---|
| `AuthService` (PF3) | 3 × `set`/`clear` + `try`/`finally` | `credentialScope.runAs(slug, () -> …)` |
| `PublicShareController` | 1 × `runAs` with `"PUBLIC"` | `PublicPrincipal`, via adapter 2 |
| `DemoSeeder` | 1 × `runAs` with `"OWNER"` | `UserPrincipal` |
| `OrderService`, `QuotationService` | 2 × `.map(::userId).orElse(null)` | `instanceof UserPrincipal u` — the null goes away |
| `TenantService` | 1 × role string compare | pattern match (2.2) |
| Tests | **122 sites across 54 files** | `TenantContextTestSupport.set/clear`, same package (P9) |

The test migration is a one-line import swap per file, by design: the fixture lives in the same
package, so the `@BeforeEach`/`@AfterEach` pairs work unchanged. **The whole plan rests on
`java-test-fixtures` genuinely reaching package-private members of the main source set** — the parent's
Appendix B item 2, still unverified, and the single item most capable of invalidating P3 and P9
together. Verify it on day one, before touching 54 files.

---

# Part 4 — What keeps it honest

**CR1 — every `@Entity` is tenant-scoped unless it says why it isn't.** Today the exception list is a
hard-coded `Set.of("com.easycrm.tenant.Tenant", "com.easycrm.iam.RefreshToken",
"com.easycrm.sales.ShareLink")` inside one test class. Those three FQNs belong to **two different
future services**, so the split forces the list to split, and the "one list, reviewed together"
property that made it trustworthy is exactly what is lost.

```java
@Retention(RUNTIME) @Target(TYPE)
public @interface GlobalTable { String reason(); }
```

```java
classes().that().areAnnotatedWith(Entity.class)
    .and().areNotAnnotatedWith(GlobalTable.class)
    .should().beAssignableTo(TenantScopedEntity.class);
```

The justification moves next to the entity, where it is reviewed by whoever reviews the entity, and
the rule ships in this module so every service runs the identical one. `@GlobalTable(reason = "…")`
forces the reason to be written down; an empty-reason variant should fail review, which is the one
part of this that stays human.

**CR2 — no `runAs` inside an open transaction.**

```java
noMethods().that().areAnnotatedWith(Transactional.class)
    .should().callMethod(TenantContext.class, "runAs", TenantPrincipal.class, Supplier.class)
    .because("Hibernate resolves a session's tenant at session-open and doBegin reads the "
           + "context to set the GUC — a runAs inside the transaction changes neither, and "
           + "the work silently runs under the previous tenant or none. Challenge #9.");
```

The first mechanical form of the ordering rule in 2.3, which until now has been a comment and a
challenge-log entry. It is approximate — it cannot see a `runAs` two frames below a `@Transactional`
method — and that is stated rather than hidden (**CF5**).

**CR3 — every tenant-scoped table has RLS enabled, forced, and a `tenant_isolation` policy.** The
integration test in 2.6. This is the rule that closes the gap between layer 4 and layer 3.

**CR4 — `set`/`clear` are package-private.** Not a rule; the compiler. That is the entire argument for
P3 over documentation.

**CR5 — `spring.jpa.open-in-view` must be `false`.** A config assertion, because nothing else in the
build catches it: no test fails and no exception is thrown when it flips (challenge #29).

**CR6 — the JWT filter catches `InvalidTokenException` only.** LLD #3's SR4, restated here because
this is the module the filter now lives in.

---

# Part 5 — Test plan

## 5.1 What exists today

Nine tests across five classes — and they are the load-bearing ones in the codebase:

| Class | Asserts |
|---|---|
| `TenantContextTest` | unset by default; set/get; **`runAs` restores the previous principal** |
| `TenantFilteringIntegrationTest` | `tenant_id` auto-populated on insert; reads filtered by tenant (layer 2) |
| `RlsIntegrationTest` | a **raw** query with no context returns zero rows (layer 3) |
| `TenantProvisioningTest` | tenant + its first tenant-scoped row insert atomically (challenge #9) |
| `TenantAwareTaskDecoratorTest` | context propagates to the worker thread; cleared after the run |

**The gap is in layer 3.** `RlsIntegrationTest` has exactly one test and it covers the *no context*
case. Nothing asserts that a raw query under tenant A cannot see tenant B's rows — which is the case
RLS exists for, the one that survives a Hibernate bypass, and the one `@TenantId` cannot cover. The
suite proves RLS is switched on; it does not prove RLS isolates.

## 5.2 What this design adds

| Test | Kind | Asserts |
|---|---|---|
| Raw SQL under tenant A cannot read tenant B's rows | integration | **the most important missing test in the codebase** — layer 3 doing its actual job |
| Every `TenantScopedEntity` table: `relrowsecurity` and `relforcerowsecurity` true, `tenant_isolation` present | integration, reflective over the entity set | CR3 — closes CB1 and CB2 together |
| Connecting as the **owner** role still sees only its tenant's rows | integration | proves `FORCE` works, and is the only test that would have caught CB1 |
| `runAs` after a transaction opens does **not** change the GUC | integration | pins challenge #9 as behaviour rather than a comment |
| A `SystemPrincipal` fails a role check | unit | CD3 — SF2 closed structurally |
| Each entry adapter binds the context and clears it on the exception path | unit per adapter | the `finally`, once per adapter instead of 137 times |
| `TenantIdentifierResolver` returns `NO_TENANT`, and `NO_TENANT` matches no rows | unit + integration | the fallback is safe, not merely present |
| Auto-configuration registers the resolver and the `@Primary` transaction manager | `ApplicationContextRunner` | CB3 — the silent double failure of 1.2 |
| `open-in-view` is false | config | CR5 |
| CR1, CR2 | ArchUnit | Part 4 |

## 5.3 Not testable here

- **That the deployed service connects with a non-owner role.** `FORCE` (CB1) is what makes the
  question stop mattering; until then it is Terraform's, and no Java test can see it.
- **That an entry adapter was used rather than bypassed** (PF7). A metric, alarmed on absence — not a
  test.
- **`ScopedValue` semantics under virtual threads**, if the P3 rejection is ever revisited.

---

# Part 6 — Bugs you will hit

| # | Bug | Why it happens | Fix |
|---|---|---|---|
| **CB1** | A service is provisioned with the database owner role and **every RLS policy silently stops applying** for it. No error, no log, no failing test | RLS is `ENABLE`d, never `FORCE`d, so the owner is exempt. Fine with one deployment and one role; five services and Terraform-issued credentials is a different bet | `FORCE ROW LEVEL SECURITY` (2.6), plus 5.2's owner-role test — the only test that would catch it |
| **CB2** | A new table ships with `@TenantId` and no RLS policy. Behaviour is correct, so nothing fails; one of the four isolation layers is simply absent | ArchUnit guards layer 2; nothing guards layer 3 | CR3's `pg_policy` test |
| **CB3** | After the split a service starts cleanly with **no tenant filtering and no GUC at all** | `@Configuration`/`@Component` stop being scanned once the module is a jar; layers 2 and 3 disappear together, silently | Auto-configuration (1.2) + 5.2's context test. Same shape as LLD #3's SB2, with a worse blast radius |
| **CB4** | A cross-tenant write — the worst outcome available | `runAs` called after the transaction opens, or `open-in-view` flipped back to true | CR2, CR5, and 5.2's ordering test. Also the outbox LLD's TB9 |
| **CB5** | A scheduled job or SQS handler silently processes nothing, for weeks | Adapters 4–6 fail with no error — an unbound context looks exactly like an empty queue | PF7's bound-context metric, alarmed on absence. This LLD offers nothing better |
| **CB6** | A pooled request thread serves tenant B under tenant A's context | A `set` without its `finally` | Impossible to write under P3 — the seal is the fix, and CB6 exists only in the pre-migration codebase |
| **CB7** | Someone reads `TenantPrincipal.role()` on a `SystemPrincipal` and gets `"SYSTEM"` past a role check | Only possible before CD3 lands. After it, `SystemPrincipal` has no `role()` | CD3 — and this is the reason it rides the seal migration rather than waiting |
| **CB8** | The seal migration stalls at 54 test files because the fixtures source set cannot reach package-private members | `java-test-fixtures` visibility is assumed, not verified — parent Appendix B item 2 | Verify on day one. If it fails, P3 and P9 both need rework *before* any file is touched |

---

# Appendix A — Findings

| # | Finding | Severity |
|---|---|---|
| **CF1** | The parent's §2.4 says `BaseEntity — UUIDv7 id`. It is `@UuidGenerator(style = TIME)`, Hibernate's own time-based strategy, not RFC 9562 v7. The real `UuidV7` is used by `Tenant` alone, so **two id formats coexist** and neither document names them correctly | Correct the description; do not unify. Both are time-sortable, so index locality — the only reason the choice mattered — holds either way. A migration would buy nothing and rewrite every primary key |
| **CF2** | `UuidV7` has exactly one consumer and therefore appears to fail P5's rule, one document after that rule was applied strictly to `PasswordConfig` | Deliberate exception, argued in 2.5: it belongs to the *ordering rule*, not to a service. Recorded so the inconsistency reads as a decision rather than an oversight |
| **CF3** | **Layer 3 is proved switched on and never proved to isolate.** `RlsIntegrationTest` covers only the no-context case; nothing asserts tenant A's raw query cannot see tenant B's rows | 5.2's first test. Cheap, and it is the single highest-value test missing from the codebase |
| **CF4** | The global-table allowlist is three FQNs spanning two future services, in one test class. The split forces it apart and dissolves the review property that justified it | CR1's `@GlobalTable(reason)` annotation. Also worth noting: `ShareLink` and `RefreshToken` are global for *different* reasons (pre-auth resolution vs. pre-auth lookup), which a bare name list never recorded |
| **CF5** | CR2 cannot see a `runAs` more than one frame below a `@Transactional` method, so it catches the common case and not the general one | Accepted, and stated. The general case needs runtime instrumentation; the common case is where the bug actually gets written |
| **CF6** | Three of six entry points have no consumer, so P1 — which was written before that was visible — sits against P5. CD1 resolves the schedule without reopening the ownership | Watch for drift: a specified-but-unbuilt adapter is a contract nobody is compiling against. Re-read 2.4 when each consumer arrives |
| **CF7** | `AsyncConfig` today defines a `taskExecutor` bean with `@EnableAsync` that nothing submits to. It is dead wiring, and it also silently becomes Boot's default executor for anything that later adds `@Async` | Harmless now. Under CD1 it is built with its first consumer, so the dead bean goes away rather than being inherited into five services |
| **CF8** | `Entitlements` is specified with no content, because sub-project 10 defines it. A placeholder on the crown-jewel type is a small debt with a large blast radius if its shape turns out wrong | Keep it opaque — a record the entitlement module reads and this module only carries. If it ever grows a method this module calls, that is the signal the boundary moved. **Confirmed load-bearing by LLD #6's EF1:** keeping it opaque is what prevents `platform-tenancy` naming a `Metric` that lives in `platform-entitlement` — a third cycle, avoided by the same principle as the first two (PF18). **Extended by LLD #5's OF9:** putting `entitlements()` on the *interface* means every construction site must supply them, and an event consumer restoring context from a message cannot — so the type needs an `Entitlements.unresolved()`, and "unresolved" must not read as "none" |
| **CF9** | `TenantAwareTransactionManager` is `@Primary`. A service that later adds a second `DataSource` — the outbox relay is the named candidate — gets two transaction managers, one of which must **not** be tenant-aware | Parent Appendix B item 4, still open. It becomes real in sub-project 6, not before |

---

# Appendix B — To verify before implementation

1. **That `java-test-fixtures` reaches package-private members of the main source set** under Gradle's
   separate compilation. Parent Appendix B item 2. **Verify first** — P3, P9, CD3 and a 54-file
   migration all rest on it, and a failure here changes the plan rather than the code.
2. **That `FORCE ROW LEVEL SECURITY` does not break Flyway**, whose role owns the tables. Establish
   whether the migration role needs `BYPASSRLS`, or whether binding the GUC inside data migrations is
   workable. This gates CB1's fix, which is the most valuable change in this document.
3. **That `@AutoConfiguration(before = HibernateJpaAutoConfiguration.class)` reliably registers
   `HibernatePropertiesCustomizer` before the `EntityManagerFactory` is built** in Boot 4.1, and that
   `@Primary` on an auto-configured transaction manager beats Boot's own. Both failures are silent and
   simultaneous (CB3). This is the third LLD to want Boot 4 auto-configuration behaviour verified —
   do it once, for LLD #1, #3 and #4 together.
4. **That ArchUnit 1.4.1 can express CR2** — a `noMethods().that().areAnnotatedWith(Transactional)`
   rule matching a specific overload of a static method. Prove it by writing the violation first.
5. **That Hibernate 7's `@TenantId` still populates a field with no setter** after the package move,
   and that `MULTI_TENANT_IDENTIFIER_RESOLVER` set through `HibernatePropertiesCustomizer` behaves
   identically when the resolver arrives from a jar rather than a scanned bean.
6. **Whether a sealed interface works as a `ThreadLocal` payload with no reflection surprises** under
   the record-pattern matching in 2.2 — trivial, but 137 call sites depend on the ergonomics being as
   pleasant as they look here.
