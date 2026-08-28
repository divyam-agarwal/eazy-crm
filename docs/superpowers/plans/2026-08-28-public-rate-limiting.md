# Public + Auth Rate Limiting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cap `GET /public/q/*` and the four `permitAll` auth routes with a per-IP token bucket, so
the app's only unauthenticated route — which renders a PDF per hit — stops being uncapped.

**Architecture:** A `OncePerRequestFilter` ordered ahead of Spring Security's chain, keying buckets on
`getRemoteAddr()` alone. Buckets live behind a `RateLimitStore` port with a Bucket4j + Caffeine
in-memory implementation, so the Redis implementation the AWS re-platform needs replaces exactly one
class. Limits are `@ConfigurationProperties`, tunable without a release.

**Tech Stack:** Java 25, Spring Boot 4.1.0, Bucket4j 8.19.0 (`bucket4j_jdk17-core`), Caffeine
(Boot-BOM-managed), JUnit 5, MockMvc, Testcontainers Postgres.

**Spec:** `docs/superpowers/specs/2026-08-27-public-rate-limiting-design.md` — read it first. The
plan argues from it; where this plan gives a number, the spec gives the reason.

## Global Constraints

- **Commit as `divyam`.** Plain `git commit`, no `-c user.name=` override, no `Co-Authored-By`
  trailer, no mention of Claude or AI anywhere in a commit message. (CLAUDE.md)
- **Filtered test runs must be project-qualified.** `./gradlew :test --tests '...'` for root-project
  tests. Unqualified `./gradlew test --tests '...'` applies the filter to `platform-primitives` too
  and fails there on "no matching tests". Full-suite runs are `./gradlew clean test`, unqualified, on
  purpose.
- **Baseline before you start: 264 tests, 0 failures, 0 errors** (241 root + 23 module). Gradle prints
  no total for a multi-project build; count with the snippet in `HANDOFF.md` §0.
- **Docker must be running** before any test task (`open -a Docker`, wait for `docker info`).
- **Package for all new production code:** `com.easycrm.platform.ratelimit`. A package, not a Gradle
  module — see spec §4.2 for why this must not become module 2.
- **No new Spring dependency in `platform-primitives`.** Nothing in this plan touches that module.
- **The limiter keys on `getRemoteAddr()` only.** Never read `X-Forwarded-For` in application code.
  Spec §4.4 explains why that would be a silent bypass.

---

## File Structure

**Create (production):**

| File | Responsibility |
|---|---|
| `backend/src/main/java/com/easycrm/platform/ratelimit/RateLimitPolicy.java` | Value type: one named limit bound to a path pattern |
| `backend/src/main/java/com/easycrm/platform/ratelimit/RateLimitProperties.java` | `easycrm.rate-limit` binding + validation |
| `backend/src/main/java/com/easycrm/platform/ratelimit/RateLimitStore.java` | The port: `tryConsume(key, policy) -> Decision` |
| `backend/src/main/java/com/easycrm/platform/ratelimit/InMemoryRateLimitStore.java` | Bucket4j buckets in a bounded Caffeine cache |
| `backend/src/main/java/com/easycrm/platform/ratelimit/RateLimitFilter.java` | Match policy, resolve IP, consume, 429 or continue |
| `backend/src/main/java/com/easycrm/platform/ratelimit/RateLimitConfig.java` | Bean wiring + `FilterRegistrationBean` order |

**Create (test):**

| File | Responsibility |
|---|---|
| `backend/src/test/java/com/easycrm/platform/ratelimit/RateLimitPolicyTest.java` | Path matching, Retry-After rounding |
| `backend/src/test/java/com/easycrm/platform/ratelimit/InMemoryRateLimitStoreTest.java` | Exhaust, refill, key isolation, eviction |
| `backend/src/test/java/com/easycrm/platform/ratelimit/RateLimitFilterTest.java` | Filter logic against a fake store |
| `backend/src/test/java/com/easycrm/platform/ratelimit/RateLimitIntegrationTest.java` | End-to-end 429, XFF, ordering proof |
| `backend/src/test/java/com/easycrm/platform/ratelimit/RateLimitDefaultsTest.java` | Production default is `enabled: true` |

**Modify:** `backend/build.gradle.kts` (two dependencies) · `backend/src/main/resources/application.yml`
(policies + a `forward-headers-strategy` comment) · `backend/src/test/java/com/easycrm/support/IntegrationTest.java`
(disable the limiter suite-wide) · the four docs in Task 6.

---

## Task 1: Dependencies, policy value type, and configuration binding

Bucket4j's artifact id is JDK-qualified (`bucket4j_jdk17-core`) as of 8.10 — the coordinate you may
remember, `com.bucket4j:bucket4j-core`, is stale and resolves to an ancient version. Step 2 exists to
prove resolution on Java 25 before any code depends on it.

**Files:**
- Modify: `backend/build.gradle.kts` (dependencies block, after the jjwt lines)
- Create: `backend/src/main/java/com/easycrm/platform/ratelimit/RateLimitPolicy.java`
- Create: `backend/src/main/java/com/easycrm/platform/ratelimit/RateLimitProperties.java`
- Test: `backend/src/test/java/com/easycrm/platform/ratelimit/RateLimitPolicyTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `RateLimitPolicy` (record: `name`, `path`, `capacity`, `refillPeriod`; methods
  `boolean matches(String requestPath)` and `static long retryAfterSeconds(long nanos)`);
  `RateLimitProperties` (record: `boolean enabled`, `List<RateLimitPolicy> policies`; method
  `Optional<RateLimitPolicy> policyFor(String requestPath)`).

- [ ] **Step 1: Add the two dependencies**

In `backend/build.gradle.kts`, immediately after the `jjwt-jackson` line:

```kotlin
    // Token-bucket rate limiting. The artifact id is JDK-qualified as of 8.10:
    // com.bucket4j:bucket4j-core is a stale coordinate that resolves to 8.1.x.
    implementation("com.bucket4j:bucket4j_jdk17-core:8.19.0")
    // Bounded bucket storage. The key is a client IP — attacker-controlled — so an
    // unbounded map would make the rate limiter its own memory-exhaustion vector.
    // Version comes from the Boot BOM; do not pin it here.
    implementation("com.github.ben-manes.caffeine:caffeine")
