package com.dbonilla.ticketing.processor.model.order;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable description of one state change of an order. It is persisted atomically
 * (order + inventory + audit) by {@link com.dbonilla.ticketing.processor.model.order.gateways.OrderRepository}.
 *
 * @param deadlineAt deadline of the target state; {@code null} when the target state is final
 * @param reason     rejection reason; {@code null} unless the target state is REJECTED
 * @param movement   inventory movement; {@code null} when the transition does not touch the inventory
 */
public record OrderTransition(String orderId,
                              String eventId,
                              int quantity,
                              OrderStatus from,
                              OrderStatus to,
                              RejectionReason reason,
                              InventoryMovement movement,
                              Instant deadlineAt,
                              Instant occurredAt) {

    public OrderTransition {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (to.isFinal() == (deadlineAt != null)) {
            throw new IllegalArgumentException("Non-final states need a deadline and final states must not have one");
        }
        if ((to == OrderStatus.REJECTED) != (reason != null)) {
            throw new IllegalArgumentException("A reason is required only for REJECTED");
        }
    }

    public Optional<InventoryMovement> inventoryMovement() {
        return Optional.ofNullable(movement);
    }

    public Optional<RejectionReason> rejectionReason() {
        return Optional.ofNullable(reason);
    }

    public Optional<Instant> deadline() {
        return Optional.ofNullable(deadlineAt);
    }
}
