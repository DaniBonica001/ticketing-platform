package com.dbonilla.ticketing.api.model.order;

import com.dbonilla.ticketing.api.model.exception.DomainValidationException;
import com.dbonilla.ticketing.api.model.exception.InvalidOrderStateException;
import com.dbonilla.ticketing.api.model.exception.ReservationExpiredException;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A purchase of {@code quantity} tickets of an event. Immutable: every transition returns a new instance.
 *
 * @param reason     present only in REJECTED
 * @param deadlineAt deadline of the current state (seconds precision); only in non-final states
 */
public record Order(String orderId, String eventId, String userId, int quantity, OrderStatus status,
                    RejectionReason reason, Instant deadlineAt, String requestHash,
                    String paymentReference, Instant createdAt, Instant updatedAt) {

    public Order {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** T1: a new order accepted by the API and waiting for the processor. */
    public static Order place(String orderId, String eventId, String userId, int quantity,
                              String requestHash, Instant now, OrderPolicy policy) {
        requireQuantity(quantity);
        return new Order(orderId, eventId, userId, quantity, OrderStatus.QUEUED, null,
                deadline(now, policy.reconciliationDeadline()), requestHash, null, now, now);
    }

    /** T8: a complimentary order issued by an admin. It is final from the start. */
    public static Order complimentary(String orderId, String eventId, String recipientUserId, int quantity,
                                      String requestHash, Instant now) {
        requireQuantity(quantity);
        return new Order(orderId, eventId, recipientUserId, quantity, OrderStatus.COMPLIMENTARY, null,
                null, requestHash, null, now, now);
    }

    /** T4: evaluates a confirmation (RESERVED to PENDING_CONFIRMATION). */
    public ConfirmDecision confirm(String reference, Instant now, OrderPolicy policy) {
        return switch (status) {
            case RESERVED -> new ConfirmDecision.Apply(confirmReserved(reference, now, policy));
            case PENDING_CONFIRMATION -> replayConfirmation(reference);
            case QUEUED, SOLD, COMPLIMENTARY, REJECTED, EXPIRED ->
                    throw new InvalidOrderStateException(orderId, status, OrderStatus.PENDING_CONFIRMATION);
        };
    }

    /** Reservation deadline, exposed only while the order is RESERVED. */
    public Instant expiresAt() {
        return status == OrderStatus.RESERVED ? deadlineAt : null;
    }

    public boolean matchesRequest(String hash) {
        return Objects.equals(requestHash, hash);
    }

    private Order confirmReserved(String reference, Instant now, OrderPolicy policy) {
        if (deadlineAt == null || !deadlineAt.isAfter(now)) {
            throw new ReservationExpiredException(orderId);
        }
        return new Order(orderId, eventId, userId, quantity, OrderStatus.PENDING_CONFIRMATION, null,
                deadline(now, policy.reconciliationDeadline()), requestHash, reference, createdAt, now);
    }

    private ConfirmDecision replayConfirmation(String reference) {
        if (Objects.equals(paymentReference, reference)) {
            return new ConfirmDecision.AlreadyConfirmed(this);
        }
        throw new InvalidOrderStateException(orderId, status, OrderStatus.PENDING_CONFIRMATION);
    }

    private static Instant deadline(Instant now, Duration delay) {
        return Instant.ofEpochSecond(now.getEpochSecond() + delay.toSeconds());
    }

    private static void requireQuantity(int quantity) {
        if (quantity < OrderPolicy.MIN_QUANTITY || quantity > OrderPolicy.MAX_QUANTITY) {
            throw new DomainValidationException("quantity",
                    "quantity must be between " + OrderPolicy.MIN_QUANTITY + " and " + OrderPolicy.MAX_QUANTITY);
        }
    }
}
