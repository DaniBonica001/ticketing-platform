package com.dbonilla.ticketing.processor.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BusinessMetrics metrics = new BusinessMetrics(registry);

    @Test
    void countsTransitionsByFromAndTo() {
        metrics.transitionApplied("QUEUED", "RESERVED");
        metrics.transitionApplied("QUEUED", "RESERVED");

        assertThat(registry.get(BusinessMetrics.TRANSITIONS).tag("from", "QUEUED").tag("to", "RESERVED")
                .counter().count()).isEqualTo(2.0);
    }

    @Test
    void countsRejectionsByReasonAndConflicts() {
        metrics.orderRejected("SOLD_OUT");
        metrics.transactionConflict();

        assertThat(registry.get(BusinessMetrics.REJECTED).tag("reason", "SOLD_OUT").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(BusinessMetrics.TRANSACTION_CONFLICTS).counter().count()).isEqualTo(1.0);
    }

    @Test
    void recordsProcessingDurationByTypeAndOutcome() {
        metrics.processingCompleted("OrderPlaced", "RESERVED", Duration.ofMillis(25));

        assertThat(registry.get(BusinessMetrics.PROCESSING_DURATION).tag("type", "OrderPlaced")
                .tag("outcome", "RESERVED").timer().count()).isEqualTo(1);
    }
}
