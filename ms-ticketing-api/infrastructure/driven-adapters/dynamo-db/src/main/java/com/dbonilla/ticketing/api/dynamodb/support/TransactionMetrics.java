package com.dbonilla.ticketing.api.dynamodb.support;

import com.dbonilla.ticketing.api.model.ordertransition.OrderTransition;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Business metrics of ADR-0007 emitted by this service.
 */
@Component
public class TransactionMetrics {

    static final String ORDERS_CREATED = "ticketing.orders.created";
    static final String ORDER_TRANSITIONS = "ticketing.orders.transitions";
    static final String TRANSACTION_CONFLICTS = "ticketing.dynamodb.transaction.conflicts";
    private static final String NO_STATUS = "NONE";

    private final MeterRegistry registry;

    public TransactionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void orderCreated(OrderTransition transition) {
        registry.counter(ORDERS_CREATED, "status", transition.toStatus().name()).increment();
        transitionApplied(transition);
    }

    public void transitionApplied(OrderTransition transition) {
        String from = transition.fromStatus() == null ? NO_STATUS : transition.fromStatus().name();
        registry.counter(ORDER_TRANSITIONS, "from", from, "to", transition.toStatus().name()).increment();
    }

    public void transactionConflict() {
        registry.counter(TRANSACTION_CONFLICTS).increment();
    }
}
