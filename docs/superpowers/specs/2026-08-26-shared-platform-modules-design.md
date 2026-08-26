# EasyCRM — Shared Platform Modules (design spec)

**Date:** 2026-08-26
**Status:** Design only. Zero code changed.
**Code baseline:** `80e74a3` — `platform` is 833 lines of Java across 28 files. 231 tests.
**Parent:** [`../../architecture/2026-08-19-aws-target-architecture-design.md`](../../architecture/2026-08-19-aws-target-architecture-design.md) (D12 — `platform` as a Gradle module, mechanisms never meanings)
**Precursor to:** six module-level LLDs, written in the order given in Part 7.

## How this relates to the other docs

| Doc | What it is |
|---|---|
| `../../architecture/2026-08-24-service-scope-and-shared-modules.md` | Per-service surface; its Part 2 proposed a decomposition, marked `[new]` |
| `../../architecture/2026-08-19-outbox-lld.md` | `platform-outbox` at class level — already written, needs the revision in Part 7 |
| `2026-08-19-billing-and-entitlements-design.md` | B7: entitlements travel in the JWT, so the check stays local |
| **this doc** | The shared-module boundaries, their contracts, and every flow that passes through them |

This document **supersedes Part 2 of the service-scope doc**, which proposed eight modules. Working
out the dependency graph reduced it to six and moved three things out of `platform` entirely.

---

# Part 0 — Decisions, and why

| # | Decision | Rejected | Why |
|---|---|---|---|
| P1 | **`platform` owns all six tenant-context entry points** and auto-configures them | Primitives only, each service wiring its own; split by transport | Five of the six fail *silently* — no exception, no log line, no failing test, only a metric that reads zero. A service that never writes an entry point cannot get one wrong. This is the project's "structural, not procedural" thesis applied to the thing the thesis depends on |
| P2 | **Six modules, split by change cadence** | Three coarse; eight fine-grained | Things that change for the same reason ship together. The RS256 migration must not touch the tenancy primitives, and `platform-outbox` must *declare* its dependency on money serialisation — that declaration is the structural fix for TB3 |
| P3 | **`TenantContext` sealed: `runAs` public, `set`/`clear` package-private** | Leave as is; migrate to `ScopedValue` (JEP 506) | The compiler enforces it — a service that calls `set()` no longer compiles, so the `finally` is written once in `platform-tenancy` instead of at 125 call sites. `ScopedValue` gives a stronger guarantee but costs the same 122-site migration *plus* a primitive rewrite, for a failure mode P3 already closes |
| P4 | **`Gstin` and `StateCode` fold into `platform-money`** | Their own module; `platform-web` | A quotation's money *is* GST money — the place-of-supply derivation and the rounded tax split are one concern. The name is imperfect; a seventh module for 74 lines is worse |
| P5 | **`PdfEngine` and `IndianFormats` leave `platform`** → `document-svc` | Leave them | Used by exactly one package (`sales.pdf`). Left in `platform`, all five services inherit an openhtmltopdf dependency. **A mechanism used by exactly one service is not a platform mechanism** — the second test, added to D12 |
| P6 | **`JwtService.mint` leaves `platform`** → `identity-svc`; `platform-security` verifies only | Keep minting shared | Under D11 (RS256 + JWKS) the signing key must be reachable from one task role, not five. Shipping minting in a module all five import makes BF8 — any service minting itself Enterprise — a classpath accident rather than a hypothetical |
| P7 | **`JwtAuthenticationFilter` moves to `platform-tenancy`; `TokenVerifier` returns a plain `VerifiedClaims`** | Widen the seal; keep the filter in security | Forced by P3 — see PF1. `platform-security` must never name a tenancy type, or the graph cycles |
| P8 | **Transport dependencies are `compileOnly`, adapters guard with `@ConditionalOnClass`** | Split modules per transport; force every service onto the web stack | Standard starter idiom. `notification-svc` has no servlet on its runtime classpath, so the HTTP filter never activates — one module, exact behaviour per service |
| P9 | **`java-test-fixtures` source set carries `TenantContextTestSupport`** | A `@WithTenant` JUnit extension; widen the seal for tests | Lives in the same package, so it reaches the sealed methods. Tests swap one import and their `@BeforeEach`/`@AfterEach` pairs work unchanged. The extension is the nicer end state and is not worth blocking on |