```

- [ ] **Step 2: Prove both resolve on Java 25 before writing code against them**

Run: `cd backend && ./gradlew :dependencies --configuration runtimeClasspath | grep -E 'bucket4j|caffeine'`

Expected: two resolved lines, e.g. `com.bucket4j:bucket4j_jdk17-core:8.19.0` and
`com.github.ben-manes.caffeine:caffeine:3.x.x` — **with no `FAILED` marker on either.**

If Caffeine shows as unmanaged (no version resolved), the Boot 4.1 BOM does not manage it after all:
pin `com.github.ben-manes.caffeine:caffeine:3.2.0` explicitly and note the deviation in the task's
review notes. If Bucket4j fails, stop and report — do not substitute a different library, since the
spec's port design assumes token-bucket semantics.

- [ ] **Step 3: Write the failing test**

Create `backend/src/test/java/com/easycrm/platform/ratelimit/RateLimitPolicyTest.java`:

```java
package com.easycrm.platform.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitPolicyTest {

    private static RateLimitPolicy policy(String path) {
        return new RateLimitPolicy("test", path, 10, Duration.ofMinutes(1));
    }

    @Test
    void singleSegmentWildcardMatchesExactlyOneSegment() {
        RateLimitPolicy p = policy("/public/q/*");
        assertTrue(p.matches("/public/q/abc123"));
        // Deliberately NOT limited: an extra segment never resolves to the share route,
        // is rejected at the security chain, and renders no PDF (spec section 5).
        assertFalse(p.matches("/public/q/abc/def"));
        assertFalse(p.matches("/public/q"));
        assertFalse(p.matches("/api/v1/customers"));
    }

    @Test
    void doubleWildcardMatchesEverythingBeneath() {
        RateLimitPolicy p = policy("/api/v1/auth/**");
        assertTrue(p.matches("/api/v1/auth/login"));
        assertTrue(p.matches("/api/v1/auth/refresh"));
        assertFalse(p.matches("/api/v1/customers"));
    }

    @Test
    void retryAfterRoundsUpAndIsNeverZero() {
        // A floor would round sub-second waits to 0 and invite an immediate retry.
        assertEquals(1, RateLimitPolicy.retryAfterSeconds(1));
        assertEquals(1, RateLimitPolicy.retryAfterSeconds(999_999_999L));
        assertEquals(2, RateLimitPolicy.retryAfterSeconds(1_000_000_001L));
        assertEquals(37, RateLimitPolicy.retryAfterSeconds(36_500_000_000L));
        assertEquals(1, RateLimitPolicy.retryAfterSeconds(0));
        assertEquals(1, RateLimitPolicy.retryAfterSeconds(-5));
    }

    @Test
    void firstMatchingPolicyWinsAndUnmatchedPathsAreUnlimited() {
        RateLimitProperties props = new RateLimitProperties(true, List.of(
            new RateLimitPolicy("public-share", "/public/q/*", 60, Duration.ofHours(1)),
            new RateLimitPolicy("auth", "/api/v1/auth/**", 30, Duration.ofMinutes(1))));

        assertEquals("public-share", props.policyFor("/public/q/tok").orElseThrow().name());
        assertEquals("auth", props.policyFor("/api/v1/auth/login").orElseThrow().name());
        // Unmatched: health checks and the future frontend must never be throttled
        // by a limit nobody sized for them.
        assertTrue(props.policyFor("/api/v1/customers").isEmpty());
        assertTrue(props.policyFor("/actuator/health").isEmpty());
    }
}
```

- [ ] **Step 4: Run it and watch it fail**

Run: `cd backend && ./gradlew :test --tests 'com.easycrm.platform.ratelimit.RateLimitPolicyTest'`

Expected: **compilation failure** — `RateLimitPolicy` and `RateLimitProperties` do not exist. That is
the correct red for this task.

- [ ] **Step 5: Write `RateLimitPolicy`**

```java
package com.easycrm.platform.ratelimit;

import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.time.Duration;

/**
 * One named limit bound to a path pattern: {@code capacity} requests per
 * {@code refillPeriod}, per client IP.
 *
 * <p>The compiled {@link PathPattern} is built once in the canonical constructor rather
 * than per request — this sits in front of every request the application serves.
 */
public record RateLimitPolicy(String name, String path, long capacity, Duration refillPeriod,
                              PathPattern compiled) {

    public RateLimitPolicy(String name, String path, long capacity, Duration refillPeriod) {
        this(name, path, capacity, refillPeriod, PathPatternParser.defaultInstance.parse(path));
    }

    public boolean matches(String requestPath) {
        return compiled.matches(PathContainer.parsePath(requestPath));
    }

    /**
     * Whole seconds for the {@code Retry-After} header, rounded UP and floored at 1.
     * Rounding down would emit {@code Retry-After: 0} for any sub-second wait, which
     * tells a client to retry immediately — the opposite of the point.
     */
    public static long retryAfterSeconds(long nanosToWaitForRefill) {
        if (nanosToWaitForRefill <= 0) return 1;
        return Math.max(1, (nanosToWaitForRefill + 999_999_999L) / 1_000_000_000L);
    }
}
```

- [ ] **Step 6: Write `RateLimitProperties`**

```java
package com.easycrm.platform.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Optional;

/**
 * Limits are configuration, not code: a deployment retunes them without a release.
 *
 * <p>{@code enabled} defaults to TRUE. The test harness turns it off explicitly
 * (see IntegrationTest) — a default of false would mean a config typo silently ships
 * an unprotected application.
 */
@ConfigurationProperties("easycrm.rate-limit")
public record RateLimitProperties(@DefaultValue("true") boolean enabled,
                                  @DefaultValue List<RateLimitPolicy> policies) {

    /** First match wins; an unmatched path is unlimited. */
    public Optional<RateLimitPolicy> policyFor(String requestPath) {
        return policies.stream().filter(p -> p.matches(requestPath)).findFirst();
    }
}
```

- [ ] **Step 7: Run the test and watch it pass**

Run: `cd backend && ./gradlew :test --tests 'com.easycrm.platform.ratelimit.RateLimitPolicyTest'`
Expected: PASS, 4 tests.

- [ ] **Step 8: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/build.gradle.kts backend/src/main/java/com/easycrm/platform/ratelimit backend/src/test/java/com/easycrm/platform/ratelimit
git commit -m "feat: rate-limit policy value type and configuration binding

Limits are configuration so a deployment can retune them without a release.
enabled defaults to true: a default of false would mean a config typo ships an
unprotected application silently.

Bucket4j's artifact id is JDK-qualified as of 8.10 (bucket4j_jdk17-core);
the bucket4j-core coordinate is stale. Caffeine's version comes from the Boot
BOM because bucket storage keyed on client IP must be bounded."
```

