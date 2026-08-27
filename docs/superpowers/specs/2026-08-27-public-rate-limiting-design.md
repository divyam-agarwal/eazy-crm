# EasyCRM P1 — Rate Limiting Design (`/public/q/*` + auth routes, per-IP token bucket)

**Status:** Design approved, pre-implementation
**Date:** 2026-08-27
**Parent spec:** `2026-07-22-easycrm-design.md` (§3 security: "rate-limited login (Bucket4j + Redis)")
**Target architecture:** `../../architecture/2026-07-29-target-architecture.md` (edge rate limiter,
`platform/ratelimit`, "strictest bucket on `/public/q/*`", 429 + `Retry-After`)
**Depends on:** everything merged on `main` through `95e119c` (quotation PDF/share provides the
route being limited; `rls-force-and-guard` is unrelated but immediately prior)

---

## 1. Context & purpose

`GET /public/q/{token}` is the application's only route reachable without a JWT, and it is also the
most expensive thing the application does per request: **every hit renders a PDF from scratch**
(`QuotationPdfService`, real Thymeleaf → openhtmltopdf work, inside a read-only transaction). Nothing
caps it. Two independent arguments arrive at the same fix:

1. **Security** (backlog item #3). The 128-bit share token is not guessable, but nothing prevents an
   attacker from hammering the route — either scanning the token space or simply replaying one known
   link. There is no cost to the attacker and a real CPU cost to us per attempt.
2. **Billing / COGS** (finding **PF19**, `../../architecture/2026-08-27-platform-llds-handoff.md` §3).
   The route has no JWT, therefore no tenant, therefore *structurally nowhere* to hang an entitlement
   check. The most expensive metered operation in the product is the one place a plan limit cannot be
   applied. A per-IP cap is not an entitlement check, but it is the only ceiling available today.

The auth routes (`/api/v1/auth/login`, `signup`, `refresh`, `logout`) are `permitAll` and equally
uncapped. The parent design spec §3 already specifies rate-limited login; credential stuffing is the
threat there. Since the mechanism is the expensive part and a second policy is a YAML entry, both
surfaces are closed in this slice.

**What this is not.** This is an application-level limiter for a single-instance deployment. It is
not the edge limiter of the target architecture, and it does not pretend to be — see §8.

## 2. Scope

**In scope**

- A per-IP token-bucket limiter running ahead of Spring Security, covering `/public/q/*` and the four
  auth routes under two independently configured policies.
- `429 Too Many Requests` with `Retry-After` and the house error envelope.
- Limits expressed as configuration, not code.
- Bucket storage behind a port, with a Bucket4j in-memory implementation and bounded memory.

**Out of scope** (§8 restates these as explicit non-goals)

- Redis / multi-instance correctness.
- Per-tenant or per-plan entitlement metering (PF19's other half).
- Share-link expiry and revoke (backlog item #4).
- Limiting `/api/**`, which no frontend exists to characterise yet.

## 3. Keying decision — per IP, not per token

**Per IP only.** This is the load-bearing product decision in the slice, and the obvious alternative
is wrong for this product.

A quotation share link is *designed* to be forwarded: the distributor WhatsApps it to a buyer, who
forwards it to a purchase team. One token is legitimately opened by many people from many IPs. So:

- A **per-token** bucket does not stop the threat. An attacker scanning the token space presents a
  *different* token on every request, so a per-token bucket sees exactly one request per bucket and
  never fires.
- A **per-token** bucket does hurt real users. It is the one configuration that can 429 a customer
  opening a link a colleague forwarded — and the failure is invisible to the distributor, who simply
  hears that the buyer never saw the quotation.
- A **per-IP** bucket stops both the scan and the replay, and cannot be tripped by ordinary
  forwarding, because each recipient brings their own address.

The target architecture's "per IP + per token" wording is therefore narrowed here deliberately, and
this section is the record of why. If per-token is ever revisited, it should be as a *generous*
abuse ceiling (dozens per hour), never as a tight bucket.

## 4. Architecture

### 4.1 Position in the filter chain

A `OncePerRequestFilter` registered **before** `SecurityFilterChain`, via an explicit
`FilterRegistrationBean` order (or `@Order` ahead of Spring Security's registration).

Ordering is a correctness requirement, not a preference:

- The purpose is to cap work *before* it is done. Behind the security chain, the limiter would run
  after authentication has already spent effort.
- For the auth routes specifically, the traffic being limited is traffic that **fails** authentication.
  A limiter positioned after Spring Security would never see a credential-stuffing attempt, because
  each attempt short-circuits to 401 first.

The consequence is that the filter runs with no `Authentication` and no `TenantContext`, so it can
key on nothing but the client address. That constraint agrees exactly with §3's decision rather than
fighting it.

### 4.2 Components — `com.easycrm.platform.ratelimit`

A package, **not** a Gradle module. `platform-web` (LLD #2) is unbuilt; inventing module 2's boundary
as a side effect of this slice would front-run the module queue and pre-commit a decision that
handoff says should be made deliberately. The package name matches the target architecture's
`ratelimit/` so the eventual extraction is a move, not a redesign.

| Type | Responsibility | Depends on |
|---|---|---|
| `RateLimitPolicy` | Value type: name, path pattern, capacity, refill period. | nothing |
| `RateLimitProperties` | `@ConfigurationProperties("easycrm.rate-limit")`, validated; holds `enabled` + the policy list. | Spring Boot config |
| `RateLimitStore` | **The port.** `Decision tryConsume(String key, RateLimitPolicy policy)` where `Decision` carries `allowed` and `nanosToWaitForRefill`. | nothing |
| `InMemoryRateLimitStore` | Bucket4j buckets in a bounded Caffeine cache. The only class Redis replaces. | Bucket4j, Caffeine |
| `RateLimitFilter` | Match path → policy, resolve client IP, consume, write 429 or continue. | the above + `ObjectMapper` |

Each is independently testable: the filter can be exercised against a fake `RateLimitStore`, and the
store against synthetic policies with no HTTP involved.

### 4.3 Bucket storage and eviction

Bucket4j `com.bucket4j:bucket4j_jdk17-core:8.19.0` — the artifact is JDK-qualified as of 8.10; the
older `com.bucket4j:bucket4j-core` coordinate is stale. Java-25 resolution is verified as the plan's
first task rather than assumed.

**Eviction is a security requirement, not housekeeping.** The map key is the client IP, which is
attacker-controlled. A plain `ConcurrentHashMap` grows without bound as an attacker rotates source
addresses, so the naive rate limiter becomes its own memory-exhaustion vector — the component added
to prevent resource abuse becomes the resource abuse. `InMemoryRateLimitStore` therefore holds
buckets in a Caffeine cache (`maximumSize` plus `expireAfterAccess` at twice the policy's refill
period). Caffeine is version-managed by the Spring Boot BOM, so nothing new is pinned.

Evicting a bucket is safe because an evicted bucket and a fully-refilled idle bucket are
indistinguishable to a caller: a client whose bucket is dropped after twice the refill window would
have had a full allowance anyway. Eviction can only ever be generous, never punitive.

### 4.4 Client IP resolution — and the trap

The filter uses `HttpServletRequest.getRemoteAddr()` and **does not read `X-Forwarded-For` itself.**

Reading that header directly would be the subtly wrong thing that passes every test: it is
client-supplied, so any attacker could vary it per request, receive a fresh bucket each time, and
render the limiter decorative — while a test that sets one `X-Forwarded-For` and loops would still
observe a 429 and go green.

Behind a genuine proxy the correct mechanism is Spring's own
`server.forward-headers-strategy: framework`, which installs `ForwardedHeaderFilter` so
`getRemoteAddr()` returns the forwarded client address for *every* consumer, not just this one. That
property is a deployment statement — "a trusted proxy is in front of me" — and belongs to whoever
operates the deployment. Today nothing trusted is in front (ngrok in dev terminates to localhost), so
the default stays off and is documented in `application.yml` beside the existing `public-base-url`
comment.

A test pins the safe behaviour: two requests carrying *different* `X-Forwarded-For` values share one
bucket under the default configuration.

### 4.5 Response contract

```
HTTP/1.1 429 Too Many Requests
Retry-After: 37
Content-Type: application/json

{"error":{"code":"RATE_LIMITED","message":"too many requests; please retry shortly"}}
```

`Retry-After` is whole seconds, rounded up from Bucket4j's nanos-to-refill (a floor could round to
`0` and invite an immediate retry).

`ApiExceptionHandler` **cannot** serve this response: an exception thrown from a servlet filter never
reaches `@RestControllerAdvice`. The envelope is therefore constructed in the filter, through the
injected `ObjectMapper`, and this is an acknowledged duplication of the error contract in exactly one
place. A test asserts the 429 body has the same `error.code` / `error.message` shape the handler
produces, so the two cannot drift silently.

No `X-RateLimit-*` headers: nothing consumes them, and they advertise the limit to an attacker.

## 5. Policies and starting values

Configuration, `application.yml`:

```yaml
easycrm:
  rate-limit:
    enabled: true
    policies:
      - name: public-share
        path: /public/q/*
        capacity: 60
        refill-period: 1h
      - name: auth
        path: /api/v1/auth/**
        capacity: 30
        refill-period: 1m
```

`public-share` uses `/public/q/*`, character-for-character the pattern `SecurityConfig` already
permits, so the limited set and the publicly-reachable set are the same set by construction. A path
with an extra segment (`/public/q/a/b`) is deliberately *not* limited: it never resolves to this
route, is rejected at the security chain with a 401 (backlog item #19), and renders no PDF, so it
carries none of the cost this policy exists to cap.

**These numbers are starting guesses and are labelled as such.** No frontend exists and no production
traffic has ever been observed, so any figure is a judgement call; what matters is that they are
config, tunable without a release.

- **`public-share` — 60/hour per IP.** A real recipient opens a link a handful of times. Sixty leaves
  large headroom for an office NAT while capping PDF renders per address at a rate that makes both
  token scanning and render-cost abuse pointless.
- **`auth` — 30/minute per IP.** Well above any real office's combined login and 15-minute refresh
  traffic, and far below what makes credential stuffing worthwhile.

**Unmatched paths are unlimited.** `/actuator/health` and all of `/api/**` are untouched, so ALB
health checks and the future frontend cannot be throttled by a limit nobody sized for them.

### 5.1 The limiter must default to OFF in the test harness

`IntegrationTest` registers `easycrm.rate-limit.enabled=false`; only the rate-limit tests turn it on,
with their own tiny limits. This is not tidiness — leaving it on would make the existing suite
intermittently red for a reason almost impossible to diagnose from the failure.

Every `@SpringBootTest` sharing a configuration also shares one cached application context, hence one
`RateLimitStore` bean. Every MockMvc request in the whole suite originates from the same loopback
address. So all auth-touching requests across *all* test classes accumulate into a single bucket, and
the `auth` policy's 30-per-minute allowance is measured against the aggregate of a suite that runs in
about twelve seconds. The result would be a test failing because of how many *other* tests ran before
it — order-dependent, machine-speed-dependent, and green on a re-run of that class alone.

The production default remains `enabled: true`, and one test asserts that default explicitly, so
"off in tests" cannot quietly become "off everywhere".

## 6. Testing summary

| Level | Test | What would break without it |
|---|---|---|
| Unit | Path → policy matching, including no-match | A typo'd pattern silently limiting nothing |
| Unit | `Retry-After` rounds up, never to 0 | A 429 inviting an instant retry |
| Store | Capacity exhausts, then refills after the period | The bucket never actually refilling |
| Store | Distinct keys hold distinct buckets | All clients sharing one global bucket |
| Integration | N+1th request to `/public/q/{token}` → 429, `Retry-After` present, envelope matches | The feature not working at all |
| Integration | Different `X-Forwarded-For`, same socket → one bucket | Trivially spoofable limiter that still looks green |
| Integration | Drained bucket on a **protected** route returns **429, not 401** | The filter slipping behind Spring Security |
| Integration | An unmatched path is never limited | Collateral throttling of `/api/**` or health |
| Config | `enabled` defaults to `true` outside the test harness | §5.1's test-only disable silently becoming a production disable |

Integration tests inject tiny limits (`capacity: 2`) via `@TestPropertySource` so no test loops sixty
times. The ordering test is this slice's equivalent of the previous slice's probe table: it is the
one assertion that fails if the mechanism is installed in the wrong place, which is the failure mode
that otherwise looks identical to success.

## 7. Documentation obligations (same change, per CLAUDE.md)

- **`engineering-challenges.md`** — at least one entry is warranted. The strongest candidate is the
  pair of inversions in §4.3/§4.4: a rate limiter keyed on attacker-controlled input becomes a memory
  DoS unless bounded, and a limiter that reads `X-Forwarded-For` to be "more accurate" becomes
  bypassable by the very clients it limits — both being changes that make the component *look* more
  correct while removing its protection, and both invisible to an obvious test.
- **`annotations-reference.md`** — add rows for any annotation new to the repo; expected candidates
  are `@ConfigurationProperties`, `@EnableConfigurationProperties` and `@TestPropertySource` if not
  already present.
- **`HANDOFF.md`** — record the slice, the new baseline test count, and strike PF19's app-side half
  from §8 while leaving the entitlement half open.
- **`good-repos` catalog** — add a Bucket4j entry; the catalog was searched during design and had no
  match (only `resilience4j`, whose limiter is a downstream-call throttle with no keyed buckets and
  no distributed backend).

## 8. Out-of-scope recap (do not build)

1. **Redis / multi-instance.** With N application instances the effective limit is N× the configured
   value, because each holds its own buckets. This is correct-enough at N=1, which is today's
   deployment, and it is the reason `RateLimitStore` is a port. The AWS re-platform must swap in the
   Redis implementation *before* running more than one task, and this constraint is to be stated in
   the handoff rather than left for someone to discover.
2. **Entitlement metering.** PF19's other half — plan-aware limits per tenant — needs an identity the
   public route does not have. Unchanged by this slice.
3. **Share-link expiry / revoke** (backlog #4). A minted link still renders forever; rate limiting
   caps the *rate* of abuse of a leaked link, not its lifetime. The two are complementary and the
   distinction should not be blurred in the handoff.
4. **Per-token buckets.** Rejected in §3 with reasons; not to be reintroduced without revisiting them.
5. **`/api/**` limits.** Deferred until a frontend exists that can state its request patterns.