---

# Part 1 — Module map

```
platform-security   (no deps)      platform-money  (no deps)      platform-web  (no deps)
        ▲                                 ▲                              ▲
        │                                 │                              │
  platform-tenancy ───────────────────────┤                              │
        ▲            │                    │                              │
        │            └── platform-outbox ─┘                              │
        └────────────────── platform-entitlement ─────────────────────────┘
```

Three modules depend on nothing. `platform-tenancy` — the load-bearing one — depends on exactly one
thing, and only for token verification. The graph is a DAG two levels deep, which is the point:
**the crown jewels sit near the bottom, where a change to billing or PDF rendering cannot reach
them.**

| Module | Imported by | Changes when |
|---|---|---|
| `platform-money` | all 5 services + `platform-outbox` | never |
| `platform-web` | 4 (not notification) | rarely |
| `platform-security` | 4 (not notification) | sub-project 7 (RS256/JWKS) |
| `platform-tenancy` | all 5 | never — the crown jewels |
| `platform-outbox` | all 5 | sub-project 6 — being built now |
| `platform-entitlement` | 3 (metered write paths) | sub-project 10 (billing) |

`platform-db` is Flyway ordering, not Java. It stays a section of the parent doc.

## Per-service imports

| Service | tenancy | security | web | money | outbox | entitlement |
|---|:-:|:-:|:-:|:-:|:-:|:-:|
| identity | ● | ● | ● | ● | ● | ● |
| master-data | ● | ● | ● | ● | ● | ● |
| sales | ● | ● | ● | ● | ● | ● |
| document | ● | ● | ● | ● | ● | ○ |
| notification | ● | ○ | ○ | ● | ● | ○ |

`notification-svc` takes three of six and no servlet. It is not an ALB target, so a filter chain it
never uses would be a liability, not a convenience. It still needs `platform-tenancy` in full,
because every SQS consumer restores tenant context before opening its transaction (F4).

---

# Part 2 — Module contracts

Each module answers three questions: what does it do, how do you use it, what does it depend on.

## 2.1 `platform-money`

**Does.** Makes money and Indian fiscal identifiers impossible to get wrong on any wire.

**Contents.** `BigDecimalStringModule`, `MoneyJacksonConfig`, `Gstin` (checksum validation),
`StateCode` (derivation from the GSTIN prefix).

**Used as.** Auto-configuration registers the Jackson module; `Gstin.parse(s)`,
`StateCode.of(gstin)` are called directly.

**Depends on.** Nothing.

**Why it is a module and not a package.** `platform-outbox` must declare
`implementation(project(":platform:platform-money"))`. TB3 is the bug where a fresh `ObjectMapper`
inside the outbox writer serialises `BigDecimal` as a JSON **number**, silently undoing challenges
#2 and #17 — the money reaches SNS as a double after the entire stack avoided one. A declared
dependency is how that stops being possible to forget.

## 2.2 `platform-web`

**Does.** One error envelope, one page shape, and the 404-not-403 rule.

**Contents.** `ApiExceptionHandler`, the exception hierarchy (`NotFoundException`,
`UnauthorizedException`, `ForbiddenException`, `ConflictException`, `ValidationException`),
`PageResponse`.

**Used as.** The `@RestControllerAdvice` auto-registers; services throw the typed exceptions.

**Depends on.** Nothing.