---

## Task 2: The store port and its Bucket4j implementation

**Files:**
- Create: `backend/src/main/java/com/easycrm/platform/ratelimit/RateLimitStore.java`
- Create: `backend/src/main/java/com/easycrm/platform/ratelimit/InMemoryRateLimitStore.java`
- Test: `backend/src/test/java/com/easycrm/platform/ratelimit/InMemoryRateLimitStoreTest.java`

**Interfaces:**
- Consumes: `RateLimitPolicy` from Task 1.
- Produces: `RateLimitStore` (interface, method
  `RateLimitStore.Decision tryConsume(String key, RateLimitPolicy policy)`);
  `RateLimitStore.Decision` (record: `boolean allowed`, `long nanosToWaitForRefill`);
  `InMemoryRateLimitStore` (constructor `InMemoryRateLimitStore(TimeMeter timeMeter)`, plus a no-arg
  constructor using `TimeMeter.SYSTEM_MILLISECONDS`; method `long bucketCount()` for tests).

- [ ] **Step 1: Write the failing test**

Note the injected `TimeMeter`: refill is tested by *advancing a clock*, never by sleeping. A
`Thread.sleep` test here would be slow and flaky on a loaded CI box.

Create `backend/src/test/java/com/easycrm/platform/ratelimit/InMemoryRateLimitStoreTest.java`:

```java
package com.easycrm.platform.ratelimit;

import io.github.bucket4j.TimeMeter;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryRateLimitStoreTest {

    /** A clock the test moves by hand, so refill is deterministic and instant. */
    static final class FakeClock implements TimeMeter {
        private long nanos = 0;
        void advance(Duration d) { nanos += d.toNanos(); }
        @Override public long currentTimeNanos() { return nanos; }
        @Override public boolean isWallClockBased() { return false; }
    }

    private static final RateLimitPolicy POLICY =
        new RateLimitPolicy("test", "/**", 3, Duration.ofMinutes(1));

    @Test
    void allowsUpToCapacityThenDenies() {
        InMemoryRateLimitStore store = new InMemoryRateLimitStore(new FakeClock());

        for (int i = 1; i <= 3; i++) {
            assertTrue(store.tryConsume("1.2.3.4", POLICY).allowed(), "request " + i + " of 3");
        }
        RateLimitStore.Decision denied = store.tryConsume("1.2.3.4", POLICY);
        assertFalse(denied.allowed());
        assertTrue(denied.nanosToWaitForRefill() > 0,
            "a denial must say how long to wait, or Retry-After is a guess");
    }

    @Test
    void refillsAfterThePeriodElapses() {
        FakeClock clock = new FakeClock();
        InMemoryRateLimitStore store = new InMemoryRateLimitStore(clock);
        for (int i = 0; i < 3; i++) store.tryConsume("1.2.3.4", POLICY);
        assertFalse(store.tryConsume("1.2.3.4", POLICY).allowed());

        clock.advance(Duration.ofMinutes(1));

        assertTrue(store.tryConsume("1.2.3.4", POLICY).allowed(),
            "the bucket must actually refill — otherwise this is a permanent ban, not a limit");
    }

    @Test
    void distinctKeysGetDistinctBuckets() {
        InMemoryRateLimitStore store = new InMemoryRateLimitStore(new FakeClock());
        for (int i = 0; i < 3; i++) store.tryConsume("1.2.3.4", POLICY);
        assertFalse(store.tryConsume("1.2.3.4", POLICY).allowed());

        assertTrue(store.tryConsume("5.6.7.8", POLICY).allowed(),
            "one noisy client must not exhaust everyone else's allowance");
    }

    @Test
    void separatePoliciesDoNotShareABucket() {
        InMemoryRateLimitStore store = new InMemoryRateLimitStore(new FakeClock());
        RateLimitPolicy other = new RateLimitPolicy("other", "/**", 3, Duration.ofMinutes(1));
        for (int i = 0; i < 3; i++) store.tryConsume("1.2.3.4", POLICY);

        assertTrue(store.tryConsume("1.2.3.4", other).allowed(),
            "buckets are keyed by policy AND client, so the auth limit cannot drain "
                + "the public-share limit");
    }

    @Test
    void bucketStorageIsBounded() {
        InMemoryRateLimitStore store = new InMemoryRateLimitStore(new FakeClock());
        for (int i = 0; i < 60_000; i++) store.tryConsume("10.0." + (i / 250) + "." + (i % 250), POLICY);

        assertTrue(store.bucketCount() <= InMemoryRateLimitStore.MAX_BUCKETS,
            "the key is attacker-controlled: unbounded storage makes the rate limiter "
                + "its own memory-exhaustion vector, got " + store.bucketCount());
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && ./gradlew :test --tests 'com.easycrm.platform.ratelimit.InMemoryRateLimitStoreTest'`
Expected: compilation failure — neither store type exists yet.

- [ ] **Step 3: Write the port**

```java
package com.easycrm.platform.ratelimit;

/**
 * Where buckets live. The one seam Redis replaces.
 *
 * <p>The AWS re-platform must swap in a Redis-backed implementation BEFORE running more
 * than one application instance: with N instances each holding their own buckets, the
 * effective limit is N times the configured value. Correct at N=1, which is today.
 */
public interface RateLimitStore {

    /** @param key an opaque bucket key — today {@code policyName + '|' + clientIp}. */
    Decision tryConsume(String key, RateLimitPolicy policy);

    record Decision(boolean allowed, long nanosToWaitForRefill) {
        static Decision allowed() { return new Decision(true, 0); }
    }
}
```

- [ ] **Step 4: Write the Bucket4j + Caffeine implementation**

