package com.dbonilla.ticketing.expirer.helpers.retry;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransientRetryTest {

    private static final RetrySettings FAST = new RetrySettings(3, Duration.ofMillis(1), Duration.ofMillis(2), 0.5);

    @Test
    void retriesTransientErrorsUntilSuccess() {
        AtomicInteger attempts = new AtomicInteger();
        Mono<String> call = Mono.defer(() -> attempts.incrementAndGet() < 3
                ? Mono.error(SdkClientException.create("reset"))
                : Mono.just("ok"));

        StepVerifier.create(call.retryWhen(TransientRetry.backoff(FAST))).expectNext("ok").verifyComplete();

        assertThat(attempts).hasValue(3);
    }

    @Test
    void neverRetriesConditionalFailures() {
        AtomicInteger attempts = new AtomicInteger();
        Mono<String> call = Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.error(ConditionalCheckFailedException.builder().message("condition").build());
        });

        StepVerifier.create(call.retryWhen(TransientRetry.backoff(FAST)))
                .verifyError(ConditionalCheckFailedException.class);

        assertThat(attempts).hasValue(1);
    }

    @Test
    void propagatesTheOriginalErrorWhenAttemptsAreExhausted() {
        AtomicInteger attempts = new AtomicInteger();
        Mono<String> call = Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.error(SdkClientException.create("still down"));
        });

        StepVerifier.create(call.retryWhen(TransientRetry.backoff(FAST))).verifyError(SdkClientException.class);

        assertThat(attempts).hasValue(4);
    }

    @Test
    void defaultsMatchTheConventions() {
        assertThat(RetrySettings.DEFAULT.maxRetries()).isEqualTo(3);
        assertThat(RetrySettings.DEFAULT.minBackoff()).isEqualTo(Duration.ofMillis(100));
        assertThat(RetrySettings.DEFAULT.maxBackoff()).isEqualTo(Duration.ofSeconds(2));
        assertThat(RetrySettings.DEFAULT.jitter()).isEqualTo(0.5);
    }

    @Test
    void rejectsInvalidSettings() {
        Duration min = Duration.ofMillis(1);
        assertThatThrownBy(() -> new RetrySettings(-1, min, min, 0.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrySettings(1, min, min, 1.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrySettings(1, min, min, -0.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrySettings(1, null, min, 0.5)).isInstanceOf(NullPointerException.class);
    }
}