**The load-bearing behaviour.** A cross-tenant read surfaces as `NotFoundException` → **404, never
403**. A 403 confirms the row exists, which is the leak the four isolation layers exist to prevent.
The handler also backstops `DataIntegrityViolationException` and `OptimisticLockingFailureException`
to 409 — the latter needs its own handler because
`ObjectOptimisticLockingFailureException` does **not** extend `DataIntegrityViolationException`.

## 2.3 `platform-security`

**Does.** Verifies a token. Hashes a password. Nothing else.

**Contents.** `TokenVerifier`, `VerifiedClaims`, `JwtProperties`, `SecurityConfig`,
`PasswordConfig`.

**Used as.** `TokenVerifier.verify(token) → VerifiedClaims`. `SecurityConfig` auto-configures the
filter chain behind `@ConditionalOnClass(Servlet.class)`.

**Depends on.** Nothing — **and this is a constraint, not an observation.** `VerifiedClaims` is a
plain record. `platform-security` must never name a `platform-tenancy` type, or the graph cycles.
See PF1.

**Does not contain.** Token *minting*. `JwtService.mint` moves to `identity-svc` (P6).

## 2.4 `platform-tenancy`

**Does.** Binds a tenant to the current call, and guarantees every database access made inside that
call is scoped to it.

**Contents.**

```
platform-tenancy/
├── TenantContext                    sealed: runAs public, set/clear package-private
├── TenantAwareTransactionManager    set_config('app.current_tenant', …, is_local => true)
├── TenantIdentifierResolver         → tenantId, or NIL UUID which matches nothing
├── HibernateTenancyConfig · TransactionManagerConfig · AsyncConfig
├── persistence/
│   ├── BaseEntity                   UUIDv7 id, @Version, timestamps
│   ├── TenantScopedEntity           the @TenantId carrier
│   └── UuidV7
└── entry/                           six adapters, auto-configured
    ├── JwtAuthenticationFilter      HTTP            @ConditionalOnClass(Servlet)
    ├── PublicTokenEntryPoint        /public/q/*     @ConditionalOnClass(Servlet)
    ├── CredentialBoundScope         signup/login/refresh
    ├── TenantLoopRunner             @Scheduled
    ├── TenantAwareSqsListener       SQS             @ConditionalOnClass(SqsClient)
    └── TenantAwareTaskDecorator     @Async
```

**Used as.**

```java
TenantContext.runAs(principal, () -> …);          // anywhere a tenant is already known
loopRunner.forEachTenant(tenantId -> …);          // one transaction per tenant, per §2.4 of the parent
```

**Depends on.** `platform-security`, for token verification only.

**Test support.** A `java-test-fixtures` source set ships `TenantContextTestSupport` in the same
package, so tests can bind a context without the seal being widened for production code (P9).

## 2.5 `platform-outbox`

Already specified at class level in
[`../../architecture/2026-08-19-outbox-lld.md`](../../architecture/2026-08-19-outbox-lld.md).
Twelve classes, `V900`/`V901` DDL shipped inside the jar, adopted by a service in three steps.

**Depends on.** `platform-tenancy` (`Outbox` is a `TenantScopedEntity`; `IdempotentConsumer` uses
`runAs`) and `platform-money` (TB3).

Part 7 lists the three revisions this document forces on it.

## 2.6 `platform-entitlement`

**Does.** Makes it impossible to ship a metered write endpoint unguarded.

**Contents.** `@RequiresEntitlement(Metric)`, the guard, and the ArchUnit rule that fails the build
for a metered endpoint without the annotation.

**Used as.** `@RequiresEntitlement(Metric.QUOTATION)` on the controller method.

**Depends on.** `platform-tenancy` (reads the principal), `platform-web` (the 402 contract).

**Deferred.** Specify the annotation and the rule now; the rest belongs with sub-project 10. The
entitlement check is a purely local operation in all five services (B7), which is what keeps a
Chargebee outage from stopping a quotation.

---

# Part 3 — Every flow through platform code

## 3.1 Tenant-context establishment — six entry points

