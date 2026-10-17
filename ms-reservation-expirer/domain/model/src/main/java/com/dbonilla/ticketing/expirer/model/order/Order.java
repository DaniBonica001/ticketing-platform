package com.dbonilla.ticketing.expirer.model.order;

import java.time.Instant;
import java.util.Objects;

/**
 * Projection of an order with a pending deadline, as read from the sparse status/deadline index.
 * Only non-final orders carry a deadline.
 */
public record Order(String orderId, String eventId, int quantity, OrderStatus status, Instant deadlineAt) {

    public Order {
        requireText(orderId, "orderId");
        requireText(eventId, "eventId");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(deadlineAt, "deadlineAt is required");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (status.isFinal()) {
            throw new IllegalArgumentException("a final order has no deadline");
        }
    }

    /** True when the order is still in {@code expected} and its deadline is strictly before {@code now}. */
    public boolean isOverdue(OrderStatus expected, Instant now) {
        return status == expected && deadlineAt.isBefore(now);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
