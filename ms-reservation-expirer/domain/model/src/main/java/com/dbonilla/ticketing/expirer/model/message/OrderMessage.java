package com.dbonilla.ticketing.expirer.model.message;

import com.dbonilla.ticketing.expirer.model.execution.ExecutionContext;
import com.dbonilla.ticketing.expirer.model.order.Order;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Thin order message re-published by the reconciliation (only identifiers; consumers re-read the order).
 */
public record OrderMessage(UUID messageId, OrderMessageType type, String orderId, String eventId,
                           Instant occurredAt, String traceId) {

    public OrderMessage {
        Objects.requireNonNull(messageId, "messageId is required");
        Objects.requireNonNull(type, "type is required");
        Objects.requireNonNull(orderId, "orderId is required");
        Objects.requireNonNull(eventId, "eventId is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        Objects.requireNonNull(traceId, "traceId is required");
    }

    /** A re-published message always gets a new message id. */
    public static OrderMessage republish(Order order, OrderMessageType type, ExecutionContext context) {
        return new OrderMessage(UUID.randomUUID(), type, order.orderId(), order.eventId(),
                context.now(), context.traceId());
    }
}
