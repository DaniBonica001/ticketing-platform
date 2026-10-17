package com.dbonilla.ticketing.api.model.ordertransition;

import com.dbonilla.ticketing.api.model.order.Order;
import com.dbonilla.ticketing.api.model.order.OrderStatus;
import com.dbonilla.ticketing.api.model.order.RejectionReason;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable audit row of an order state change.
 *
 * @param fromStatus absent ({@code null}) on creation
 * @param actor      component that applied the transition
 */
public record OrderTransition(String orderId, String eventId, OrderStatus fromStatus, OrderStatus toStatus,
                              int quantity, RejectionReason reason, String actor, Instant occurredAt) {

    public static final String API_ACTOR = "ticketing-api";

    public OrderTransition {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(toStatus, "toStatus");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    /** Audit row of an order creation applied by this API. */
    public static OrderTransition created(Order order) {
        return new OrderTransition(order.orderId(), order.eventId(), null, order.status(),
                order.quantity(), order.reason(), API_ACTOR, order.updatedAt());
    }

    /** Audit row of a transition applied by this API. */
    public static OrderTransition between(Order from, Order to) {
        return new OrderTransition(to.orderId(), to.eventId(), from.status(), to.status(),
                to.quantity(), to.reason(), API_ACTOR, to.updatedAt());
    }
}