```java
package com.easycrm.platform.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;

import java.time.Duration;

/**
 * In-memory buckets, bounded.
 *
 * <p><b>Why the bound is a security control, not housekeeping.</b> The cache key contains
 * the client IP, which the attacker chooses. A plain ConcurrentHashMap would grow without
 * limit as an attacker rotates source addresses, so the component added to prevent resource
 * abuse would become the resource abuse. Caffeine caps entries and expires idle ones.
 *
 * <p>Eviction can only ever be generous, never punitive: an evicted bucket and a
 * fully-refilled idle bucket are indistinguishable to a caller, and entries are only
 * evicted after twice their refill period of inactivity, by which point the client's
 * allowance had been restored anyway.
 */
public class InMemoryRateLimitStore implements RateLimitStore {

    /** Roughly 50k distinct clients before the coldest are dropped. */
    public static final long MAX_BUCKETS = 50_000;

    private final Cache<String, Bucket> buckets;
    private final TimeMeter timeMeter;

    public InMemoryRateLimitStore() {
        this(TimeMeter.SYSTEM_MILLISECONDS);
    }

    /** @param timeMeter injected so tests can advance time instead of sleeping. */
    public InMemoryRateLimitStore(TimeMeter timeMeter) {
        this.timeMeter = timeMeter;
        this.buckets = Caffeine.newBuilder()
            .maximumSize(MAX_BUCKETS)
            .expireAfterAccess(Duration.ofHours(2))
            .build();
    }

    @Override
    public Decision tryConsume(String key, RateLimitPolicy policy) {
        Bucket bucket = buckets.get(key, k -> newBucket(policy));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        return probe.isConsumed()
            ? Decision.allowed()
            : new Decision(false, probe.getNanosToWaitForRefill());
    }

    private Bucket newBucket(RateLimitPolicy policy) {
        return Bucket.builder()
            .addLimit(limit -> limit.capacity(policy.capacity())
                                    .refillGreedy(policy.capacity(), policy.refillPeriod()))
            .withCustomTimePrecision(timeMeter)
            .build();
    }

    /** Test seam: how many buckets are currently retained. */
    public long bucketCount() {
        buckets.cleanUp();
        return buckets.estimatedSize();
    }
}
```

- [ ] **Step 5: Run the test and watch it pass**

Run: `cd backend && ./gradlew :test --tests 'com.easycrm.platform.ratelimit.InMemoryRateLimitStoreTest'`
Expected: PASS, 5 tests.

If `withCustomTimePrecision` does not exist on your Bucket4j version, the equivalent builder method is
`withCustomTimePrecision(TimeMeter)` on `LocalBucketBuilder` — confirm by reading the jar's API rather
than guessing, and if the name differs, adjust and note it. Do **not** fall back to `Thread.sleep`
tests; a flaky refill test is worse than none.

- [ ] **Step 6: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/src/main/java/com/easycrm/platform/ratelimit backend/src/test/java/com/easycrm/platform/ratelimit
git commit -m "feat: bounded in-memory rate-limit store behind a port

The cache key contains the client IP, which the attacker picks, so unbounded
storage would make the rate limiter its own memory-exhaustion vector. Caffeine
caps entries and expires idle ones; eviction is always generous, never punitive,
because an evicted bucket is indistinguishable from a refilled idle one.

