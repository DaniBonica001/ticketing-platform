package com.dbonilla.ticketing.api.resilience;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TransientRetryTest {

    private final TransientRetry retry = new TransientRetry(RetryProperties.defaults());

    private Mono<String> failing(AtomicInteger attempts, Throwable error, int failures) {
        return Mono.defer(() -> attempts.incrementAndGet() <= failures ? Mono.error(error) : Mono.just("ok"));
    }

    @Test
    void retriesTransientErrorsUntilSuccess() {
        AtomicInteger attempts = new AtomicInteger();

        StepVerifier.withVirtualTime(() -> failing(attempts, new IOException("io"), 2).retryWhen(retry.spec()))
                .thenAwait(Duration.ofSeconds(10))
                .expectNext("ok")
                .verifyComplete();
        assertThat(attempts).hasValue(3);
    }

    @Test
    void retriesTransactionConflicts() {
        AtomicInteger attempts = new AtomicInteger();

        StepVerifier.withVirtualTime(() -> failing(attempts, TransientErrorsTest.canceled("TransactionConflict"), 1)
                        .retryWhen(retry.spec()))
                .thenAwait(Duration.ofSeconds(10))
                .expectNext("ok")
                .verifyComplete();
        assertThat(attempts).hasValue(2);
    }

    @Test
    void propagatesTheOriginalErrorAfterThreeRetries() {
        AtomicInteger attempts = new AtomicInteger();
        IOException error = new IOException("io");

        StepVerifier.withVirtualTime(() -> failing(attempts, error, 10).retryWhen(retry.spec()))
                .thenAwait(Duration.ofSeconds(30))
                .expectErrorMatches(thrown -> thrown == error)
                .verify();
        assertThat(attempts).hasValue(4);
    }

    @Test
    void neverRetriesBusinessErrors() {
        AtomicInteger attempts = new AtomicInteger();

        StepVerifier.create(failing(attempts, TransientErrorsTest.canceled("ConditionalCheckFailed"), 10)
                        .retryWhen(retry.spec()))
                .verifyErrorMessage("canceled");
        assertThat(attempts).hasValue(1);
    }

    @Test
    void propertiesUseDocumentedDefaults() {
        RetryProperties defaults = RetryProperties.defaults();
        RetryProperties custom = new RetryProperties(5, Duration.ofMillis(10), Duration.ofSeconds(1), 0.1);

        assertThat(defaults.maxAttempts()).isEqualTo(3);
        assertThat(defaults.minBackoff()).isEqualTo(Duration.ofMillis(100));
        assertThat(defaults.maxBackoff()).isEqualTo(Duration.ofSeconds(2));
        assertThat(defaults.jitter()).isEqualTo(0.5);
        assertThat(custom.maxAttempts()).isEqualTo(5);
        assertThat(custom.jitter()).isEqualTo(0.1);
    }
}
