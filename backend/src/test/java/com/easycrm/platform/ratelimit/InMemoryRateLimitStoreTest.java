package com.easycrm.platform.ratelimit;

import io.github.bucket4j.TimeMeter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

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

    /**
     * Pins the eviction window to the configured policies, not to a hardcoded constant.
     * Without this, retuning {@code public-share.refill-period} to something longer than
     * one hour (application.yml's comments actively invite this) would silently shrink
     * the *effective* limit: a bucket idles out at the old fixed window and comes back
     * FULL well before the configured refill period has actually elapsed.
     */
    @Test
    void evictionWindowTracksTheLongestConfiguredRefillPeriod() {
        RateLimitProperties sixHourPolicy = new RateLimitProperties(true, List.of(
            new RateLimitPolicy("public-share", "/public/q/*", 60, Duration.ofHours(6)),
            new RateLimitPolicy("auth", "/api/v1/auth/**", 30, Duration.ofMinutes(1))));

        InMemoryRateLimitStore store = new InMemoryRateLimitStore(sixHourPolicy, new FakeClock());

        assertEquals(Duration.ofHours(12), store.evictionWindow(),
            "twice the longest configured refill period (6h), not a hardcoded 2h — "
                + "otherwise a 6h-tuned policy is silently enforced as a 2h policy");
    }

    @Test
    void evictionWindowIsFlooredForATinyConfiguredRefillPeriod() {
        RateLimitProperties tinyPolicy = new RateLimitProperties(true, List.of(
            new RateLimitPolicy("test", "/public/q/*", 3, Duration.ofSeconds(1))));

        InMemoryRateLimitStore store = new InMemoryRateLimitStore(tinyPolicy, new FakeClock());

        assertEquals(InMemoryRateLimitStore.MIN_EVICTION_WINDOW, store.evictionWindow(),
            "twice a 1-second refill period is absurdly short; the floor must apply");
    }

    @Test
    void noArgConstructorsKeepTheirFixedWindowForUnitTestsThatSupplyNoProperties() {
        assertEquals(Duration.ofHours(2), new InMemoryRateLimitStore().evictionWindow());
        assertEquals(Duration.ofHours(2), new InMemoryRateLimitStore(new FakeClock()).evictionWindow());
    }
}
