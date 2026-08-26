# `platform-security` — Low-Level Design

**Date:** 2026-08-26
**Status:** Design only. Zero code changed.
**Code baseline:** `80e74a3` — `platform/security` is 153 lines of Java across 5 files, 113 lines of test across 4
**Parent:** [`../superpowers/specs/2026-08-26-shared-platform-modules-design.md`](../superpowers/specs/2026-08-26-shared-platform-modules-design.md)
**Depends on:** *nothing* — this module takes no other platform module, by constraint (parent §2.3, Part 1.1 below). Read [`2026-08-26-platform-primitives-lld.md`](2026-08-26-platform-primitives-lld.md) and [`2026-08-26-platform-web-lld.md`](2026-08-26-platform-web-lld.md) first for the rule shapes this one reuses, not for a dependency
**Constrains:** LLD #4, `platform-tenancy` — see Part 2.6, 2.7 and SR4
**LLD #3 of 6.**
**Published:** https://claude.ai/code/artifact/ae01a33c-0577-458c-a476-152708f5ae3c

**Label prefixes.** `SD` decisions, `SR` rules, `SF` findings, `SB` bugs. The bare `S1`–`S10` range is
already taken by [`2026-08-24-service-scope-and-shared-modules.md`](2026-08-24-service-scope-and-shared-modules.md);
nothing here reuses it.

---

# Part 0 — What changed from the parent spec

The parent (§2.3) scoped this module as `TokenVerifier`, `VerifiedClaims`, `JwtProperties`,
`SecurityConfig`, `PasswordConfig`, and set three questions for this LLD: the `VerifiedClaims` shape,
where the RS256/JWKS seam sits, and — added by the IdP evaluation's I4/I5 — that the issuer must be a
configuration input rather than a constant.

Reading the source answered all three. Two of the three answers also move something the parent did
not anticipate — the module's contents shrink, and `SecurityConfig` as scoped cycles the graph. The
third sharpens a question the parent did ask.

## The module is smaller than the parent's contents list

**`PasswordConfig` does not belong here.** It has exactly one consumer — `AuthService`, which calls
`encoder.encode` at signup and `encoder.matches` at login. Nothing else in 833 lines of platform or
in any of the five services touches a `PasswordEncoder`. That is precisely the test the parent added
to D12 as its second criterion, and applied to `PdfEngine` and `IndianFormats` in P5:

> **A mechanism used by exactly one service is not a platform mechanism.**

`PasswordConfig` fails it as squarely as `PdfEngine` did. It follows `JwtService.mint` into
`identity-svc` (**SD5**), where the bcrypt strength, the signing key and the credential flow all sit
under one task role and one deployment.

The honest tension, recorded rather than argued away: unlike openhtmltopdf, `spring-security-crypto`
is small and already on four services' classpaths via the filter chain, so the *jar-weight* half of
P5's reasoning does not apply. The rule is kept anyway because it is about meaning, not weight — and
because "where is the password hashed" having one answer, in the one service that owns credentials,
is worth more than the convenience of a shared bean. If this is overridden on review, nothing else in
this document changes.

## `SecurityConfig` as written cycles the graph

The parent puts `SecurityConfig` in this module (§2.3) and `JwtAuthenticationFilter` in
`platform-tenancy` (§2.4, forced by PF1). Today those two are wired by constructor injection:

```java
SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter)
```

Under the split that signature makes `platform-security` name a `platform-tenancy` type — the exact
cycle PF1 exists to prevent, arriving through the back door while everyone watches `VerifiedClaims`.
Part 2.6 resolves it, and the resolution also fixes a second problem the parent has not noticed: the
current route table is a single hard-coded list covering routes that will belong to **three different
services** after the split.

## The RS256 seam is two seams, not one

The parent asks "where the RS256/JWKS seam sits", which presumes one seam. There are two, and they
fail differently:

| Seam | What changes at sub-project 7 | Failure mode if drawn wrong |
|---|---|---|
| **Key resolution** | `SecretKey` from config → public key selected by `kid` from a cached JWKS | A network dependency appears inside token verification. Today verification cannot fail for an infrastructure reason; after RS256 it can |
| **Claim set** | `iss`, `aud`, `kid` must exist in the token | Five services and the minter must change on the same day |