TimeMeter is injected so refill is tested by advancing a clock rather than by
sleeping. RateLimitStore is the one seam Redis replaces when the deployment
grows past a single instance."
```

---

## Task 3: The filter

**Files:**
- Create: `backend/src/main/java/com/easycrm/platform/ratelimit/RateLimitFilter.java`
- Test: `backend/src/test/java/com/easycrm/platform/ratelimit/RateLimitFilterTest.java`

**Interfaces:**
- Consumes: `RateLimitProperties`, `RateLimitPolicy`, `RateLimitStore` from Tasks 1–2.
- Produces: `RateLimitFilter` (constructor
  `RateLimitFilter(RateLimitProperties properties, RateLimitStore store, ObjectMapper objectMapper)`).

- [ ] **Step 1: Write the failing test**

```java
package com.easycrm.platform.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RateLimitFilterTest {

    /** Records the keys it is asked about, and denies once told to. */
    static final class RecordingStore implements RateLimitStore {
        final List<String> keys = new ArrayList<>();
        boolean deny = false;
        @Override public Decision tryConsume(String key, RateLimitPolicy policy) {
            keys.add(key);
            return deny ? new Decision(false, 36_500_000_000L) : new Decision(true, 0);
        }
    }

    private static final RateLimitProperties PROPS = new RateLimitProperties(true, List.of(
        new RateLimitPolicy("public-share", "/public/q/*", 2, Duration.ofHours(1))));

    private static MockHttpServletRequest request(String path, String remoteAddr) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setRemoteAddr(remoteAddr);
        return req;
    }

    @Test
    void allowedRequestPassesDownTheChain() throws Exception {
        RecordingStore store = new RecordingStore();
        RateLimitFilter filter = new RateLimitFilter(PROPS, store, new ObjectMapper());
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("/public/q/tok", "1.2.3.4"), res, chain);

        verify(chain).doFilter(any(), any());
        assertEquals(200, res.getStatus());
    }

    @Test
    void deniedRequestIs429WithRetryAfterAndTheHouseEnvelope() throws Exception {
        RecordingStore store = new RecordingStore();
        store.deny = true;
        RateLimitFilter filter = new RateLimitFilter(PROPS, store, new ObjectMapper());
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("/public/q/tok", "1.2.3.4"), res, chain);

        // The chain must NOT run: the entire point is that the expensive work never starts.
        verify(chain, never()).doFilter(any(), any());
        assertEquals(429, res.getStatus());
        assertEquals("37", res.getHeader("Retry-After"));
        assertEquals("application/json", res.getContentType());
        assertTrue(res.getContentAsString().contains("\"code\":\"RATE_LIMITED\""),
            "must match the ApiExceptionHandler envelope shape, got: " + res.getContentAsString());
        assertTrue(res.getContentAsString().contains("\"error\""));
    }

    @Test
    void unmatchedPathIsNeverConsulted() throws Exception {
        RecordingStore store = new RecordingStore();
        store.deny = true;   // would deny if it were ever asked
        RateLimitFilter filter = new RateLimitFilter(PROPS, store, new ObjectMapper());
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("/api/v1/customers", "1.2.3.4"), res, chain);

        verify(chain).doFilter(any(), any());
        assertEquals(200, res.getStatus());
        assertTrue(store.keys.isEmpty(), "an unmatched path must not even touch the store");
    }

    @Test
    void bucketKeyIsPolicyPlusRemoteAddrAndIgnoresForwardedHeaders() throws Exception {
        RecordingStore store = new RecordingStore();
        RateLimitFilter filter = new RateLimitFilter(PROPS, store, new ObjectMapper());

        MockHttpServletRequest first = request("/public/q/tok", "1.2.3.4");
        first.addHeader("X-Forwarded-For", "9.9.9.9");
        MockHttpServletRequest second = request("/public/q/tok", "1.2.3.4");
        second.addHeader("X-Forwarded-For", "8.8.8.8");

        filter.doFilter(first, new MockHttpServletResponse(), mock(FilterChain.class));
        filter.doFilter(second, new MockHttpServletResponse(), mock(FilterChain.class));

        // Same socket, different spoofed headers, ONE bucket. Reading X-Forwarded-For here
        // would let any client mint a fresh bucket per request and the limiter would be
        // decorative while every other test still passed.
        assertEquals(List.of("public-share|1.2.3.4", "public-share|1.2.3.4"), store.keys);
    }

    @Test
    void disabledPropertiesSkipTheStoreEntirely() throws Exception {
        RecordingStore store = new RecordingStore();
        store.deny = true;
        RateLimitProperties off = new RateLimitProperties(false, PROPS.policies());
        RateLimitFilter filter = new RateLimitFilter(off, store, new ObjectMapper());
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("/public/q/tok", "1.2.3.4"), res, chain);

        verify(chain).doFilter(any(), any());
        assertTrue(store.keys.isEmpty());
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && ./gradlew :test --tests 'com.easycrm.platform.ratelimit.RateLimitFilterTest'`
Expected: compilation failure — `RateLimitFilter` does not exist.

- [ ] **Step 3: Write the filter**

```java
package com.easycrm.platform.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Per-IP token bucket, in front of everything.
 *
 * <p><b>Ordering is a correctness requirement.</b> This filter runs BEFORE Spring
 * Security (see RateLimitConfig). Two reasons: work must be capped before it is done,
 * and on the auth routes the traffic worth limiting is traffic that FAILS
 * authentication — behind the security chain, a credential-stuffing attempt would
 * short-circuit to 401 and this filter would never see it.
 *
 * <p>Running that early means there is no Authentication and no TenantContext, so the
 * only available key is the client address. That is exactly the key the design wants
 * (spec section 3): a share link is meant to be forwarded, so a per-token bucket would
 * miss token-space scans while denying legitimate recipients.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitProperties properties;
    private final RateLimitStore store;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimitProperties properties, RateLimitStore store,
                           ObjectMapper objectMapper) {
        this.properties = properties;
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {
        if (!properties.enabled()) {
            chain.doFilter(request, response);
            return;
        }
        Optional<RateLimitPolicy> policy = properties.policyFor(request.getRequestURI());
        if (policy.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        RateLimitPolicy p = policy.get();
        // getRemoteAddr() ONLY. X-Forwarded-For is client-supplied: reading it here would
        // let any caller mint a fresh bucket per request. Behind a trusted proxy the right
        // fix is server.forward-headers-strategy=framework, which is a deployment
        // statement and fixes getRemoteAddr() for every consumer at once.
        RateLimitStore.Decision decision = store.tryConsume(
            p.name() + "|" + request.getRemoteAddr(), p);

        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        reject(response, RateLimitPolicy.retryAfterSeconds(decision.nanosToWaitForRefill()));
    }

    /**
     * The 429 body is written here rather than by ApiExceptionHandler because an exception
     * thrown from a servlet filter never reaches @RestControllerAdvice. This is a knowing
     * duplication of the error envelope in exactly one place; RateLimitIntegrationTest
     * asserts the two shapes agree so they cannot drift silently.
     *
     * <p>No X-RateLimit-* headers: nothing consumes them and they advertise the limit.
     */
    private void reject(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        objectMapper.writeValue(response.getOutputStream(), Map.of("error", Map.of(
            "code", "RATE_LIMITED",
            "message", "too many requests; please retry shortly")));
    }
}
```

- [ ] **Step 4: Run the test and watch it pass**

Run: `cd backend && ./gradlew :test --tests 'com.easycrm.platform.ratelimit.RateLimitFilterTest'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/src/main/java/com/easycrm/platform/ratelimit/RateLimitFilter.java backend/src/test/java/com/easycrm/platform/ratelimit/RateLimitFilterTest.java
git commit -m "feat: per-IP rate-limit filter with a 429 + Retry-After contract

Keys on getRemoteAddr() and deliberately ignores X-Forwarded-For: the header is
client-supplied, so reading it would let any caller mint a fresh bucket per
request and leave the limiter decorative while tests stayed green. A test pins
that two different forwarded headers from one socket share a bucket.

The 429 envelope is written in the filter because an exception thrown from a
servlet filter never reaches @RestControllerAdvice."
```

---

## Task 4: Wiring, configuration, and the test-harness default

This task makes the filter real. It also disables the limiter across the existing suite — read Step 3's
comment before assuming that is mere tidiness.

**Files:**
- Create: `backend/src/main/java/com/easycrm/platform/ratelimit/RateLimitConfig.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/java/com/easycrm/support/IntegrationTest.java`
- Test: `backend/src/test/java/com/easycrm/platform/ratelimit/RateLimitDefaultsTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1–3.
- Produces: beans `RateLimitStore`, `RateLimitFilter`, and
  `FilterRegistrationBean<RateLimitFilter>` at order `-110`.

- [ ] **Step 1: Write the configuration class**

```java
package com.easycrm.platform.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.BeanIds;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    /**
     * Ahead of Spring Security, whose chain registers at
     * SecurityProperties.DEFAULT_FILTER_ORDER (-100). Anything less than that runs first.
     * If this number ever drifts above -100, the auth-route limit silently stops seeing
     * failed logins — RateLimitIntegrationTest's ordering test is what catches that.
     */
    public static final int FILTER_ORDER = -110;

    @Bean
    @ConditionalOnMissingBean(RateLimitStore.class)
    RateLimitStore rateLimitStore() {
        return new InMemoryRateLimitStore();
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            RateLimitProperties properties, RateLimitStore store, ObjectMapper objectMapper) {
        FilterRegistrationBean<RateLimitFilter> registration =
            new FilterRegistrationBean<>(new RateLimitFilter(properties, store, objectMapper));
        registration.setOrder(FILTER_ORDER);
        registration.addUrlPatterns("/*");
        registration.setName("rateLimitFilter");
        return registration;
    }
}
```

If `org.springframework.security.config.BeanIds` turns out to be unused after you write this, drop the
import — it is listed only because some editors auto-add it when referencing security ordering.

- [ ] **Step 2: Add the policies to `application.yml`**

Insert under the existing `easycrm:` block, after `public-base-url`:

```yaml
  # Per-IP token buckets, applied BEFORE authentication. Numbers are starting values,
  # not measured ones: no frontend and no production traffic exist yet. They are config
  # precisely so they can be retuned without a release.
  rate-limit:
    enabled: true
    policies:
      # The only unauthenticated route, and the most expensive one: a PDF render per hit.
      # Same pattern SecurityConfig permits, so the limited set and the publicly reachable
      # set are the same set by construction.
      - name: public-share
        path: /public/q/*
        capacity: 60
        refill-period: 1h
      # Credential stuffing. Well above any real office's login + 15-minute refresh
      # traffic, far below what makes stuffing worthwhile.
      - name: auth
        path: /api/v1/auth/**
        capacity: 30
        refill-period: 1m
```

Then add this comment directly above the `spring:` block at the top of the file:

```yaml
# NOTE: the rate limiter keys on the socket address (getRemoteAddr). If this app is ever
# deployed behind a load balancer or reverse proxy, set
#   server.forward-headers-strategy: framework
# so X-Forwarded-For is honoured for every consumer at once. Do NOT have application code
# read that header directly — it is client-supplied, and trusting it unconditionally lets
# any caller mint a fresh rate-limit bucket per request. Off by default because nothing
# trusted sits in front of this app today.
```

- [ ] **Step 3: Disable the limiter in the shared test harness**

In `backend/src/test/java/com/easycrm/support/IntegrationTest.java`, inside the `props` method, after
the datasource registrations:

```java
        // The limiter is OFF for the suite at large, and this is correctness, not tidiness.
        // Every @SpringBootTest sharing this configuration shares ONE cached context, hence
        // one RateLimitStore bean, and every MockMvc request originates from the same
        // loopback address. Auth-touching requests from ALL test classes would therefore
        // accumulate into a single bucket and blow the 30/minute auth policy partway
        // through a suite that runs in ~12s — failing on the strength of how many other
        // tests ran first. Rate-limit tests turn it back on with their own tiny limits.
        registry.add("easycrm.rate-limit.enabled", () -> "false");
```

- [ ] **Step 4: Write the defaults test**

Create `backend/src/test/java/com/easycrm/platform/ratelimit/RateLimitDefaultsTest.java`:

```java
package com.easycrm.platform.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the shipped configuration itself, not the mechanism.
 *
 * <p>IntegrationTest disables the limiter suite-wide, which means no other test in the
 * codebase would notice if the production default flipped to false. This one reads
 * application.yml directly, so "off in tests" cannot quietly become "off everywhere".
 */
class RateLimitDefaultsTest {

    private static RateLimitProperties shippedProperties() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
            .load("application.yml", new ClassPathResource("application.yml"));
        StandardEnvironment env = new StandardEnvironment();
        sources.forEach(s -> env.getPropertySources().addLast(s));
        return new Binder(ConfigurationPropertySources.get(env))
            .bind("easycrm.rate-limit", RateLimitProperties.class).orElseThrow();
    }

    @Test
    void productionConfigShipsTheLimiterEnabled() throws Exception {
        assertTrue(shippedProperties().enabled(),
            "application.yml must ship enabled:true — the test harness disables it "
                + "separately, and nothing else would catch a production default of false");
    }

    @Test
    void bothPublicAndAuthRoutesAreCovered() throws Exception {
        RateLimitProperties props = shippedProperties();

        RateLimitPolicy share = props.policyFor("/public/q/some-token").orElseThrow(
            () -> new AssertionError("the public share route is unprotected"));
        assertEquals("public-share", share.name());
        assertEquals(60, share.capacity());
        assertEquals(Duration.ofHours(1), share.refillPeriod());

        RateLimitPolicy auth = props.policyFor("/api/v1/auth/login").orElseThrow(
            () -> new AssertionError("the login route is unprotected"));
        assertEquals("auth", auth.name());
        assertEquals(30, auth.capacity());
        assertEquals(Duration.ofMinutes(1), auth.refillPeriod());

        assertTrue(props.policyFor("/api/v1/customers").isEmpty(),
            "authenticated API routes are deliberately unlimited for now");
        assertTrue(props.policyFor("/actuator/health").isEmpty(),
            "throttling health checks would take the app out of its own load balancer");
    }
}
```

- [ ] **Step 5: Run it**

Run: `cd backend && ./gradlew :test --tests 'com.easycrm.platform.ratelimit.RateLimitDefaultsTest'`
Expected: PASS, 2 tests. If binding fails, the likely cause is the `policies` list not binding into the
record's `PathPattern` component — `RateLimitPolicy` has a 4-arg constructor for exactly this reason,
and Boot binds to the canonical constructor. If Boot picks the 5-arg canonical constructor and fails on
`PathPattern`, add `@ConstructorBinding` to the 4-arg constructor and note the deviation.

- [ ] **Step 6: Run the full suite — nothing may regress**

Run: `cd backend && ./gradlew clean test`
Expected: BUILD SUCCESSFUL. Count tests with the `HANDOFF.md` §0 snippet; expect **264 + 16 = 280**,
0 failures, 0 errors. If any pre-existing test now fails, the cause is almost certainly the filter
running where the harness did not expect it — do not "fix" the old test; report it.

- [ ] **Step 7: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/src/main/java/com/easycrm/platform/ratelimit/RateLimitConfig.java backend/src/main/resources/application.yml backend/src/test/java/com/easycrm/support/IntegrationTest.java backend/src/test/java/com/easycrm/platform/ratelimit/RateLimitDefaultsTest.java
git commit -m "feat: register the rate-limit filter ahead of Spring Security

Order -110 beats Spring Security's -100 so the limiter caps work before it is
done, and so the auth policy actually sees failed logins rather than watching
them short-circuit to 401 behind the security chain.

The limiter is disabled for the shared test harness: one cached context means
one store bean, and every MockMvc request comes from the same loopback address,
so auth traffic from all test classes would accumulate into a single bucket and
fail tests on the strength of how many others ran first. A separate test reads
application.yml to assert the shipped default stays enabled."
```

---

## Task 5: End-to-end proof, including the ordering test

The ordering test is this slice's equivalent of the previous slice's probe table: it is the one
assertion that fails if the mechanism is installed in the wrong place, which is the failure mode that
otherwise looks exactly like success.

**Files:**
- Test: `backend/src/test/java/com/easycrm/platform/ratelimit/RateLimitIntegrationTest.java`

**Interfaces:**
- Consumes: everything above, plus `com.easycrm.support.IntegrationTest` and
  `com.easycrm.support.TestTokens`.
- Produces: nothing.

- [ ] **Step 1: Write the integration test**

`@TestPropertySource` gives this class its own tiny limits and its own context (a distinct property set
means a distinct cached context, so these buckets never touch another test's). The extra
`api-protected` policy exists solely to make the ordering assertion possible: no shipped policy covers
a protected route, and the proof needs one that does.

```java
package com.easycrm.platform.ratelimit;

import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "easycrm.rate-limit.enabled=true",
    // Tiny limits: the point is the mechanism, not looping sixty times.
    "easycrm.rate-limit.policies[0].name=public-share",
    "easycrm.rate-limit.policies[0].path=/public/q/*",
    "easycrm.rate-limit.policies[0].capacity=2",
    "easycrm.rate-limit.policies[0].refill-period=1h",
    // Not a shipped policy. It exists so the ordering proof below has a PROTECTED route
    // to aim at — that assertion is impossible without one.
    "easycrm.rate-limit.policies[1].name=api-protected",
    "easycrm.rate-limit.policies[1].path=/api/v1/customers/**",
    "easycrm.rate-limit.policies[1].capacity=2",
    "easycrm.rate-limit.policies[1].refill-period=1h"
})
class RateLimitIntegrationTest extends IntegrationTest {