| # | Entry point | Adapter | Binds from | Failure mode if the context is absent |
|---|---|---|---|---|
| 1 | Authenticated HTTP | `JwtAuthenticationFilter` | JWT claims | **401 — loud.** The only loud one |
| 2 | Public token route | `PublicTokenEntryPoint` | the global `share_link` row | zero rows → a blank or 404 PDF |
| 3 | Credential flow | `CredentialBoundScope` | the tenant just created or resolved | signup writes a user nobody can read |
| 4 | `@Scheduled` sweep | `TenantLoopRunner` | `shared.tenant`, the loop variable | **the job reports success having done nothing** |
| 5 | SQS consumer | `TenantAwareSqsListener` | `tenant_id` on the envelope | the event is silently dropped |
| 6 | `@Async` | `TenantAwareTaskDecorator` | captured from the calling thread | the task writes under no tenant |

**Five of six fail silently.** No exception, no log line, no failing test — only a metric that reads
zero. That asymmetry is the whole argument for P1: the one entry point that fails loudly is the one
nobody would get wrong anyway.

Entry point 3 deserves naming because it is not a transport. `AuthService.signup`, `.login` and
`.refresh` each resolve a tenant and then need it installed before their own writes — three
hand-written `set`/`try`/`finally`/`clear` sequences in business logic today. Under P3 they become
`runAs` and the pattern gets a home so it is not reinvented in a sixth service.

## 3.2 Persistence — what happens once a tenant is bound

```
runAs(principal) ──► @Transactional
                         │
                     TenantAwareTransactionManager.doBegin
                         ├─ super.doBegin()                    EntityManagerHolder now exists
                         └─ SELECT set_config('app.current_tenant', :tid, true)
                         │
                     Hibernate opens the session
                         └─ TenantIdentifierResolver → tenantId, else NIL UUID
                         │
                     ┌───┴──────────────────────────────┐
                @TenantId filter                   Postgres RLS
                (Hibernate — layer 2)          (easycrm_app, no BYPASSRLS — layer 3)
```

Three details are load-bearing and each has a comment in the source explaining why:

- **`is_local => true`**, because `SET LOCAL` cannot take a bind parameter, and because it clears at
  commit or rollback so no value leaks back into the pooled connection.
- **`NO_TENANT` is the NIL UUID**, not null — no real tenant owns it, so a scoped query with no
  context matches nothing rather than everything.
- **The GUC is left unset when there is no tenant**, so RLS sees zero rows rather than an error.
  This is deliberate and it is also why §3.1's silent failures are silent.

**The ordering constraint.** The tenant must be bound *before* the transaction opens.
`TenantAwareTransactionManager` reads it in `doBegin`, and Hibernate pins a session's tenant once at
session-open and never re-reads it. `spring.jpa.open-in-view: false` is what keeps this true for
entry point 2 — with OSIV enabled the `EntityManager` opens in an interceptor *before* the
controller runs, so `runAs` arrives too late and the endpoint reads under no tenant. Nothing in the
build catches that regression.

## 3.3 The event path

| Stage | Path | Note |
|---|---|---|
| **Write** | domain event → `OutboxWriter` `@EventListener` → outbox row, **same transaction** | `tenant_id` and `traceparent` stamped here. No dual write |
| **Relay** | `@Scheduled` 2s + ShedLock → `JdbcTemplate` on a **separate DataSource** as `relay_app` | The only flow that deliberately escapes **both** `@TenantId` (F12b) and RLS (F12) — two layers, two reasons, both silent |
| **Consume** | SQS → `runAs(envelope.tenantId)` → dedupe on `ProcessedEvent` → handler → one transaction | Context restored *before* the transaction, same rule as §3.2 |

The relay is worth restating because it took two attempts to get right. RLS blocks it, which is F12,
fixed with a `BYPASSRLS` `relay_app` role bounded by grants to outbox tables alone. Hibernate's
`@TenantId` then blocks it **again**, at a different layer for a different reason — F12b, fixed by
making the read path `JdbcTemplate` on its own DataSource. Both failures return zero rows and throw
nothing.