The second is the one that would have turned "a swap" into a rewrite, and it is closed here rather
than deferred: **SD3** — identity-svc mints the full target claim set from day one of the split,
still under HS256, and the verifier validates it from day one. Sub-project 7 then changes the
algorithm and the key source and nothing else.

---

# Part 1 — Where the code lives

```
platform/
└── platform-security/
    ├── build.gradle.kts
    ├── src/main/java/com/easycrm/platform/security/
    │   ├── VerifiedClaims.java              NEW — plain record, no dependencies
    │   ├── TokenVerifier.java               NEW — interface
    │   ├── JwtTokenVerifier.java            NEW — from JwtService.parse, verification half only
    │   ├── SigningKeyResolver.java          NEW — the RS256 seam
    │   ├── StaticSecretKeyResolver.java     NEW — HS256 today; JwksKeyResolver replaces it at sub-project 7
    │   ├── InvalidTokenException.java       NEW — the caller's fault  → 401
    │   ├── KeyUnavailableException.java     NEW — our fault          → 503
    │   ├── JwtProperties.java               gains issuer, audience, validation
    │   ├── HttpSecurityContribution.java    NEW — how other modules and services reach the chain
    │   ├── SecurityConfig.java              rewritten: default-deny base chain + contributions
    │   └── SecurityAutoConfiguration.java   NEW — replaces component scan
    └── src/testFixtures/java/com/easycrm/platform/security/
        └── TestTokenMinter.java             NEW — see SD4
```

**Leaves this module:** `JwtService.mint` → `identity-svc` (P6). `PasswordConfig` → `identity-svc`
(SD5). `JwtAuthenticationFilter` → `platform-tenancy` (P7).

**Package does not move.** `com.easycrm.platform.security` stays exactly where it is, so unlike LLD
#2's `ApiExceptionHandler` there is no silent-import hazard here (WF5 has no sibling in this module).

## 1.1 Build file

```kotlin
dependencies {
    api("io.jsonwebtoken:jjwt-api")
    runtimeOnly("io.jsonwebtoken:jjwt-impl")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson")

    compileOnly("org.springframework.security:spring-security-web")
    compileOnly("org.springframework.security:spring-security-config")
    compileOnly("jakarta.servlet:jakarta.servlet-api")

    // NOT here: spring-security-crypto (SD5), platform-primitives, platform-tenancy
}
```

**`platform-security` depends on no other platform module, and this is a constraint rather than an
observation** (parent §2.3). It does not even take `platform-primitives`: `InvalidTokenException`
and `KeyUnavailableException` are declared here, not drawn from the five shared types, because
`platform-primitives` is where `ValidationException` lives and a token is not a validated field —
see SF3 for the argument against the alternative.

`compileOnly` on the Spring Security artifacts, per P8. `notification-svc` does not take this module
at all; the `@ConditionalOnClass(Servlet.class)` guard means nothing breaks if it ever does.

## 1.2 Auto-configuration

```java
@AutoConfiguration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityAutoConfiguration {

    @Bean @ConditionalOnMissingBean
    SigningKeyResolver signingKeyResolver(JwtProperties p) { return new StaticSecretKeyResolver(p); }

    @Bean @ConditionalOnMissingBean
    TokenVerifier tokenVerifier(SigningKeyResolver keys, JwtProperties p) {
        return new JwtTokenVerifier(keys, p);
    }

    @Bean @ConditionalOnClass(name = "jakarta.servlet.Servlet")
    SecurityFilterChain filterChain(HttpSecurity http, List<HttpSecurityContribution> contributions) { … }
}
```

Registered in `META-INF/spring/…AutoConfiguration.imports`, not component-scanned — `@Component` on
`JwtAuthenticationFilter` and `@Service` on `JwtService` work today only because every class sits
under one `@SpringBootApplication`'s scan root. That stops being true the moment the module is a jar,
and it stops being true *silently*: no filter chain means every route is wide open, not closed.
This is MB1's shape (LLD #1) on a surface where the failure is not a wire-format regression but an
authentication bypass.

---

# Part 2 — Class model

## 2.1 `VerifiedClaims`

```java
public record VerifiedClaims(
    UUID tenantId,
    UUID userId,
    String role,
    Instant expiresAt
) {}
```

A plain record with no Spring, no Hibernate, no `platform` import. It compiles against nothing but
the JDK, which is what makes the no-dependency constraint hold.

