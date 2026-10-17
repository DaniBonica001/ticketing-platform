package com.dbonilla.ticketing.expirer.model.metrics;

import com.dbonilla.ticketing.expirer.model.metrics.gateways.RunMetricsPublisher;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunMetricsTest {

    @Test
    void acceptsZeroAndPositiveCounts() {
        RunMetrics metrics = new RunMetrics(0, 1, 2, 3);

        assertThat(metrics.republishedPendingConfirmation()).isEqualTo(2);
    }

    @Test
    void rejectsNegativeCounts() {
        assertThatThrownBy(() -> new RunMetrics(-1, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunMetrics(0, -1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunMetrics(0, 0, -1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunMetrics(0, 0, 0, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noopPublisherDoesNothing() {
        assertThatCode(() -> RunMetricsPublisher.NOOP.publish(new RunMetrics(1, 1, 1, 1))).doesNotThrowAnyException();
    }
}
