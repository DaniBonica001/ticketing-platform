package com.dbonilla.ticketing.api.api.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * One token bucket per key, kept in a size-bounded cache so memory stays bounded whatever the number of
 * clients. Idle buckets are evicted (a full bucket is equivalent to a missing one).
 */
final class RateLimiter {

    private final int burst;
    private final double tokensPerSecond;
    private final LongSupplier nanoClock;
    private final Cache<String, TokenBucket> buckets;

    RateLimiter(RateLimitProperties.Limit limit, long maxKeys, Duration idleTimeout, LongSupplier nanoClock) {
        this.burst = limit.burst();
        this.tokensPerSecond = limit.requestsPerSecond();
        this.nanoClock = nanoClock;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(maxKeys)
                .expireAfterAccess(idleTimeout)
                .build();
    }

    RateLimitDecision tryAcquire(String key) {
        long now = nanoClock.getAsLong();
        return buckets.get(key, ignored -> new TokenBucket(burst, tokensPerSecond, now)).tryConsume(now);
    }

    long trackedKeys() {
        buckets.cleanUp();
        return buckets.estimatedSize();
    }
}
