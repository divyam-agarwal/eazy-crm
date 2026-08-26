# `platform-web` — Low-Level Design

**Date:** 2026-08-26
**Status:** Design only. Zero code changed.
**Code baseline:** `80e74a3`
**Parent:** [`../superpowers/specs/2026-08-26-shared-platform-modules-design.md`](../superpowers/specs/2026-08-26-shared-platform-modules-design.md)
**Depends on:** [`2026-08-26-platform-primitives-lld.md`](2026-08-26-platform-primitives-lld.md) — LLD #1
**LLD #2 of 6.**

---

# Part 0 — What changed from the parent spec

The parent scoped this module as `ApiExceptionHandler` + `PageResponse` and posed one open question:
where the 503-for-unavailable-downstream mapping lives (PF5). Answering it found something larger.

**There is no outbound HTTP client anywhere in the codebase.** No `RestTemplate`, no `WebClient`, no
`RestClient`, no Feign — the monolith has never made a call to itself. The parent's six-module plan
inherits that gap: nothing owns cross-service HTTP, while §2.1 of the parent depends on `sales`
calling `master-data` on every quotation write.

PF5 is therefore two questions, and the 503 mapping is the easy half:

| Question | Answer |
|---|---|
| Who *distinguishes* "downstream said 404" from "downstream is unreachable"? | Only the calling code can. A 404 from `GET /internal/customers/{id}` means the customer does not exist → 422. A timeout, 5xx or DNS failure means master-data is down → 503 |
| Who *maps* the distinction onto a status code? | `ApiExceptionHandler`, trivially, once a type exists to map |

**Resolution.** `platform-web` widens from "inbound HTTP" to **this service's HTTP surface in both
directions**, and owns the *mechanism* only:

- inbound — `ApiExceptionHandler`, `PageResponse`
- outbound — the `RestClient` configuration, the error translator, header propagation, and the
  timeout budget

The **typed client stays in the calling service.** `MasterDataClient.getCustomer(id) → CustomerRef`
knows what a customer is, and a customer is a meaning. D12's rule makes the cut, exactly as it does
everywhere else: `platform` may contain mechanisms, never meanings.

