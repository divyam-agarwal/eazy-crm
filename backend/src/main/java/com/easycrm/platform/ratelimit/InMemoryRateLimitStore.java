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
 * which point the client's allowance had been restored anyway. That "at least twice the
 * longest configured refill period" is not just a comment's promise — {@link
 * #evictionWindowFor} derives the actual eviction window from the live {@link
 * RateLimitProperties} at construction time, so retuning a policy's refill period in
 * configuration retunes the eviction window along with it. See engineering challenge #42.
 */
public class InMemoryRateLimitStore implements RateLimitStore {

    /** Roughly 50k distinct clients before the coldest are dropped. */
    public static final long MAX_BUCKETS = 50_000;

    /**
     * Floor under the computed eviction window. Without it, a policy configured with a
     * very short refill period (a test configuration today, conceivably a real policy
     * tomorrow) would produce a window so short that buckets could be evicted mid-burst,
     * defeating the "eviction is always generous" invariant above.
     */
    static final Duration MIN_EVICTION_WINDOW = Duration.ofMinutes(10);

    /**
     * Used only by the constructors below that take no {@link RateLimitProperties} —
     * i.e. the test-only constructors, which have no configuration to derive a window
     * from. The production path always goes through {@link #InMemoryRateLimitStore(RateLimitProperties)}
     * (see {@link RateLimitConfig}), which derives the window from configuration instead.
     */
    private static final Duration DEFAULT_EVICTION_WINDOW = Duration.ofHours(2);

    private final Cache<String, Bucket> buckets;
    private final TimeMeter timeMeter;
    private final Duration evictionWindow;

    /** Test-only: no configuration to derive an eviction window from, so the fixed default applies. */
    public InMemoryRateLimitStore() {
        this(DEFAULT_EVICTION_WINDOW, TimeMeter.SYSTEM_MILLISECONDS);
    }

    /**
     * Test-only, as above, with an injectable clock.
     * @param timeMeter injected so tests can advance time instead of sleeping.
     */
    public InMemoryRateLimitStore(TimeMeter timeMeter) {
        this(DEFAULT_EVICTION_WINDOW, timeMeter);
    }

    /** Production entry point: the eviction window is derived from the configured policies. */
    public InMemoryRateLimitStore(RateLimitProperties properties) {
        this(evictionWindowFor(properties), TimeMeter.SYSTEM_MILLISECONDS);
    }

    /** As above, with an injectable clock, for tests that want both real configuration and a fake clock. */
    public InMemoryRateLimitStore(RateLimitProperties properties, TimeMeter timeMeter) {
        this(evictionWindowFor(properties), timeMeter);
    }

    private InMemoryRateLimitStore(Duration evictionWindow, TimeMeter timeMeter) {
        this.evictionWindow = evictionWindow;
        this.timeMeter = timeMeter;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(MAX_BUCKETS)
                .expireAfterAccess(evictionWindow)
                .build();
    }

    /**
     * At least twice the longest configured refill period, floored at {@link
     * #MIN_EVICTION_WINDOW} so a small policy (a test configuration, or a future
     * short-lived policy) can never produce a window shorter than the floor.
     */
    static Duration evictionWindowFor(RateLimitProperties properties) {
        Duration longestRefillPeriod = properties.policies().stream()
                .map(RateLimitPolicy::refillPeriod)
                .max(Duration::compareTo)
                .orElse(Duration.ZERO);
        Duration doubled = longestRefillPeriod.multipliedBy(2);
        return doubled.compareTo(MIN_EVICTION_WINDOW) > 0 ? doubled : MIN_EVICTION_WINDOW;
    }

    @Override
    public Decision tryConsume(String key, RateLimitPolicy policy) {
        // Buckets are keyed by policy AND client: the same client IP must get an
        // independent allowance per policy, or one policy's limit would drain another's.
        // Thread-safety leans on two guarantees, both true today: Caffeine's
        // Cache.get(key, mappingFunction) is atomic per key, so concurrent first-touch
        // for the same cacheKey builds exactly one bucket, never a duplicate; and
        // Bucket4j's default bucket is itself safe under concurrent consumption. Swap
        // either the cache or the bucket implementation and re-check both hold.
        String cacheKey = policy.name() + '|' + key;
        Bucket bucket = buckets.get(cacheKey, k -> newBucket(policy));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        return probe.isConsumed() ? Decision.allow() : new Decision(false, probe.getNanosToWaitForRefill());
    }

    private Bucket newBucket(RateLimitPolicy policy) {
        return Bucket.builder()
                .addLimit(limit ->
                        limit.capacity(policy.capacity()).refillGreedy(policy.capacity(), policy.refillPeriod()))
                .withCustomTimePrecision(timeMeter)
                .build();
    }

    /** Test seam: how many buckets are currently retained. */
    public long bucketCount() {
        buckets.cleanUp();
        return buckets.estimatedSize();
    }

    /** Test seam: the eviction window this instance was actually built with. */
    Duration evictionWindow() {
        return evictionWindow;
    }
}