## 3.4 Errors

Every exception lands in `ApiExceptionHandler`. Two behaviours are load-bearing rather than
cosmetic: the 404-not-403 rule of §2.2, and the 409 backstops for constraint and optimistic-lock
violations that slip past application pre-checks.

**The split adds one case that does not exist today.** A Service Connect call to an unavailable
service must map to **503**, and must not be swallowed into a 404. If `master-data` being down looks
like "customer not found", `sales` produces a quotation with the wrong tax split — which is worse
than producing no quotation, and is exactly what §3.4 of the parent doc forbids. See PF5.

## 3.5 Serialisation

`BigDecimalStringModule` now covers **two** wires, not one: controller responses, and outbox event
payloads. The second is new with the split and is precisely where TB3 bites — an `ObjectMapper`
constructed inside the outbox writer rather than injected does not carry the module.

## 3.6 Entitlement

`@RequiresEntitlement` reads the tenant's limits from the principal, which reads them from the JWT
(B7). No service calls billing on a write path, so a vendor outage cannot stop a quotation.

**This changes a crown-jewel type.** `TenantPrincipal` is `(tenantId, userId, role)` today and must
gain entitlements. See PF2.

---

# Part 4 — Invariants

Nine properties this design holds, and what enforces each.

| Invariant | Enforced by |
|---|---|
| A service cannot bind a tenant context by hand | `set`/`clear` package-private (P3); it does not compile |
| A bound context cannot leak into a pooled thread | The `finally` is written once, inside `platform-tenancy` |
| The tenant is bound before the transaction opens | `runAs` wraps the call; `open-in-view: false` keeps it true for the public route |
| A query with no tenant matches nothing | `NO_TENANT` = NIL UUID, plus RLS with the GUC unset |
| A cross-tenant read is indistinguishable from a missing row | `NotFoundException` → 404, never 403 |
| Money never crosses a wire as a number | `platform-money` on both wires; `platform-outbox` declares the dependency |
| A metered endpoint cannot ship unguarded | ArchUnit rule in `platform-entitlement` |
| `platform` cannot reference a service package | ArchUnit rule (D12) |
| A mechanism used by one service is not in `platform` | Review rule (P5); no automation — see PF6 |

---

# Part 5 — What leaves `platform`

| Leaves | Goes to | Why |
|---|---|---|
| `PdfEngine` | `document-svc` | Used by one package. Otherwise five services inherit openhtmltopdf |
| `IndianFormats` | `document-svc` | Same — its only caller is the PDF template |
| `JwtService.mint` | `identity-svc` | The signing key must be reachable from one task role, not five (P6, BF8) |
| `DemoRecord` + controller | test fixtures | Belongs to no service; shipping a demo endpoint five times is worse than once (S9) |

---

# Part 6 — Migration

| Change | Scope | Nature |
|---|---|---|
| `AuthService` `set`/`clear` → `runAs` | 3 call sites | Behavioural; the `finally` becomes structural |
| Test call sites → `TenantContextTestSupport` | **122 sites across 54 files** (45 `set`, 77 `clear`) | Mechanical; one import swap |
| `JwtService.parse` → `TokenVerifier.verify` | 1 caller | Breaks the cycle (P7) |
| `JwtService.mint` → `identity-svc` | 1 caller | Moves with `AuthService` |
| Split one Gradle module into six | build files only | No Java changes beyond package moves |

The 122 test sites are not incidental — they are the uncontrolled surface P3 exists to remove, and
counting them is what turned "seal it" from a preference into a costed decision.

---

# Part 7 — LLD order

Dependency order, leaves first, so each LLD may assume its dependencies are already specified.

