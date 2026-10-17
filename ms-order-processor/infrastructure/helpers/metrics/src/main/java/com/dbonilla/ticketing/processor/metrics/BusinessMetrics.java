package com.dbonilla.ticketing.processor.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Business metrics of ADR-0007 emitted by the processor.
 */
@Component
public class BusinessMetrics {

    public static final String TRANSITIONS = "ticketing.orders.transitions";
    public static final String REJECTED = "ticketing.orders.rejected";
    public static final String TRANSACTION_CONFLICTS = "ticketing.dynamodb.transaction.conflicts";
    public static final String PROCESSING_DURATION = "ticketing.order.processing.duration";

    private final MeterRegistry registry;
    private final Counter transactionConflicts;

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.transactionConflicts = Counter.builder(TRANSACTION_CONFLICTS)
                .description("DynamoDB transactions cancelled because of a TransactionConflict")
                .register(registry);
    }

    public void transitionApplied(String from, String to) {
        Counter.builder(TRANSITIONS)
                .description("Order state transitions committed")
                .tag("from", from)
                .tag("to", to)
                .register(registry)
                .increment();
    }

    public void orderRejected(String reason) {
        Counter.builder(REJECTED)
                .description("Orders rejected, by reason")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    public void transactionConflict() {
        transactionConflicts.increment();
    }

    public void processingCompleted(String messageType, String outcome, Duration duration) {
        Timer.builder(PROCESSING_DURATION)
                .description("Time to process one queue message")
                .tag("type", messageType)
                .tag("outcome", outcome)
                .register(registry)
                .record(duration);
    }
}
