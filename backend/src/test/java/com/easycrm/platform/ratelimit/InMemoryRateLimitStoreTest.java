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
