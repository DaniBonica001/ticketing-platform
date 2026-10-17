package com.dbonilla.ticketing.processor.model.order;

import com.dbonilla.ticketing.processor.model.exceptions.InvalidOrderStateException;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Order as stored in the source of truth. Immutable: transitions return a new {@link OrderTransition}
 * and never modify this instance.
 */
public record Order(String orderId,
                    String eventId,
                    String userId,
                    int quantity,
                    OrderStatus status,
                    RejectionReason reason,
                    Instant deadlineAt,
                    String paymentReference) {

    public Order {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(status, "status");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }

    /** T2: QUEUED -> RESERVED, available -> reserved. */
    public OrderTransition reserve(Instant now, Duration reservationTtl) {
        requireStatus(OrderStatus.QUEUED, OrderStatus.RESERVED);
        return transition(OrderStatus.RESERVED, null,
                new InventoryMovement(TicketState.AVAILABLE, TicketState.RESERVED), now.plus(reservationTtl), now);
    }

    /** T3: QUEUED -> REJECTED (SOLD_OUT), no inventory movement. */
    public OrderTransition rejectSoldOut(Instant now) {
        requireStatus(OrderStatus.QUEUED, OrderStatus.REJECTED);
        return transition(OrderStatus.REJECTED, RejectionReason.SOLD_OUT, null, null, now);
    }

    /** T5: PENDING_CONFIRMATION -> SOLD, pendingConfirmation -> sold. */
    public OrderTransition markSold(Instant now) {
        requireStatus(OrderStatus.PENDING_CONFIRMATION, OrderStatus.SOLD);
        return transition(OrderStatus.SOLD, null,
                new InventoryMovement(TicketState.PENDING_CONFIRMATION, TicketState.SOLD), null, now);
    }

    /** T6: PENDING_CONFIRMATION -> REJECTED (PAYMENT_DECLINED), pendingConfirmation -> available. */
    public OrderTransition rejectPaymentDeclined(Instant now) {
        requireStatus(OrderStatus.PENDING_CONFIRMATION, OrderStatus.REJECTED);
        return transition(OrderStatus.REJECTED, RejectionReason.PAYMENT_DECLINED,
                new InventoryMovement(TicketState.PENDING_CONFIRMATION, TicketState.AVAILABLE), null, now);
    }

    public boolean isIn(OrderStatus expected) {
        return status == expected;
    }

    private void requireStatus(OrderStatus expected, OrderStatus target) {
        if (status != expected) {
            throw new InvalidOrderStateException(orderId, status, target);
        }
    }

    private OrderTransition transition(OrderStatus target, RejectionReason rejectionReason,
                                       InventoryMovement movement, Instant deadline, Instant now) {
        return new OrderTransition(orderId, eventId, quantity, status, target, rejectionReason, movement, deadline, now);
    }
}