**`role` is a `String`, and deliberately opaque to this module** (**SD1**). Three reasons, in order
of weight:

1. `Role` (`OWNER`, `SALES_MANAGER`, `SALES_EXEC`) is declared in `com.easycrm.iam`, which becomes
   `identity-svc`. This module cannot name it without depending on a service.
2. Verification answers exactly one question — *did the issuer we trust sign this, and is it still
   valid* — and "is `SALES_MANAGER` a role we recognise" is a different question with a different
   owner. A verifier that rejects an unknown role turns adding a role into a fleet-wide deploy.
3. There is nearly no authorization in the codebase to serve. The only check anywhere is
   `TenantService.requireOwner()` — a literal `"OWNER".equals(role)` in a domain service, throwing
   `ForbiddenException`. Meanwhile `JwtAuthenticationFilter` populates `SecurityContextHolder` with
   `ROLE_<role>` authorities that **nothing reads**: no `@PreAuthorize`, no `hasRole`, no
   `authorizeHttpRequests` role matcher exists in the application. Designing a shared role vocabulary
   now would be designing a guard for a policy that does not exist yet (**SF1**).

The RBAC vocabulary belongs to the P0-auth follow-up slice (user invitations, record-level
visibility filtering by `assigned_to`) — that slice has the requirements this module does not.

**What `VerifiedClaims` deliberately does not carry:** anything derived. No `Role` enum, no
`TenantPrincipal`, no `Authentication`. It is the token's contents, checked, and nothing more.

### `VerifiedClaims` is not `TenantPrincipal` minus a cycle (SD2)

PF1 reads as though the two types are split to break a dependency, which undersells it. The source
says they mean different things. `TenantContext.TenantPrincipal` is written from **three** places
and only one of them is a token:

| Provenance | Where | `role` value |
|---|---|---|
| A verified bearer token | `JwtAuthenticationFilter` | `OWNER` / `SALES_MANAGER` / `SALES_EXEC` |
| The credential flow, before any token exists | `AuthService.signup`, `.login`, `.refresh` | `"SYSTEM"` |
| A public share link, where there is no user at all | `PublicShareController` | `"PUBLIC"` |

So:

- **`VerifiedClaims`** — *this token says so, and the signature checks out.*
- **`TenantPrincipal`** — *this call is bound to this tenant, by whatever means.*

The second is strictly wider, and mapping one to the other is `platform-tenancy`'s job (P7). Keeping
them separate is correct on meaning alone; the cycle is a bonus.

It also exposes something worth naming before RBAC is designed: `"SYSTEM"` and `"PUBLIC"` currently
occupy the same `String` field as real roles. No check collides today, because `requireOwner` is the
only check. A future `hasRole`-style check across four services would be one careless pseudo-role
name away from a collision (**SF2**). That is `TenantPrincipal`'s problem, not this module's — logged
as a constraint on LLD #4 (SR4).

## 2.2 `TokenVerifier` and the two seams

```java
public interface TokenVerifier {
    /**
     * @throws InvalidTokenException  the token is absent, malformed, unsigned by us,
     *                                expired, or issued for someone else  → 401
     * @throws KeyUnavailableException the key needed to answer could not be obtained → 503
     */
    VerifiedClaims verify(String token);
}
```

**The two exceptions are the point of this interface**, and they are the fix for the single worst
thing in the current code (2.6, SB1). One means *the caller presented something bad*; the other means
*we cannot currently tell*. Under HS256 the second is unreachable — the key is a config string. Under
JWKS it is a network call, and conflating the two turns a JWKS outage into a fleet-wide 401 storm
that reads exactly like every user simultaneously mistyping their password.

### Seam 1 — key resolution

```java
public interface SigningKeyResolver {
    /** @param kid the token's `kid` header, null if absent */
    Key resolve(String kid);
}
```

`StaticSecretKeyResolver` ignores `kid` and returns the HMAC key from `JwtProperties`. At
sub-project 7, `JwksKeyResolver` replaces it — caching by `kid`, refreshing on a miss, throwing
`KeyUnavailableException` when the fetch fails. **`JwtTokenVerifier` does not change.** That is the
whole claim of "a swap, not a rewrite", reduced to one bean substitution behind
`@ConditionalOnMissingBean`.

### Seam 2 — validation, in order

