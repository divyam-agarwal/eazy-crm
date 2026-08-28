package com.easycrm.platform.ratelimit;

/**
 * Where buckets live. The one seam Redis replaces.
 *
 * <p>The AWS re-platform must swap in a Redis-backed implementation BEFORE running more
 * than one application instance: with N instances each holding their own buckets, the
 * effective limit is N times the configured value. Correct at N=1, which is today.
 */
public interface RateLimitStore {

    /**
     * @param key identifies the client alone — today its socket address. The store
     *            namespaces per policy internally, so two policies never share a bucket
     *            regardless of what a caller passes here.
     */
    Decision tryConsume(String key, RateLimitPolicy policy);

    record Decision(boolean allowed, long nanosToWaitForRefill) {
        static Decision allow() { return new Decision(true, 0); }
    }
}
