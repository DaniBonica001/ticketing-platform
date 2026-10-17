package com.dbonilla.ticketing.api.api.ratelimit;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lock-free token bucket. The state is an immutable snapshot replaced with compare-and-set.
 */
final class TokenBucket {

    private static final double ONE_TOKEN = 1.0;
    private static final double NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);

    private final double capacity;
    private final double tokensPerNano;
    private final AtomicReference<State> state;

    TokenBucket(int capacity, double tokensPerSecond, long nowNanos) {
        this.capacity = capacity;
        this.tokensPerNano = tokensPerSecond / NANOS_PER_SECOND;
        this.state = new AtomicReference<>(new State(capacity, nowNanos));
    }

    /** Takes one token; when none is left returns how long until the next one. */
    RateLimitDecision tryConsume(long nowNanos) {
        while (true) {
            State current = state.get();
            double tokens = Math.min(capacity,
                    current.tokens() + Math.max(0, nowNanos - current.nanos()) * tokensPerNano);
            if (tokens < ONE_TOKEN) {
                long waitNanos = (long) Math.ceil((ONE_TOKEN - tokens) / tokensPerNano);
                return RateLimitDecision.rejected(Duration.ofNanos(waitNanos));
            }
            if (state.compareAndSet(current, new State(tokens - ONE_TOKEN, nowNanos))) {
                return RateLimitDecision.allowed();
            }
        }
    }

    private record State(double tokens, long nanos) {
    }
}