    @Autowired MockMvc mvc;

    /** Distinct source addresses, so each test gets a fresh bucket in the shared store. */
    private static RequestPostProcessor from(String ip) {
        return request -> { request.setRemoteAddr(ip); return request; };
    }

    private static String someToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Test
    void thirdRequestToThePublicRouteIs429WithRetryAfterAndTheHouseEnvelope() throws Exception {
        String token = someToken();
        // An unknown token 404s, which is fine: the limiter runs before the handler, so
        // the response status below proves the bucket drained regardless of the outcome.
        mvc.perform(get("/public/q/" + token).with(from("203.0.113.10")))
            .andExpect(status().isNotFound());
        mvc.perform(get("/public/q/" + token).with(from("203.0.113.10")))
            .andExpect(status().isNotFound());

        var third = mvc.perform(get("/public/q/" + token).with(from("203.0.113.10")))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
            .andExpect(jsonPath("$.error.message").exists())
            .andReturn();

        assertTrue(Integer.parseInt(third.getResponse().getHeader("Retry-After")) >= 1,
            "Retry-After must never be 0 — that invites an immediate retry");
    }

    @Test
    void spoofedForwardedHeadersShareOneBucket() throws Exception {
        String token = someToken();
        mvc.perform(get("/public/q/" + token).with(from("203.0.113.20"))
            .header("X-Forwarded-For", "1.1.1.1"));
        mvc.perform(get("/public/q/" + token).with(from("203.0.113.20"))
            .header("X-Forwarded-For", "2.2.2.2"));

        // If the filter trusted the header, each request would have minted its own bucket
        // and this third one would sail through — a bypass any client could perform.
        mvc.perform(get("/public/q/" + token).with(from("203.0.113.20"))
                .header("X-Forwarded-For", "3.3.3.3"))
            .andExpect(status().isTooManyRequests());
    }

