package com.dbonilla.ticketing.processor.model.processing;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessingValuesTest {

    @Test
    void retryPolicyValidatesItsBounds() {
        assertThat(new RetryPolicy(3, Duration.ofMillis(100), Duration.ofSeconds(2), 0.5).maxRetries()).isEqualTo(3);
        assertThatThrownBy(() -> new RetryPolicy(-1, Duration.ZERO, Duration.ZERO, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(1, Duration.ZERO, Duration.ZERO, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(1, Duration.ZERO, Duration.ZERO, -0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(1, null, Duration.ZERO, 0.5))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void reservationTtlMustBePositive() {
        assertThat(new ReservationSettings(Duration.ofMinutes(10)).reservationTtl()).hasMinutes(10);
        assertThatThrownBy(() -> new ReservationSettings(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReservationSettings(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReservationSettings(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void orderCommandRequiresIdentifiersButNotTraceId() {
        assertThat(new OrderCommand("m", "o", "e", null).traceId()).isNull();
        assertThatThrownBy(() -> new OrderCommand(null, "o", "e", "t")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderCommand("m", null, "e", "t")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderCommand("m", "o", null, "t")).isInstanceOf(NullPointerException.class);
    }
}