| # | Module | Status | What its LLD must settle |
|---|---|---|---|
| 1 | `platform-money` | new | Whether it owns an `ObjectMapper` bean or a `Jackson2ObjectMapperBuilderCustomizer` — `platform-outbox` needs identical config on a mapper Boot does not manage |
| 2 | `platform-web` | new | Whether the 503 mapping for an unavailable downstream lives here or in each service (PF5) |
| 3 | `platform-security` | new | The `VerifiedClaims` shape, and where the RS256/JWKS seam sits so sub-project 7 is a swap rather than a rewrite |
| 4 | `platform-tenancy` | new — **the large one** | The sealed API, all six adapters, the `@ConditionalOnClass` guards, `TenantPrincipal` gaining entitlements, and the test-fixtures migration |
| 5 | `platform-outbox` | **revise** (504 lines exist) | Three revisions: declare the `platform-money` dependency; align with the sealed API; align `IdempotentConsumer` with adapter #5 rather than duplicating it |
| 6 | `platform-entitlement` | defer to sub-project 10 | Specify only the annotation and the ArchUnit rule now |

---

# Appendix A — Findings

| # | Finding | Resolution |
|---|---|---|
| **PF1** | Sealing `TenantContext` locks `JwtAuthenticationFilter` out of it — the filter lives in `platform-security` and calls `set()`. Java has no friend classes. Moving the filter to `platform-tenancy` flips the dependency, and `JwtService.parse` returning `TenantContext.TenantPrincipal` then cycles the graph | P7 — `TokenVerifier` returns a plain `VerifiedClaims`; the filter maps it |
| **PF2** | `TenantPrincipal` must gain entitlements for B7's local check, so the billing project changes the crown-jewel type of `platform-tenancy` | Specify the field now, in LLD #4, rather than discovering it in sub-project 10 |
| **PF3** | `AuthService` is a sixth tenant-context entry point and the only one using raw `set`/`clear` in business logic — three hand-written `try`/`finally` sequences | `CredentialBoundScope`, entry adapter 3 |
| **PF4** | 122 test call sites across 54 files call `set`/`clear` directly | P9 — `java-test-fixtures` |
| **PF5** | The split introduces an error case that does not exist today: an unavailable downstream must be 503 and must not be swallowed into 404, or `master-data` being down produces a quotation with the wrong tax split | LLD #2 decides where the mapping lives |
| **PF6** | P5's rule — a mechanism used by one service is not a platform mechanism — has **no automated enforcement**. The D12 ArchUnit rule passes happily for `PdfEngine` | Accepted. It is a review rule. An ArchUnit rule counting consumers would need the full service graph at test time |
| **PF7** | Five of six entry points fail silently. The design leans entirely on P1 to close them, and P1 has no test that proves an adapter was used rather than bypassed | Each adapter emits a bound-context metric; alarm on absence, per §2.4 of the parent doc |

---

# Appendix B — To verify before implementation

1. **`@ConditionalOnClass` with `compileOnly` transport dependencies** — confirm Boot 4.1 evaluates
   the condition against the runtime classpath as expected, and that `notification-svc` with
   `platform-tenancy` on the classpath does not start an embedded Tomcat.
2. **`java-test-fixtures` visibility** — confirm the fixtures source set genuinely reaches
   package-private members of the main source set in the same package under Gradle's separate
   compilation.
3. **`HibernatePropertiesCustomizer` across modules** — `HibernateTenancyConfig` currently relies on
   component scanning from `com.easycrm`. As a separate module it must arrive through
   auto-configuration; confirm ordering against Boot's own Hibernate auto-configuration.
4. **Whether `TenantAwareTransactionManager` as `@Primary` survives** a service that also defines a
   second `DataSource` for the outbox relay — two transaction managers, one of which must not be
   tenant-aware.
5. **ArchUnit and Java 25 bytecode** — 1.3.0 silently imported zero classes and passed every rule
   vacuously. The pinned 1.4.1 must be re-confirmed for any new rule added here.