```java
Jwts.parser()
    .keyLocator(header -> keys.resolve(header.get("kid")))
    .requireIssuer(props.issuer())
    .requireAudience(props.audience())
    .clockSkewSeconds(30)
    .build()
    .parseSignedClaims(token);
```

Signature first, then `exp`, then `iss`, then `aud`. Order matters for what an attacker learns: no
claim is read as trusted before the signature is checked, and no claim is *reported on* at all — every
failure produces the same `InvalidTokenException` with a generic message, matching the generic-401
discipline `AuthService` already applies to login (no slug or email enumeration).

**`iss` and `aud` are configuration, never constants** (I4/I5). The verifier does not care who signed
the token, only that the issuer it was configured to trust did. Adopting Cognito later is then a
change to two YAML values plus a signup saga — the reversibility the IdP evaluation made an
obligation on this module.

**30 seconds of clock skew** is allowed because ECS tasks are not guaranteed to share a clock to the
millisecond, and a 15-minute access token has room to spare. Zero skew makes a rare, unreproducible
401 at token boundaries.

## 2.3 The target claim set (SD3)

| Claim | Today | Target | Why |
|---|:-:|:-:|---|
| `sub` | ● | ● | user id |
| `tenant_id` | ● | ● | the crown jewel |
| `role` | ● | ● | opaque `String` (SD1) |
| `iat`, `exp` | ● | ● | 15-minute TTL, unchanged |
| `iss` | ○ | ● | **configuration input** (I4/I5). Without it a token from any issuer sharing our secret verifies |
| `aud` | ○ | ● | stops an access token being replayed against a different EasyCRM surface (the field-rep app, P5) |
| `kid` (header) | ○ | ● | RS256 key selection. Minted early and **tolerated when absent**, so the flag day is one-directional |
| `plan`, `limits` | ○ | later | B7, sub-project 10. Named here so the shape is known; `VerifiedClaims` gains fields then, not now |
| `jti` | ○ | ✗ | See SF4 — deliberately not adopted |

Minting the full set while still on HS256 costs one change in `identity-svc` and buys a
sub-project 7 that touches one bean. The migration is free at 15 minutes of TTL: deploy the verifier
tolerantly (`kid` optional), deploy the minter, and every pre-migration token has aged out within a
quarter of an hour.

## 2.4 `JwtProperties` — and failing loudly

```java
@ConfigurationProperties(prefix = "easycrm.jwt")
@Validated
public record JwtProperties(
    @NotBlank String secret,
    @NotBlank String issuer,
    @NotBlank String audience,
    @Positive long accessTtlSeconds
) {}
```

Today this record is unvalidated and `application.yml` carries
`${JWT_SECRET:0123456789-0123456789-0123456789-devsecret}`. That is the same shape as the
`public-base-url` default already on the backlog as item 21, with a worse consequence: a deployment
that forgets `JWT_SECRET` boots happily and signs every token in the fleet with a secret that is
committed to a public-facing repository. Nothing fails, nothing logs, and the only observable symptom
is that tokens minted by an attacker verify (**SF5**).

`@NotBlank` does not catch a *known* default. The rule that does:

**SR3 — outside a `dev` profile, the application must fail to start if `easycrm.jwt.secret` equals
the development default or is shorter than 32 bytes.** A startup failure is loud; a shared secret is
silent. This is the validated-`@ConfigurationProperties` treatment backlog item 21 asks for on
`public-base-url`, and the two should land together — same mechanism, same class of bug.

## 2.5 The filter chain, and why it cannot be one list

Today's route table is a single hard-coded block. After the split its rows belong to three different
services:

| Route | Owner after the split |
|---|---|
| `/api/v1/auth/{signup,login,refresh,logout}` | `identity-svc` |
| `GET|HEAD /public/q/*` | `document-svc` |
| `/actuator/health` | every service |
| `/api/**` authenticated | every service |

A shared `SecurityConfig` carrying a literal list of all four is wrong in both directions:
`notification-svc` would authorise auth routes it does not serve, and — the direction that actually
bites — **a service that adds a public route has nowhere to declare it.** S1 is already this bug one
layer up: `/api/v1/tenant` is built, works, and is absent from the ALB routing table, so it 404s at
the edge under the split. Two route tables that must agree, and nothing that makes a disagreement
visible, is the same failure at the service boundary.

## 2.6 `HttpSecurityContribution` — one mechanism, three problems

