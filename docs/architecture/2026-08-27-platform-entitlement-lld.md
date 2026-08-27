# `platform-entitlement` — Low-Level Design

**Date:** 2026-08-27
**Status:** Design only. Zero code changed. **Built in sub-project 10**, not sub-project 1.
**Parent:** [`../superpowers/specs/2026-08-26-shared-platform-modules-design.md`](../superpowers/specs/2026-08-26-shared-platform-modules-design.md) §2.6
**Product spec:** [`../superpowers/specs/2026-08-19-billing-and-entitlements-design.md`](../superpowers/specs/2026-08-19-billing-and-entitlements-design.md) — B4, B7, B9, and the 402 contract
**Depends on:** `platform-tenancy` (#4) and `platform-web` (#2)
**LLD #6 of 6 — the last, and deliberately the smallest.**

**Label prefixes.** `ED` decisions, `ER` rules, `EF` findings, `EB` bugs.

---

# Part 0 — Scope

The parent defers this module to sub-project 10 and asks for two things now: **the annotation and the
ArchUnit rule.** That instruction is right, and the reason is worth stating because it is the only
reason to write this document at all today.

Everything else here — the `plan` table, tier pricing, Chargebee, proration, the tenant state machine
— is sub-project 10's and would be guesswork now. But **the annotation constrains every controller
written between now and then.** A `@PostMapping` that ships in the enquiry or activity slice next
month is a metered endpoint whether or not metering exists yet, and if the shape arrives after those
endpoints do, someone has to go back and audit them all. Specifying the shape early costs one small
document; discovering it late costs a sweep of every create endpoint in five services.

So: the annotation, the exemption, the rule, and the dependency direction. Not the pricing.

## What the source forced

**ED1 — the guard is advisory under concurrency, and that is a property, not a defect.** The check is
count-then-insert and the annotation sits on the controller, so it runs before the transaction opens:
three concurrent creates against a limit of 50 can all pass at 49 and produce 52. This codebase has
twice refused to accept exactly that shape — challenge #15's `DataIntegrityViolation` backstop and
challenge #26's `UNIQUE(tenant_id, enquiry_id)` both exist because a guard that reads before it writes
is not a guarantee.

The difference is what the invariant protects. One-quote-per-enquiry is a *correctness* invariant: two
quotations against one enquiry corrupts lead traceability and there is no acceptable overshoot. A plan
limit is a *commercial* one: 52 quotations on a 50-quotation plan is a billing conversation, and the
cost of making it airtight — a `usage_counter` row locked on every metered write, one hot row per
tenant per metric — is a throughput tax paid forever to prevent an overshoot nobody would notice.
**Recorded so that a future limit which genuinely must not be exceeded is recognised as a different
problem** rather than assumed to be covered (EF4).

**ED2 — every create declares one or the other.** Nothing in bytecode says which endpoints are
metered: `ContactController` has a bare `@PostMapping` and appears in no metric, `AuthController`'s
POSTs create tenants rather than rows inside one. So the rule does not try to know. It requires that
**someone decided, in writing, at the site** — `@RequiresEntitlement(...)` or
`@NotMetered(reason = "…")`, and a create carrying neither fails the build.

This is the same shape LLD #4 gave `@GlobalTable(reason)` for the same class of problem, which means
one idiom now covers both isolation exemptions and entitlement exemptions. That consistency is worth
more than either rule alone: a reviewer learns the pattern once.

---

# Part 1 — Where the code lives

```
platform/
└── platform-entitlement/
    ├── build.gradle.kts
    └── src/main/java/com/easycrm/platform/entitlement/
        ├── Metric.java                     the enum — QUOTATION, CUSTOMER, PRODUCT, …
        ├── RequiresEntitlement.java        @interface
        ├── NotMetered.java                 @interface — the explicit exemption
        ├── UsageCounter.java               SPI — the service implements it
        ├── EntitlementInterceptor.java     the guard
        ├── PlanLimitExceededException.java
        ├── PlanLimitAdvice.java            402 — and it CANNOT live in platform-web (ED3)
        └── EntitlementAutoConfiguration.java
```

Eight files, three of which are annotations. The module is small on purpose: it owns *whether a check
happened*, never *what the numbers are*.

## 1.1 Build file

```kotlin
dependencies {
    api(project(":platform:platform-tenancy"))    // reads the principal's entitlements
    api(project(":platform:platform-web"))        // its own @RestControllerAdvice — see ED3
    compileOnly("org.springframework:spring-webmvc")
}
```

Taken by **three** services — identity, master-data and sales, the ones with metered write paths.
`document-svc` and `notification-svc` do not take it, which is a claim this LLD has to defend, and
partly cannot: see EF2.

---

# Part 2 — Class model

## 2.1 The cycle this module would have caused, and the principle that prevents it

LLD #4 put `entitlements()` on the `TenantPrincipal` interface, so `platform-tenancy` names an
`Entitlements` type. If `Entitlements` is keyed by `Metric`, and `Metric` lives here, then
`platform-tenancy` → `platform-entitlement` → `platform-tenancy`. **The graph cycles**, for the third
time in six LLDs, and again through a type that looks too small to matter.

The fix is already written down, which is the interesting part. LLD #4's CF8 says of `Entitlements`:
*"Keep it opaque — a record the entitlement module reads and this module only carries."* That reads
like a style preference. It is not:

```java
// platform-tenancy — carries the data, names no Metric
public record Entitlements(String plan, Map<String, Integer> limits) {
    public static Entitlements unresolved() { … }        // LLD #5, OF9
}

// platform-entitlement — gives the data meaning
public enum Metric { QUOTATION, CUSTOMER, PRODUCT, PRICE_LIST, WHATSAPP_SEND, PDF_RENDER;
    public String key() { return name(); }
}
Integer limit = entitlements.limits().get(metric.key());
```

**EF1 — the same principle has now resolved three separate cycles, and is worth naming.** LLD #3 kept
`role` an opaque `String` because `Role` lives in `identity-svc`. LLD #4 kept `Entitlements` opaque
because `Metric` lives here. LLD #3's `VerifiedClaims` carries claims as data because
`platform-security` may name nothing at all. In every case: **a lower module carries the value; an
upper module supplies the meaning.** That is D12's "mechanisms, never meanings" restated as a
dependency rule, and it is what keeps a two-level DAG from becoming a knot.

## 2.2 The annotations

```java
@Retention(RUNTIME) @Target(METHOD)
public @interface RequiresEntitlement { Metric value(); }

@Retention(RUNTIME) @Target(METHOD)
public @interface NotMetered { String reason(); }
```

`reason` has no default, so the exemption cannot be taken silently. Whether the reason is *good* is a
review question and stays one — the same honest limit `@GlobalTable` has (**EF5**).

## 2.3 The guard, and where the count comes from

```java
class EntitlementInterceptor implements HandlerInterceptor {
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        Metric metric = metricOf(handler);                     // null → not annotated → pass
        if (metric == null) return true;

        Entitlements ent = TenantContext.get()
            .map(TenantPrincipal::entitlements).orElse(Entitlements.unresolved());
        Integer limit = ent.limits().get(metric.key());
        if (limit == null) return true;                        // unlimited on this plan

        long used = counters.count(metric);                    // ← the service's own schema
        if (used >= limit) throw new PlanLimitExceededException(metric, limit, used);
        return true;
    }
}
```

```java
/** Implemented by the service. platform owns whether a check happened, never what is counted. */
public interface UsageCounter { long count(Metric metric); }
```

**The SPI is D12's line, in the one place it is easiest to cross.** `sales` counts quotations with
`SELECT count(*) FROM quotation WHERE created_at >= :period_start` — RLS scopes it to the tenant, so
no `WHERE tenant_id` is written, per the standing agreement. A quotation is a meaning; the module
must not know one. Left in `platform`, this interface would have accumulated every metered table in
the product within two slices.

## 2.4 ED3 — the 402 cannot be mapped where every other status is

`ApiExceptionHandler` in `platform-web` maps all eight of the application's exception types. It cannot
map this one: LLD #2's **W2** says `platform-web` may depend only on `platform-primitives`, and the
DAG has entitlement → web, not the reverse. A 402 handler in `ApiExceptionHandler` would reverse the
only arrow that keeps the error vocabulary free of billing.

So `platform-entitlement` ships its own one-method `@RestControllerAdvice`:

```java
@RestControllerAdvice
class PlanLimitAdvice {
    @ExceptionHandler(PlanLimitExceededException.class)
    ResponseEntity<ApiError> handle(PlanLimitExceededException e) { … }   // 402
}
```

LLD #2's **W3** — exactly one advice per exception type — is satisfied: two advices exist, and no type
is claimed twice. This is the first case that proves W3 had to be phrased per *type* rather than
per *application*, which at the time looked like pedantry.

**402, not 403.** The request is well-formed and authorised; payment is what unblocks it. The body
carries `metric`, `limit`, `used` and an upgrade URL, because a limit error that does not say which
limit is a support ticket.

---

# Part 3 — How a service adopts it

```kotlin
dependencies { implementation(project(":platform:platform-entitlement")) }
```

One bean, and the annotations on the endpoints:

```java
@Component
class SalesUsageCounter implements UsageCounter {
    public long count(Metric metric) {
        return switch (metric) {
            case QUOTATION -> quotations.countCreatedSince(periodStart());
            default        -> 0L;
        };
    }
}
```

```java
@RequiresEntitlement(Metric.QUOTATION)
@PostMapping
QuotationResponse create(@RequestBody CreateQuotationRequest req) { … }

@NotMetered(reason = "contacts are unlimited on every tier; a contact is not a billable object")
@PostMapping
ContactResponse create(@RequestBody CreateContactRequest req) { … }
```

---

# Part 4 — What keeps it honest

**ER1 — every create endpoint declares its metering.**

```java
methods().that().areAnnotatedWith(PostMapping.class)
    .and().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
    .and(havePostMappingWithNoPath())          // a bare @PostMapping is the create idiom
    .should().beAnnotatedWith(RequiresEntitlement.class)
    .orShould().beAnnotatedWith(NotMetered.class)
    .because("nothing in bytecode says which endpoints are metered, so the rule requires "
           + "that someone decided in writing at the site. A create that ships with neither "
           + "annotation is a plan limit nobody enforces and nobody knows is missing.");
```

The predicate is `@PostMapping` **with no path** — the create idiom in all seven of this codebase's
current create endpoints, and the one that distinguishes them from `POST /{id}/dispatch` and the
eleven other transition endpoints, which are not creates and are not metered.

**ER2 — `Metric` is named only inside this module and in `@RequiresEntitlement` arguments.** A service
that imports `Metric` to do its own arithmetic has begun re-implementing the guard.

**ER3 — `platform-entitlement` may not be named by `platform-tenancy` or `platform-web`.** The
mechanical form of 2.1. Of the six modules this is the only one nothing else may depend on, which is
what makes it safe for it to change every time pricing does.

---

# Part 5 — Test plan

There is nothing to test today — the module is built in sub-project 10. What follows is the plan that
ships with it.

| Test | Kind | Asserts |
|---|---|---|
| A create at `used == limit` → **402**, with `metric`, `limit`, `used` in the body | slice | the contract, including the fields that keep it out of support |
| A create at `used == limit - 1` → 201 | slice | the boundary is `>=`, not `>` |
| No limit for the metric → passes | unit | absent means unlimited, never means zero — EB1 |
| `Entitlements.unresolved()` → passes, and **logs** | unit | LLD #5's OF9 — a consumer-restored context must not silently read as "no entitlements" |
| `@NotMetered` endpoint → guard never consulted | slice | the exemption is real, not decorative |
| Two concurrent creates at `limit - 1` → **both succeed** | integration | ED1 stated as a test, so the overshoot is a recorded property rather than a latent surprise |
| ER1 fails against a bare `@PostMapping` with neither annotation | ArchUnit, negative | write the violation first — a rule that never goes red is not a rule |
| `PlanLimitExceededException` is not claimed by `ApiExceptionHandler` | ArchUnit / advice test | W3, and ED3's arrow |

---

# Part 6 — Bugs you will hit

| # | Bug | Why it happens | Fix |
|---|---|---|---|
| **EB1** | A tenant on an unlimited plan is blocked at zero | `limits.get(metric)` returns null and null is read as 0 | Absent means unlimited. Explicit null check, and the unit test above |
| **EB2** | Every tenant is blocked the moment the module ships | A service registers the interceptor before any `UsageCounter` exists, or one whose `default` branch returns the limit rather than the count | `@ConditionalOnBean(UsageCounter.class)`, and `default -> 0L` |
| **EB3** | Limits stop applying entirely and nothing fails | The JWT stops carrying `limits` — a claim-set change, an issuer swap — so every lookup returns null and every check passes. **Fails open, silently** | The counterpart of LLD #3's SB5: a claim nothing checks is a comment. Assert `limits` present in the minted token in `identity-svc`'s own tests |
| **EB4** | A tenant is blocked for 15 minutes after upgrading | Entitlements live in the JWT, so they are stale until the token refreshes (B7) | The upgrade flow forces a refresh; the product spec already requires it. Worth a test in sub-project 10 |
| **EB5** | The overshoot in ED1 is later mistaken for a bug and "fixed" with a lock on every metered write | The property is not written down anywhere a future reader looks | It is written down here, and asserted by the concurrent-create test |

---

# Appendix A — Findings

| # | Finding | Severity |
|---|---|---|
| **EF1** | **A third dependency cycle, prevented by the same principle as the first two.** `Entitlements` in `platform-tenancy` keyed by a `Metric` that lives here would cycle the graph. LLD #4's CF8 — "keep it opaque" — turns out to be load-bearing rather than stylistic, exactly as LLD #3's SD1 was for `role` | Recorded as the general rule: **a lower module carries the value, an upper module supplies the meaning.** Worth adding to the parent's Part 0 as the thing P2 actually protects |
| **EF2** | **The metric set does not respect the create/read boundary that B9 asserts.** `PDF_RENDER` is incurred by `GET /api/v1/quotations/{id}/pdf`; `POST /{id}/share` mints a link that renders forever; `POST /{id}/revise` is a create that meters nothing. B9 says limits are enforced on create and never on read, and the metric set does not fit that | ER1 covers creates only, so **every read-incurred metric is outside the rule by construction**. Either those metrics are soft COGS-protection only (which the product spec half-says: "never acceptable for a limit that gates a paid feature"), or they need a different mechanism. **A decision for sub-project 10, flagged now so it is not discovered by an unmetered bill** |
| **EF3** | **`/public/q/{token}` renders a PDF with no JWT, so no claims, so no entitlements.** The application's only unauthenticated route is also its most expensive operation, and it structurally cannot carry an entitlement check — there is nowhere for the tenant's limits to come from | Not solvable by this module at any cost. It is a **rate-limiting** problem, and it is already on the backlog (item 3) and in the parent handoff's §8 as a candidate to pull forward. This LLD is the second independent argument for doing so |
| **EF4** | ED1's overshoot is acceptable for a *commercial* limit and would not be for a *correctness* one. The distinction is easy to lose once the annotation is just something you put on endpoints | Stated in Part 0 and asserted by a test. If a future metric ever gates something that must not be exceeded — a compliance cap, a hard seat ceiling under an RBI mandate ceiling (BF3) — it is a different problem and needs challenge #26's treatment |
| **EF5** | `@NotMetered(reason)` forces a reason to be written, never a *good* one. Same honest limit as `@GlobalTable(reason)` and as PF6 before it | Accepted. Three review rules now share this shape; the parent's PF6 should note that it has company rather than being treated as a one-off |
| **EF6** | Three services take this module and two do not, but `document-svc` renders the PDFs that `PDF_RENDER` counts and `notification-svc` sends the WhatsApp messages that `WHATSAPP_SEND` counts. **Both un-taken services own a metered action** | The product spec's answer is that these are counted-not-derived metrics, reported onward by event and enforced softly. That is coherent, but it means the per-service import table in the parent (§1) is describing *enforcement*, not *metering*, and does not say so |

---

# Appendix B — To verify before implementation

1. **That `HandlerInterceptor.preHandle` throwing is routed through `@RestControllerAdvice`** in Spring
   6/Boot 4 — an exception from an interceptor does not always reach the same handler chain as one
   from a controller method. If it does not, the interceptor writes the 402 itself and `PlanLimitAdvice`
   is unnecessary. This is the only structural assumption in the module.
2. **That ArchUnit can express "a `@PostMapping` with no path attribute"** (ER1's predicate). If the
   annotation-parameter inspection is awkward, the fallback is a naming convention on the method,
   which is weaker and should be resisted before it is accepted.
3. **Whether `@ConditionalOnMissingBean`/`@ConditionalOnBean` ordering makes EB2 reliably safe** when
   the `UsageCounter` is defined in a service's own `@Configuration` rather than an auto-configuration
   — condition evaluation order across the two is the failure mode.
4. **That two `@RestControllerAdvice` beans coexist** with no `@Order` and no ambiguity, given neither
   claims a type the other does (W3). Worth proving once rather than assuming, since the failure is
   silent: the loser's mapping simply never fires (WF4).