Rejected: a seventh `platform-client` module (reopens P2 for one consumer), and letting `sales` own
the whole thing (which is what P5 literally prescribes for a one-consumer mechanism, but the rule
being re-derived by the second caller is how master-data being down starts reading as "customer not
found" — and that produces a quotation with the wrong tax split).

**Name.** `platform-web` is kept. It is the service's HTTP surface; the direction of travel does not
change what it is.

---

# Part 1 — Where the code lives

```
platform/
└── platform-web/
    ├── build.gradle.kts
    └── src/main/java/com/easycrm/platform/web/
        ├── ApiExceptionHandler.java        moved from platform/error/, unchanged behaviour
        ├── PageResponse.java               unchanged
        ├── WebAutoConfiguration.java       NEW — replaces component scan
        └── client/                         NEW — the outbound mechanism
            ├── DownstreamUnavailableException.java
            ├── DownstreamErrorTranslator.java     ClientHttpRequestInterceptor
            ├── ContextPropagatingInterceptor.java  Authorization + traceparent
            ├── InternalClients.java                RestClient.Builder factory
            └── InternalClientProperties.java       base URLs, timeouts
```

`ApiExceptionHandler` moves package from `com.easycrm.platform.error` to
`com.easycrm.platform.web`. This is the **only** package move in the whole platform split, and it is
forced: the five exception types stay in `platform.error` inside `platform-primitives` (LLD #1), and
one package cannot span two Gradle modules.

## 1.1 Build file

```kotlin
dependencies {
    api(project(":platform:platform-primitives"))
    compileOnly("org.springframework:spring-web")
    compileOnly("org.springframework.data:spring-data-commons")
}
```

`api` on primitives: a consumer catching `NotFoundException` sees the type through this module.

`spring-data-commons`, not `spring-data-jpa` — `PageResponse.of` takes
`org.springframework.data.domain.Page`, which lives in commons. The distinction matters because it
keeps a persistence *engine* out of a web module while still accepting the de-facto pagination type.
All four consumers already have it.

`compileOnly` throughout, with `@ConditionalOnClass` guards, per P8. `notification-svc` does not take
this module at all, but the guards mean nothing breaks if it ever does.

---

# Part 2 — Class model

## 2.1 `ApiExceptionHandler` — inbound

Behaviour unchanged. Eight handlers, producing one envelope:

```json
{ "error": { "code": "NOT_FOUND", "message": "…", "fields": { … } } }
```

Two mappings are load-bearing rather than cosmetic, and both must survive the split untouched:

- **404, never 403, for a cross-tenant read.** A 403 confirms the row exists. Nine test classes
  assert this today.
- **409 backstops** for `DataIntegrityViolationException` and `OptimisticLockingFailureException`.
  The latter needs its own handler because `ObjectOptimisticLockingFailureException` does **not**
  extend `DataIntegrityViolationException` — a subtlety that is easy to lose in a package move.

**One handler is added:** `DownstreamUnavailableException` → **503**, with a `Retry-After` header.

`@RestControllerAdvice` composes, so a service may add its own advice for service-specific types
without replacing this one. Ordering is only relevant if two advices claim the same exception type,
which an ArchUnit rule could forbid but does not yet — see WF4.

## 2.2 `PageResponse`

Unchanged. A stable list envelope, so Spring's `PageImpl` is never serialised directly — its JSON
shape is not a contract Spring maintains.

```java
public record PageResponse<T>(List<T> content, int page, int size,
                              long totalElements, int totalPages) {}
```

Note what it does *not* do: `totalElements` is a `long` and serialises as a JSON **number**. That is
correct and deliberate — it is a count, not a quantity. LLD #1's rejection of
`WRITE_NUMBERS_AS_STRINGS` turns on exactly this field.

## 2.3 The outbound mechanism

**This is the module's real design decision**, and the split is between mechanism and meaning.

```
sales-svc
  MasterDataClient                     ← in sales. Knows what a customer is.
      │  RestClient built by InternalClients
      ▼
  ContextPropagatingInterceptor        ← platform-web. Authorization, traceparent.
  DownstreamErrorTranslator            ← platform-web. Failure → typed exception.
      │
      ▼  ECS Service Connect (Envoy sidecar) — retries, outlier ejection
  master-data-svc  /internal/customers/{id}
```

### The translation table

This is the part that must not be re-derived per service:

| Downstream outcome | Translated to | Surfaces as |
|---|---|---|
| `200` | the response body | — |
| `404` | `NotFoundException` | **422** at the caller, per parent §3.4 — "customer not found" |
| `409` | `ConflictException` | 409 |
| `4xx` other | `DownstreamUnavailableException` | 503 — a 400 from an internal call is *our* bug, not the user's |
| `5xx` | `DownstreamUnavailableException` | 503 |
| connect timeout, read timeout, DNS failure, connection refused | `DownstreamUnavailableException` | 503 |

The 404 row is the one everybody gets wrong in the other direction. A missing customer is a real,
expected outcome and must reach the user as a validation failure. An unreachable master-data must
never wear that costume — if it does, `sales` proceeds as though the customer simply is not there,
and the parent doc's rule that "a quotation with the wrong tax split is worse than no quotation"
is silently violated.

### Tenant propagation

The internal call **forwards the caller's `Authorization` header**, unchanged.

This is not a convenience. The project's first isolation layer is "tenant comes from the JWT only,
never a header, query param or client-settable subdomain." A service-to-service token with a tenant
header would break that rule at exactly the moment there are five services to break it in.
`master-data` validates the forwarded JWT against JWKS the same way it validates a browser's, and
`platform-tenancy`'s filter installs the same `TenantPrincipal`. Nothing new is trusted.

`traceparent` is forwarded alongside it, so a trace spans the hop. The outbox breaks trace continuity
separately (parent F8) and is not this module's problem.

### The timeout budget — a gap in the parent's ladder

The parent orders timeouts outward so the innermost failure is the one reported:

```
client               60 s
CloudFront origin    30 s
ALB idle             25 s
task request         20 s
```

**The ladder stops at the task boundary.** Once `sales` calls `master-data`, there is a second hop
*inside* that 20 s, and nothing specifies its budget. Give the internal call the same 20 s and
`sales` has none left for its own work — both time out at the same instant, and the failure is
attributable to neither.

The ladder must extend inward:

```
task request         20 s      ← sales' own budget
  internal call       3 s      ← connect 1 s + read 2 s
  remaining          17 s      ← GST calc, numbering, write, commit
```

Three seconds is a starting number, not a measured one: a `/internal/customers/{id}` read is a
single indexed lookup on a warm connection through RDS Proxy. It must be measured before it is
fixed. See WF1.

**Envoy also applies a timeout**, configured on the Service Connect service. If the two disagree, the
shorter wins and the longer is decoration — so they are set from one source and asserted in a test,
not maintained in two places by hand.

### Retries — the multiplicative trap

D6 chose ECS Service Connect precisely so retries, timeouts and outlier ejection are task-definition
config rather than code repeated in five services. **The Java client therefore does not retry.**

If `InternalClients` adds a retry policy of 3 and Envoy is configured for 3, one call becomes nine,
each waiting the full timeout — a slow downstream turns into a self-inflicted outage well before it
recovers. The interceptor translates; it never retries. See WF2.

Retrying at all is only safe because everything under `/internal/*` is a `GET`. That is an assumption
the parent's Appendix B already lists for verification, and it is now load-bearing here too.

---

# Part 3 — How a service adopts it

```kotlin
dependencies { implementation(project(":platform:platform-web")) }
```

```yaml
easycrm.internal-clients:
  master-data:
    base-url: http://master-data.easycrm.local     # Service Connect DNS
    connect-timeout: 1s
    read-timeout: 2s
```

The service then writes its own typed client, which is the only part that names a domain concept:

```java
@Component
public class MasterDataClient {

    private final RestClient http;

    MasterDataClient(InternalClients clients) {
        this.http = clients.forService("master-data");   // interceptors already attached
    }

    public CustomerRef getCustomer(UUID id) {
        return http.get().uri("/internal/customers/{id}", id)
                   .retrieve().body(CustomerRef.class);
    }
}
```

No `try`/`catch`. `DownstreamErrorTranslator` has already turned every failure mode into a typed
exception, and `ApiExceptionHandler` has already mapped every typed exception to a status. A service
that writes its own error handling here is doing something wrong.

---

# Part 4 — What keeps it honest

**W1 — no service may build its own HTTP client.**

```java
noClasses().that().resideOutsideOfPackage("com.easycrm.platform.web.client..")
    .should().callMethod(RestClient.class, "create")
    .orShould().callMethod(RestClient.class, "builder")
    .because("a client built elsewhere skips DownstreamErrorTranslator, so an unreachable "
           + "master-data surfaces as a 500 or, worse, as a 404 that reads like "
           + "'customer not found'. Use InternalClients.forService(...).");
```

The same shape as LLD #1's R1: the dangerous thing is unavailable, so the safe thing cannot be
forgotten.

**W2 — `platform-web` may depend only on `platform-primitives`.** No tenancy, no outbox, no service
package. If the error envelope ever needs a tenant id, something is wrong with the envelope.

**W3 — exactly one `@RestControllerAdvice` may handle any given exception type.** Not yet
expressible as written — see WF4.

---

# Part 5 — Test plan

## 5.1 What exists today

The module has **no tests of its own**, and is nonetheless heavily covered: 24 test classes assert
its status mappings and 9 assert the cross-tenant 404 rule, all through service endpoints. Error
codes are asserted by value (`$.error.code` = `VALIDATION_FAILED`, `CONFLICT`), so the envelope shape
is pinned.

That is good coverage of *behaviour* and no coverage of the *contract*. A change to the envelope
breaks twenty-four unrelated tests with confusing messages, and nothing states the envelope as a
thing in its own right.

## 5.2 What this design adds

| Test | Kind | Asserts |
|---|---|---|
| Envelope shape, one test per handler | unit (`@WebMvcTest` + a throwing stub controller) | the eight existing mappings, stated once, independent of any domain endpoint |
| `OptimisticLockingFailureException` → 409 | unit | the sibling that does not extend `DataIntegrityViolationException` |
| `DownstreamUnavailableException` → 503 + `Retry-After` | unit | the new handler |
| Downstream **404** → `NotFoundException` → **422** | integration (MockWebServer) | **the most important test in this module** — the failure mode is a wrong tax split |
| Downstream 500, connect timeout, read timeout, connection refused → 503 | integration (MockWebServer) | each row of the translation table |
| The client does **not** retry | integration (MockWebServer, request count == 1) | WF2 |
| `Authorization` and `traceparent` are forwarded | integration (MockWebServer, header capture) | tenant propagation |
| Read timeout is well inside the task budget | unit on config | the inward ladder |

`PageResponse` needs no test beyond the twenty-four that already serialise it.

## 5.3 Not testable here

Whether Envoy's timeout agrees with the client's. That is Terraform's job and an integration concern
of sub-project 2; this module can only assert its own half.

---

# Part 6 — Bugs you will hit

| # | Bug | Why it happens | Fix |
|---|---|---|---|
| **WB1** | An unreachable `master-data` surfaces as "customer not found", and the quotation is written with the wrong tax split | The obvious `catch (Exception e) { throw new NotFoundException(...) }` in a hand-written client | W1 makes the hand-written client impossible; the translation table makes the distinction explicit |
| **WB2** | A slow downstream becomes an outage | Client retries multiply with Envoy's | The interceptor never retries (2.3) |
| **WB3** | Both services time out simultaneously and the failure is attributable to neither | The internal call inherits the 20 s task budget | The inward ladder (2.3) |
| **WB4** | `ObjectOptimisticLockingFailureException` starts returning 500 | Someone consolidates the two 409 handlers during the package move, assuming one extends the other | It does not. Keep both |
| **WB5** | The error envelope changes shape and twenty-four unrelated tests fail | No test owns the contract | 5.2's envelope tests |
| **WB6** | An internal call succeeds with no tenant and returns another tenant's row | The `Authorization` header is dropped and `master-data` falls back to `NO_TENANT` | It cannot return another tenant's row — `NO_TENANT` matches nothing, so it returns zero rows and reads as 404. Silent, but not a leak. Still: assert header forwarding |

---

# Appendix A — Findings

| # | Finding | Severity |
|---|---|---|
| **WF1** | The 3 s internal-call budget is invented, not measured. Too tight and a warm-cache lookup under load starts throwing 503s; too loose and it eats the caller's own budget | **Measure before fixing.** Take p99 of the equivalent in-process repository call today as the floor |
| **WF2** | Envoy's retry policy and the client's must be reasoned about together, and they live in different repositories' worth of config (Terraform vs. YAML). Nothing makes a disagreement visible | Client never retries, stated here and enforced by test. Envoy's policy documented alongside the timeout in one Terraform module |
| **WF3** | The parent's timeout ladder (R9) stops at the task boundary and does not cover service-to-service hops. This LLD extends it inward, which is a change to a parent-doc decision | Fold the inward ladder into the parent's R9 section when this LLD is accepted |
| **WF4** | W3 — "exactly one advice per exception type" — has no ArchUnit form. Two advices claiming the same type resolve by `@Order`, silently, and the loser's mapping simply never fires | Accepted as a review rule, like PF6. A test that boots each service and asserts the resolved handler set is possible but heavy |
| **WF5** | `ApiExceptionHandler` moves package (`platform.error` → `platform.web`). It is the only package move in the split, and it is invisible at runtime — a missed import shows up as an unhandled exception returning 500, not as a compile error, if any service has its own advice shadowing it | Covered by 5.2's envelope tests, which is the reason to write them before the move rather than after |
| **WF6** | Forwarding the end-user JWT means an internal call inherits the user's token TTL. A long-running caller could hold a token that expires mid-call chain | Not reachable inside a 3 s budget. Revisit only if an internal call ever becomes asynchronous |

---

# Appendix B — To verify before implementation

1. **That `RestClient` interceptors see connect/read timeouts as exceptions** rather than as
   responses, so `DownstreamErrorTranslator` can catch them. If not, the translation must move to a
   wrapping layer rather than an interceptor.
2. **Whether Spring Boot 4 still ships `RestClient`** under the same coordinates, and which Boot 4
   per-integration module carries its auto-configuration — the same question LLD #1 has open for
   Jackson.
3. **Envoy's default timeout and retry policy under ECS Service Connect**, so the numbers in 2.3 are
   set against real defaults rather than assumed ones.
4. **That `spring-data-commons` alone provides `org.springframework.data.domain.Page`** without
   dragging a persistence engine, as 1.1 assumes.
5. **Whether `@RestControllerAdvice` in an auto-configuration is discovered** the same way a
   component-scanned one is — LLD #1's open question 2, in a different guise, and with the same
   silent failure mode.