```java
@FunctionalInterface
public interface HttpSecurityContribution {
    void apply(HttpSecurity http) throws Exception;
    default int order() { return 0; }
}
```

`platform-security` owns the base chain and applies contributions **before** sealing it:

```java
@Bean
SecurityFilterChain filterChain(HttpSecurity http, List<HttpSecurityContribution> contributions) {
    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
        .exceptionHandling(e -> e.authenticationEntryPoint(
            new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))          // 401, not Spring's 403
        .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health").permitAll());

    contributions.stream().sorted(comparingInt(HttpSecurityContribution::order))
                 .forEach(c -> c.apply(http));                           // services open routes

    return http.authorizeHttpRequests(a -> a                             // …then it is sealed
                   .requestMatchers("/api/**").authenticated()
                   .anyRequest().denyAll())
               .build();
}
```

This one seam resolves all three of Part 0's structural problems:

| Problem | How it is resolved |
|---|---|
| `SecurityConfig` naming `JwtAuthenticationFilter` (the PF1 cycle through the back door) | `platform-tenancy` contributes `http -> http.addFilterBefore(jwtFilter, …)`. Security names only its own interface; the dependency arrow keeps pointing tenancy → security |
| Route tables that must agree across three services (2.5, S1's shape) | Each service declares its own public routes, next to the controller that serves them |
| Five copies of csrf-disable / STATELESS / 401-entry-point, four of which can silently drift | Owned once, here. P1's argument exactly: a service that never writes an entry point cannot get one wrong |

**The ordering is the safety property.** Contributions run first and the seal runs last, and Spring
evaluates `authorizeHttpRequests` matchers in declaration order. So a service **can** open a route
and **cannot** open one after the catch-all, and cannot forget the catch-all — because it never
writes it. Default-deny is structural, not procedural, which is this project's thesis applied to
routing.

Rejected: letting each service define its own `SecurityFilterChain` bean (five copies of the base
policy — the P1 failure), and a `PublicRoutes` value type separate from the filter mechanism (two
seams where one does the work).

## 2.7 What this module does *not* own

**The filter's behaviour on failure**, which lives in `platform-tenancy` (P7). But its *contract* is
set here, because today's is the most dangerous line in the module:

```java
} catch (RuntimeException ex) {
    // invalid token: leave unauthenticated; SecurityConfig will 401 protected routes
    chain.doFilter(req, res);
}
```

An expired token, a forged token, a malformed header and a `NullPointerException` in the filter
itself are all indistinguishable — no log line, no metric, no way to tell a credential problem from
an outage. Under JWKS that set grows to include *a failed key fetch*, and the fleet 401s every
request while every dashboard says the application is healthy.

**SR4, a constraint on LLD #4:** the filter catches `InvalidTokenException` and proceeds
unauthenticated; it lets `KeyUnavailableException` propagate to a 503; it catches nothing else. A
bare `catch (RuntimeException)` in that filter is a review-blocking defect.

---

# Part 3 — How a service adopts it

```kotlin
dependencies { implementation(project(":platform:platform-security")) }
```

```yaml
easycrm:
  jwt:
    issuer:   ${JWT_ISSUER:https://id.easycrm.in}
    audience: ${JWT_AUDIENCE:easycrm-api}
    secret:   ${JWT_SECRET:}          # no default outside dev — SR3 fails startup if unset
    access-ttl-seconds: 900
```

Nothing else. No filter chain, no entry point, no `PasswordEncoder`. A service with **no** public
routes writes no Java at all and is default-deny.

A service that serves public routes declares them beside the controller that owns them:

```java
// document-svc — next to PublicShareController, not in a shared list three services away
@Bean
HttpSecurityContribution publicShareRoutes() {
    return http -> http.authorizeHttpRequests(a -> a
        .requestMatchers(HttpMethod.GET,  "/public/q/*").permitAll()
        // HEAD too: link unfurlers and some WhatsApp/proxy paths issue HEAD before GET,
        // and Spring MVC answers HEAD on a @GetMapping automatically. Without this the
        // link previews as broken (401).
        .requestMatchers(HttpMethod.HEAD, "/public/q/*").permitAll());
}
```

And `identity-svc`, which additionally keeps the minter and the encoder:

```java
@Bean
HttpSecurityContribution authRoutes() {
    return http -> http.authorizeHttpRequests(a -> a
        .requestMatchers(HttpMethod.POST,
            "/api/v1/auth/signup", "/api/v1/auth/login",
            "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll());
}
```

## 3.1 Test fixtures — the cost of P6 (SD4)

Moving `mint` to `identity-svc` breaks every integration test in the other four services.
`TestTokens` calls `jwt.mint(tenantId, userId, "OWNER")`, and it is how essentially every
authenticated test in the codebase obtains a bearer token. After P6 those services have a verifier
and no minter.

`platform-security` ships a `java-test-fixtures` source set — the same device P9 uses for
`TenantContextTestSupport`:

```java
// src/testFixtures — never on any runtime classpath
public final class TestTokenMinter {
    public static String bearer(UUID tenantId, UUID userId, String role, JwtProperties props) { … }
}
```

```kotlin
testImplementation(testFixtures(project(":platform:platform-security")))
```

This is not a hole in P6. P6 exists so that no *service* can mint a token that another service will
trust; a `testFixtures` artifact is unavailable at runtime by construction, and under RS256 it would
need the private key it will not have. **SR2 enforces the boundary in the main source set**, which is
where BF8 lives.

---

# Part 4 — What keeps it honest

**SR1 — no code outside this module may parse a JWT.**

```java
noClasses().that().resideOutsideOfPackage("com.easycrm.platform.security..")
    .should().callMethod(Jwts.class, "parser")
    .because("a parser built elsewhere skips issuer, audience and expiry validation, and "
           + "cannot distinguish InvalidToken from KeyUnavailable. Use TokenVerifier.");
```

LLD #1's R1 and LLD #2's W1 in the same shape: the dangerous thing is unreachable, so the safe thing
cannot be forgotten.

**SR2 — only `identity-svc` may mint.**

```java
noClasses().that().resideOutsideOfPackage("com.easycrm.iam..")
    .should().callMethod(Jwts.class, "builder")
    .because("BF8 — a service that can mint can mint itself plan: ENTERPRISE. Under RS256 the "
           + "signing key must be reachable from one task role, not five.");
```

This runs in `identity-svc`'s own build and, once the split lands, is trivially satisfied elsewhere
because the private key is not on the other services' task roles. It is worth writing anyway: **it is
the rule that has to hold during the monolith-to-services transition**, when every class is still in
one build and the boundary is a convention.

**SR3 — a known-default or too-short JWT secret must fail startup outside `dev`.** See 2.4/SF5.

**SR4 — the JWT filter (LLD #4) catches `InvalidTokenException` only.** See 2.7/SB1.

**SR5 — `platform-security` may depend on no other `com.easycrm` package.**

```java
noClasses().that().resideInAPackage("com.easycrm.platform.security..")
    .should().dependOnClassesThat(
        resideInAPackage("com.easycrm..")
            .and(not(resideInAPackage("com.easycrm.platform.security.."))))
    .because("this module must name no other com.easycrm type. A single innocent import — "
           + "UnauthorizedException would be the obvious one — cycles the module graph.");
```

The self-exclusion is the fiddly part and must be proved by adding a deliberate import and watching
the rule go red; see SF6.

The constraint from parent §2.3, made mechanical. It is the only one of the five rules that is a
*graph* property rather than a call-site property, and it is the one most likely to be violated by an
innocent-looking import.

---

# Part 5 — Test plan

## 5.1 What exists today

Eight tests across four classes, 113 lines:

| Class | Asserts |
|---|---|
| `JwtServiceTest` | mint→parse round-trips; a tampered token is rejected |
| `PasswordConfigTest` | bcrypt hashes and matches; the hash is bcrypt |
| `SecurityIntegrationTest` | a protected route without a token is 401; `/actuator/health` is public |
| `AuthEndpointsPublicTest` | `/api/v1/auth/login` is reachable unauthenticated; `/api/v1/auth/me` is not |

Real coverage of the happy path and of tampering. **Two gaps stand out and both are about to
matter more:**

- **No test asserts that an expired token is rejected.** The one claim with a security consequence
  that the round-trip test cannot reach, and `exp` handling is about to move behind a new interface.
- **Nothing covers `JwtAuthenticationFilter` at all** — no test that a malformed header leaves the
  request unauthenticated rather than erroring, and none that the `finally` clears `TenantContext` on
  a pooled thread. The comment on that `finally` says `MUST clear — pooled threads`; the assertion
  behind it does not exist.

## 5.2 What this design adds

| Test | Kind | Asserts |
|---|---|---|
| Expired token → `InvalidTokenException` | unit | closes 5.1's first gap; TTL manipulated through `JwtProperties`, no sleeping |
| Wrong `iss` → rejected; wrong `aud` → rejected | unit | **the two new claims actually gate something.** Without these, SD3 ships two decorative claims |
| Missing `kid` verifies successfully | unit | the tolerance that makes the sub-project 7 flag day one-directional |
| Token signed with a different key → `InvalidTokenException` | unit | the existing tamper test, restated against the new interface |
| `KeyUnavailableException` ≠ `InvalidTokenException` | unit (failing stub `SigningKeyResolver`) | **the most important test in this module** — the failure mode is a silent fleet-wide 401 storm |
| Clock skew: a token expired 10 s ago verifies, 60 s ago does not | unit | the 30 s allowance is intentional, not accidental |
| Every failure carries the same message | unit | no enumeration oracle, matching `AuthService`'s generic 401 |
| A service with no contribution is default-deny | `@WebMvcTest` slice | the base chain seals |
| A contribution can open a route; a contribution **cannot** open one past the seal | `@WebMvcTest` slice | 2.6's ordering property — the reason the mechanism exists |
| `/actuator/health` public, `/api/**` authenticated, everything else 403 | slice | the base policy, stated once instead of implied by 24 endpoint tests |
| Startup fails on the default secret outside `dev` | `ApplicationContextRunner` | SR3 |
| SR1, SR2, SR5 | ArchUnit | Part 4 |

## 5.3 Not testable here

- **That the fleet's JWKS cache behaves under a key rotation.** Needs two services and a rotating
  issuer; it belongs to sub-project 7's integration tests.
- **That the ALB route table agrees with the contributed routes** (S1). Terraform's half; this module
  can only assert its own.
- **That `identity-svc` is the only task role holding the signing key.** IAM policy, not Java. SR2 is
  the build-time approximation of a runtime guarantee.

---

# Part 6 — Bugs you will hit

| # | Bug | Why it happens | Fix |
|---|---|---|---|
| **SB1** | A JWKS fetch failure 401s every request in the fleet, and every dashboard reads healthy | Today's `catch (RuntimeException) → proceed unauthenticated` swallows an infrastructure failure into "bad credentials" | The two-exception taxonomy (2.2) plus SR4 |
| **SB2** | After the split, a service boots with **no filter chain at all** and every route is open | `@Component`/`@Service` stop being scanned once the module is a jar; the failure is silent and open, not closed | Auto-configuration (1.2) + 5.2's default-deny slice test |
| **SB3** | A service adds a public route, it works locally, and it 404s at the edge in production | Two route tables that must agree with nothing comparing them — S1, exactly | 2.6 puts the declaration beside the controller; the ALB half stays sub-project 2's problem |
| **SB4** | A deployment that forgets `JWT_SECRET` signs every token with a secret from the repository, and nothing anywhere reports it | A dev default that boots | SR3 — fail startup, loudly |
| **SB5** | `iss` and `aud` are minted, never validated, and everyone believes the tokens are scoped | The claims are added for RS256 readiness and the validation is left for "when it matters" | 5.2's wrong-issuer / wrong-audience tests. A claim nothing checks is a comment |
| **SB6** | Someone consolidates `InvalidTokenException` and `KeyUnavailableException` because "both mean the token did not verify" | They read as siblings and one is never thrown under HS256, so it looks dead for the whole pre-RS256 period | Keep both. WB4's shape: the merge is done by someone who did not read this document |
| **SB7** | An `@Async` or scheduled path calls `TokenVerifier` and blocks on a JWKS fetch inside a transaction | `verify()` looks pure and becomes a network call at sub-project 7 | Documented on the interface; the cache makes it rare, not impossible |

---

# Appendix A — Findings

| # | Finding | Severity |
|---|---|---|
| **SF1** | **The application has authentication and effectively no authorization.** One check exists (`TenantService.requireOwner`, a string compare in a domain service). `SecurityContextHolder` is populated with `ROLE_` authorities on every request that **nothing reads** — no `@PreAuthorize`, no `hasRole`, no role matcher. The authorities are pure ceremony today | Not this module's to fix (SD1), but it means the §8 backlog's "record-level visibility filtering" is a larger slice than it reads: there is no RBAC to extend, only one to write. The `SecurityContextHolder` population is worth keeping — it is the seam `@PreAuthorize` will need |
| **SF2** | `"SYSTEM"` and `"PUBLIC"` are written into the same `role` field as real roles, from `AuthService` (3 call sites) and `PublicShareController`. Harmless today because only one check exists; a hazard the moment role checks spread across four services | Constraint on LLD #4 (SR4's sibling): `TenantPrincipal` should distinguish provenance from role, rather than overloading one `String` |
| **SF3** | `InvalidTokenException` is declared in this module rather than reusing `platform-primitives`' `UnauthorizedException`, which is what the current code throws. That is a **deliberate duplication** — taking primitives would give this module a dependency, and the no-dependency constraint is load-bearing | Accepted. `platform-tenancy`'s filter maps `InvalidTokenException` onto the 401 path; the two types never meet in one place. Revisit only if a third exception wants to cross the same boundary |
| **SF4** | No `jti`, so an access token cannot be revoked before its 15-minute TTL expires. Deliberately not adopted: revocation needs a shared lookup on every request, which turns the local-verification property — the thing B7 and the whole five-service design rest on — into a network dependency | Accepted at 15 minutes. Revisit if the TTL ever lengthens, or if a compliance requirement demands immediate session kill. Refresh tokens already rotate and revoke |
| **SF5** | `easycrm.jwt.secret` has a committed dev default and no validation, exactly as `public-base-url` does (backlog 21) — but a leaked signing secret is forged tokens for every tenant, not a broken link | SR3. **Land it with backlog 21**: same mechanism, same class of bug, and doing one alone leaves the other looking deliberate |
| **SF6** | SR5's predicate self-excludes the module from its own rule, which is easy to get subtly wrong — a mis-scoped `and(not(...))` produces a rule that passes because it matches nothing, and a rule that vacuously passes is worse than no rule, because it reads as coverage | The form in Part 4 is written out; **prove it by adding a deliberate import and watching it go red** before trusting it. Appendix B item 4 |
| **SF7** | `PasswordConfig` moving to `identity-svc` (SD5) is a revision to the parent's §2.3 contents list, decided by applying P5's rule rather than by asking. It is the one call in this LLD most likely to be overridden on review | Nothing else in this document depends on it. If overridden, `PasswordConfig` returns and `1.1` regains `spring-security-crypto` |
| **SF8** | The 30-second clock-skew allowance is chosen, not measured — the same shape as WF1's 3 s budget | Low stakes: the failure it prevents is a rare 401 and the exposure it adds is 30 s on a 900 s token. No measurement needed unless the TTL shrinks |

---

# Appendix B — To verify before implementation

1. **That JJWT 0.12's `keyLocator` receives the header before signature verification**, and that
   returning a key per `kid` composes with `requireIssuer`/`requireAudience` in one parser build. The
   whole of seam 1 rests on it.
2. **Which Boot 4 per-integration module carries the Spring Security auto-configuration**, and
   whether a `SecurityFilterChain` bean defined in an `@AutoConfiguration` is discovered the same way
   a component-scanned one is. This is LLD #1's open question 2 and LLD #2's Appendix B item 5, for
   the third time — and here the silent failure is **open routes**, not a wrong wire format. Verify
   it once, for all three.
3. **That `HttpSecurity` can be mutated by more than one `authorizeHttpRequests` call** with matcher
   precedence following declaration order across those calls. 2.6's ordering property is the entire
   safety argument and it assumes this. If Spring Security 7 collapses or reorders them, the seal
   must be built as a single terminal call instead.
4. **That SR5 can be expressed in ArchUnit 1.4.1** without excluding the module from itself by
   accident — see SF6. Prove it by adding an import and watching it fail.
5. **That `java-test-fixtures` publishes cleanly for a module consumed by four others**, and that a
   `testFixtures` dependency cannot leak onto a runtime classpath. Shared with P9's use of the same
   device — verify once.
6. **Whether Spring Boot 4 still honours `@Validated` on a `record`-based
   `@ConfigurationProperties`**, which SR3's `@NotBlank`/`@Positive` assume.
7. **The actual `clockSkewSeconds` API name in the JJWT version resolved by the Boot 4 BOM** — it has
   moved between 0.11 and 0.12.
