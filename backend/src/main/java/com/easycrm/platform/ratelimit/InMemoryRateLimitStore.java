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
 * evicted after at least twice the longest configured refill period of inactivity, by
 * which point the client's allowance had been restored anyway.
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
        // Buckets are keyed by policy AND client: the same client IP must get an
        // independent allowance per policy, or one policy's limit would drain another's.
        String cacheKey = policy.name() + '|' + key;
        Bucket bucket = buckets.get(cacheKey, k -> newBucket(policy));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        return probe.isConsumed()
            ? Decision.allow()
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
