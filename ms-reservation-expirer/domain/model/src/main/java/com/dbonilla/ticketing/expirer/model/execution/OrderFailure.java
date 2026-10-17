package com.dbonilla.ticketing.expirer.model.execution;

import com.dbonilla.ticketing.expirer.model.order.OrderStatus;

import java.util.Objects;

/**
 * An operation that failed during a run. The run continues; the next run will find the order again.
 *
 * @param orderId order involved, {@code null} when a whole page query failed
 */
public record OrderFailure(String operation, OrderStatus status, String orderId, String errorType, String errorMessage) {

    public OrderFailure {
        Objects.requireNonNull(operation, "operation is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(errorType, "errorType is required");
    }

    public static OrderFailure of(String operation, OrderStatus status, String orderId, Throwable error) {
        return new OrderFailure(operation, status, orderId, error.getClass().getName(), error.getMessage());
    }
}
