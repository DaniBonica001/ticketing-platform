package com.dbonilla.ticketing.api.api.ratelimit;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Caps how many long-lived requests (SSE streams) are open at the same time, per key and in total. Memory is
 * bounded by the global cap: a key is tracked only while it holds at least one slot.
 */
final class ConcurrencyLimiter {

    private final int perKey;
    private final int global;
    private final AtomicInteger active = new AtomicInteger();
    private final ConcurrentHashMap<String, Integer> activeByKey = new ConcurrentHashMap<>();

    ConcurrencyLimiter(RateLimitProperties.StreamLimit limit) {
        this.perKey = limit.perClient();
        this.global = limit.global();
    }

    /** A slot that must be released exactly once when the request ends; empty when a cap is reached. */
    Optional<Permit> tryAcquire(String key) {
        if (!tryIncrementGlobal()) {
            return Optional.empty();
        }
        AtomicBoolean admitted = new AtomicBoolean();
        activeByKey.compute(key, (ignored, count) -> {
            int current = count == null ? 0 : count;
            if (current >= perKey) {
                return count;
            }
            admitted.set(true);
            return current + 1;
        });
        if (!admitted.get()) {
            active.decrementAndGet();
            return Optional.empty();
        }
        return Optional.of(new Permit(key));
    }

    private boolean tryIncrementGlobal() {
        while (true) {
            int current = active.get();
            if (current >= global) {
                return false;
            }
            if (active.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void release(String key) {
        activeByKey.computeIfPresent(key, (ignored, count) -> count <= 1 ? null : count - 1);
        active.decrementAndGet();
    }

    int active() {
        return active.get();
    }

    int active(String key) {
        return activeByKey.getOrDefault(key, 0);
    }

    int trackedKeys() {
        return activeByKey.size();
    }

    final class Permit {

        private final String key;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(String key) {
            this.key = key;
        }

        /** Idempotent: only the first call frees the slot. */
        void release() {
            if (released.compareAndSet(false, true)) {
                ConcurrencyLimiter.this.release(key);
            }
        }
    }
}
