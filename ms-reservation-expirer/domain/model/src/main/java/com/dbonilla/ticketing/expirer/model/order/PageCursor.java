package com.dbonilla.ticketing.expirer.model.order;

import java.time.Instant;
import java.util.Objects;

/**
 * Position after which the next page of overdue orders starts (last key of the previous page).
 */
public record PageCursor(String orderId, OrderStatus status, Instant deadlineAt) {

    public PageCursor {
        Objects.requireNonNull(orderId, "orderId is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(deadlineAt, "deadlineAt is required");
    }
}
