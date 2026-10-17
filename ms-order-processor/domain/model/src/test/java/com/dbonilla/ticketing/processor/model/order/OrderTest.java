package com.dbonilla.ticketing.processor.model.order;

import com.dbonilla.ticketing.processor.model.exceptions.InvalidOrderStateException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static final Instant NOW = Instant.parse("2026-10-16T10:00:00.123Z");
    private static final Duration TTL = Duration.ofMinutes(10);

    static Order order(OrderStatus status) {
        return new Order("order-1", "event-1", "user-1", 2, status, null, null, "REF-1");
    }

    @Test
    void reserveMovesQueuedOrderToReservedWithDeadlineAndInventoryMovement() {
        // Arrange
        Order queued = order(OrderStatus.QUEUED);

        // Act
        OrderTransition transition = queued.reserve(NOW, TTL);

        // Assert
        assertThat(transition.from()).isEqualTo(OrderStatus.QUEUED);
        assertThat(transition.to()).isEqualTo(OrderStatus.RESERVED);
        assertThat(transition.deadlineAt()).isEqualTo(NOW.plus(TTL));
        assertThat(transition.movement()).isEqualTo(new InventoryMovement(TicketState.AVAILABLE, TicketState.RESERVED));
        assertThat(transition.quantity()).isEqualTo(2);
        assertThat(transition.reason()).isNull();
        assertThat(transition.occurredAt()).isEqualTo(NOW);
        assertThat(queued.status()).isEqualTo(OrderStatus.QUEUED);
    }

    @Test
    void rejectSoldOutHasNoInventoryMovementAndNoDeadline() {
        OrderTransition transition = order(OrderStatus.QUEUED).rejectSoldOut(NOW);

        assertThat(transition.to()).isEqualTo(OrderStatus.REJECTED);
        assertThat(transition.rejectionReason()).contains(RejectionReason.SOLD_OUT);
        assertThat(transition.inventoryMovement()).isEmpty();
        assertThat(transition.deadline()).isEmpty();
    }

    @Test
    void markSoldMovesPendingConfirmationToSold() {
        OrderTransition transition = order(OrderStatus.PENDING_CONFIRMATION).markSold(NOW);

        assertThat(transition.to()).isEqualTo(OrderStatus.SOLD);
        assertThat(transition.movement())
                .isEqualTo(new InventoryMovement(TicketState.PENDING_CONFIRMATION, TicketState.SOLD));
        assertThat(transition.deadline()).isEmpty();
    }

    @Test
    void rejectPaymentDeclinedReturnsTicketsToAvailable() {
        OrderTransition transition = order(OrderStatus.PENDING_CONFIRMATION).rejectPaymentDeclined(NOW);

        assertThat(transition.to()).isEqualTo(OrderStatus.REJECTED);
        assertThat(transition.reason()).isEqualTo(RejectionReason.PAYMENT_DECLINED);
        assertThat(transition.movement())
                .isEqualTo(new InventoryMovement(TicketState.PENDING_CONFIRMATION, TicketState.AVAILABLE));
    }

    @Test
    void transitionsFromAnotherStateAreRejected() {
        Order reserved = order(OrderStatus.RESERVED);

        assertThatThrownBy(() -> reserved.reserve(NOW, TTL)).isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("RESERVED");
        assertThatThrownBy(() -> reserved.rejectSoldOut(NOW)).isInstanceOf(InvalidOrderStateException.class);
        assertThatThrownBy(() -> reserved.markSold(NOW)).isInstanceOf(InvalidOrderStateException.class);
        assertThatThrownBy(() -> reserved.rejectPaymentDeclined(NOW)).isInstanceOf(InvalidOrderStateException.class);
    }

    @Test
    void isInComparesTheCurrentStatus() {
        assertThat(order(OrderStatus.QUEUED).isIn(OrderStatus.QUEUED)).isTrue();
        assertThat(order(OrderStatus.QUEUED).isIn(OrderStatus.RESERVED)).isFalse();
    }

    @Test
    void orderRequiresIdentifiersStatusAndPositiveQuantity() {
        assertThatThrownBy(() -> new Order(null, "e", "u", 1, OrderStatus.QUEUED, null, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Order("o", null, "u", 1, OrderStatus.QUEUED, null, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Order("o", "e", "u", 1, null, null, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Order("o", "e", "u", 0, OrderStatus.QUEUED, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