    @Test
    void limiterRunsBeforeSpringSecurity() throws Exception {
        // No Authorization header: this route answers 401 normally.
        mvc.perform(get("/api/v1/customers/" + UUID.randomUUID()).with(from("203.0.113.30")))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/customers/" + UUID.randomUUID()).with(from("203.0.113.30")))
            .andExpect(status().isUnauthorized());

        // Once the bucket is drained the answer must become 429, NOT 401. A 401 here means
        // the filter is running behind the security chain — in which case the auth policy
        // would never see a failed login, and every other test in this class would still
        // pass. This is the assertion that catches a misordered filter.
        mvc.perform(get("/api/v1/customers/" + UUID.randomUUID()).with(from("203.0.113.30")))
            .andExpect(status().isTooManyRequests());
    }

    @Test
    void unmatchedPathsAreNeverLimited() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/actuator/health").with(from("203.0.113.40")))
                .andExpect(status().isOk());
        }
    }

    @Test
    void distinctClientsDoNotShareAnAllowance() throws Exception {
        String token = someToken();
        mvc.perform(get("/public/q/" + token).with(from("203.0.113.50")));
        mvc.perform(get("/public/q/" + token).with(from("203.0.113.50")));
        mvc.perform(get("/public/q/" + token).with(from("203.0.113.50")))
            .andExpect(status().isTooManyRequests());

        // Same token, different recipient: a forwarded share link must still open. This is
        // the behaviour a per-token bucket would have broken (spec section 3).
        mvc.perform(get("/public/q/" + token).with(from("203.0.113.51")))
            .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run it**

Run: `cd backend && ./gradlew :test --tests 'com.easycrm.platform.ratelimit.RateLimitIntegrationTest'`
Expected: PASS, 5 tests.

If `limiterRunsBeforeSpringSecurity` fails with 401 on the third request, the filter is behind Spring
Security: check `RateLimitConfig.FILTER_ORDER` is below `-100`. If every test 404s or 200s where a 429
was expected, the filter is not registered with MockMvc at all — confirm the `FilterRegistrationBean`
is a bean of the application context.

- [ ] **Step 3: Run the full suite**

Run: `cd backend && ./gradlew clean test`
Expected: BUILD SUCCESSFUL, **285 tests**, 0 failures, 0 errors (264 baseline + 4 + 5 + 5 + 2 + 5).
Report the actual number; if it differs from 285, reconcile before committing rather than adjusting
this line.

- [ ] **Step 4: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/src/test/java/com/easycrm/platform/ratelimit/RateLimitIntegrationTest.java
git commit -m "test: end-to-end rate limiting, including the filter-ordering proof

The ordering test is the one that matters: a drained bucket on a protected route
must answer 429, not 401. A 401 there means the filter sits behind Spring
Security, in which case the auth policy would never observe a failed login and
every other test in the class would still pass.

Also pins that a forwarded share link still opens for a second recipient, which
is the behaviour a per-token bucket would have broken."
```

---

## Task 6: Documentation obligations

Required by CLAUDE.md as part of this change, not "later". Do not skip on the grounds that the code
works.

**Files:**
- Modify: `docs/superpowers/engineering-challenges.md`
- Modify: `docs/superpowers/annotations-reference.md`
- Modify: `docs/superpowers/HANDOFF.md`
- Modify: `/Users/divyam/Documents/dsa/good-repos/CATALOG.md`

- [ ] **Step 1: Append challenge 38**

Append above the `<!-- Append new challenges below. Template:` comment, following the existing
Problem → Solution → Lesson shape. The subject is **the two inversions**: a rate limiter keyed on
attacker-controlled input becomes a memory-exhaustion vector unless its storage is bounded, and a
limiter that reads `X-Forwarded-For` "to be more accurate" becomes bypassable by the very clients it
limits. Both changes make the component *look* more correct. Both leave every obvious test green. The
lesson to draw: when a security control takes attacker-controlled input as a key, ask what the
attacker can make the control itself do — and prefer a framework-level mechanism
(`forward-headers-strategy`) over application code that trusts a header, because the framework's
version is a deployment assertion while yours is a guess.

- [ ] **Step 2: Add annotation rows**

Add rows to `docs/superpowers/annotations-reference.md` for any of these not already present, in the
file's existing column format (origin, purpose, meta-annotation composition):
`@ConfigurationProperties`, `@EnableConfigurationProperties`, `@TestPropertySource`,
`@ConditionalOnMissingBean`, `@DefaultValue`. Check first — several may already exist from P0.

- [ ] **Step 3: Update the handoff**

In `docs/superpowers/HANDOFF.md`:
- §0 item 1: new baseline test count (the number Task 5 Step 3 actually produced).
- §0 in-flight line and §3: record this slice, its branch, and its commit range.
- §6 environment: the test-count line mentioning ~12s.
- §8: strike backlog item #3 (rate limiting) as DONE. **PF19 stays open** — this slice closes the
  route's exposure to abuse, not its lack of entitlement metering; say so explicitly so the next
  reader does not infer PF19 is finished.
- §8: add a short forward-looking note that multi-instance deployment requires the Redis store first,
  since that constraint now lives in code rather than only in the spec.

- [ ] **Step 4: Add Bucket4j to the good-repos catalog**

The catalog was searched during design and had no rate-limiting entry; the near-match
(`resilience4j`) is a downstream-call throttle with no keyed buckets and no distributed backend.
Fetch the README first — do not write the entry from memory — then append under a suitable section and
update **both** tables in the tag index:

```
### bucket4j/bucket4j
https://github.com/bucket4j/bucket4j
**Tags:** `resilience` `library` `java`
**Lang:** Java
**Does:** Token-bucket rate limiting. Local in-memory buckets and distributed ones
over Redis/Hazelcast/Infinispan/JDBC behind one API, so the same limit definition
survives the move from one instance to many.
**Reach for it when:** you need to cap request rates per key (per IP, per tenant,
per API key) in a JVM service and expect to outgrow a single instance. Note the
artifact id is JDK-qualified since 8.10 — bucket4j_jdk17-core, not bucket4j-core.
```

- [ ] **Step 5: Commit the docs**

```bash
cd /Users/divyam/Documents/easy-crm
git add docs/superpowers/engineering-challenges.md docs/superpowers/annotations-reference.md docs/superpowers/HANDOFF.md
git commit -m "docs: challenge 38, annotation rows, and the handoff for rate limiting"
```

Commit the catalog separately — it lives in a different repository:

```bash
cd /Users/divyam/Documents/dsa/good-repos && git add CATALOG.md && git commit -m "Add bucket4j"
```

If that directory is not a git repository or has no remote you should push to, leave the edit
uncommitted and say so in your report rather than guessing.

---

## Task 7: Whole-branch review and merge

- [ ] **Step 1: Request a whole-branch code review**

Use `superpowers:requesting-code-review` against the full branch diff, not the individual tasks. The
areas most worth an adversarial read: the filter-ordering constant, the bucket key construction, the
Caffeine bound, and whether the 429 envelope really matches `ApiExceptionHandler`'s shape.

- [ ] **Step 2: Address findings**

Use `superpowers:receiving-code-review`. Findings deliberately deferred go into the `HANDOFF.md`
deferred-Minor backlog with their reasoning, in the style of items 23–24.

- [ ] **Step 3: Final verification before merge**

Run: `cd backend && ./gradlew clean test`
Expected: BUILD SUCCESSFUL, 0 failures, 0 errors, at the count Task 5 established. Do not claim
completion without this output in hand.

- [ ] **Step 4: Finish the branch**

Use `superpowers:finishing-a-development-branch`. House pattern from the last two slices: `--no-ff`
merge to `main`, then a follow-up docs commit recording the merge hash and clearing §0's in-flight
line, then delete the branch.

---

## Self-Review Notes

Checked against the spec, section by section:

| Spec section | Covered by |
|---|---|
| §3 per-IP keying | Task 3 (key construction, XFF test), Task 5 (`distinctClientsDoNotShareAnAllowance`) |
| §4.1 filter position | Task 4 (`FILTER_ORDER`), Task 5 (`limiterRunsBeforeSpringSecurity`) |
| §4.2 components | Tasks 1–4, one type per file as tabulated |
| §4.3 storage + eviction | Task 2 (`bucketStorageIsBounded`, `MAX_BUCKETS`) |
| §4.4 IP resolution trap | Task 3 (filter), Task 4 (yml comment), Tasks 3+5 (spoof tests) |
| §4.5 response contract | Task 3 (`reject`), Task 5 (envelope + `Retry-After` assertions) |
| §5 policies and values | Task 4 (yml), Task 4 (`RateLimitDefaultsTest` pins the numbers) |
| §5.1 test-harness default | Task 4 Step 3 + `RateLimitDefaultsTest` |
| §6 testing table | Tasks 1–5; every row has a named test |
| §7 documentation | Task 6 |
| §8 out-of-scope | Nothing in this plan implements Redis, entitlements, expiry/revoke, per-token buckets, or `/api/**` limits |

Type consistency verified: `RateLimitStore.Decision(boolean allowed, long nanosToWaitForRefill)` is
constructed in Task 2 and destructured in Task 3 under the same names;
`RateLimitPolicy.retryAfterSeconds` is defined in Task 1 and called in Task 3;
`RateLimitProperties.policyFor` is defined in Task 1 and called in Tasks 3 and 4.

Two places where an executor may need to deviate, each with explicit instructions rather than a
silent guess: Caffeine's BOM management (Task 1 Step 2) and constructor binding for the `PathPattern`
component (Task 4 Step 5).
