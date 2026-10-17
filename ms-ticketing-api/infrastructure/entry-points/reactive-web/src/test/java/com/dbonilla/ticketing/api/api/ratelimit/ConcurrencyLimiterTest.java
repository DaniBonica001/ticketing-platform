package com.dbonilla.ticketing.api.api.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConcurrencyLimiterTest {

    @Test
    void capsConcurrentSlotsPerKey() {
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(new RateLimitProperties.StreamLimit(2, 10));

        Optional<ConcurrencyLimiter.Permit> first = limiter.tryAcquire("a");
        Optional<ConcurrencyLimiter.Permit> second = limiter.tryAcquire("a");
        Optional<ConcurrencyLimiter.Permit> third = limiter.tryAcquire("a");

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(third).isEmpty();
        assertThat(limiter.tryAcquire("b")).isPresent();
        assertThat(limiter.active()).isEqualTo(3);
    }

    @Test
    void capsConcurrentSlotsGlobally() {
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(new RateLimitProperties.StreamLimit(5, 2));

        assertThat(limiter.tryAcquire("a")).isPresent();
        assertThat(limiter.tryAcquire("b")).isPresent();
        assertThat(limiter.tryAcquire("c")).isEmpty();
        assertThat(limiter.active()).isEqualTo(2);
        assertThat(limiter.trackedKeys()).as("a rejected key keeps no state").isEqualTo(2);
    }

    @Test
    void releasingFreesTheSlotOnlyOnceAndForgetsIdleKeys() {
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(new RateLimitProperties.StreamLimit(1, 1));
        ConcurrencyLimiter.Permit permit = limiter.tryAcquire("a").orElseThrow();

        permit.release();
        permit.release();

        assertThat(limiter.active()).isZero();
        assertThat(limiter.trackedKeys()).isZero();
        assertThat(limiter.tryAcquire("a")).isPresent();
        assertThat(limiter.tryAcquire("b")).as("the double release did not free an extra slot").isEmpty();
    }

    @Test
    void neverExceedsTheCapsUnderContention() throws Exception {
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(new RateLimitProperties.StreamLimit(3, 20));
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            List<Future<Optional<ConcurrencyLimiter.Permit>>> attempts = IntStream.range(0, 400)
                    .mapToObj(i -> pool.submit(() -> limiter.tryAcquire("key-" + (i % 10))))
                    .toList();
            long granted = 0;
            for (Future<Optional<ConcurrencyLimiter.Permit>> attempt : attempts) {
                granted += attempt.get().isPresent() ? 1 : 0;
            }

            assertThat(granted).isPositive().isLessThanOrEqualTo(20);
            assertThat(limiter.active()).isEqualTo((int) granted);
            IntStream.range(0, 10).forEach(key ->
                    assertThat(limiter.active("key-" + key)).isLessThanOrEqualTo(3));
        }
    }

    @Test
    void streamLimitsMustBePositive() {
        assertThatThrownBy(() -> new RateLimitProperties.StreamLimit(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitProperties.StreamLimit(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
