package com.dbonilla.ticketing.processor.model.order;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTransitionTest {

    private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");
    private static final InventoryMovement MOVE = new InventoryMovement(TicketState.AVAILABLE, TicketState.RESERVED);

    @Test
    void nonFinalTargetRequiresDeadline() {
        assertThatThrownBy(() -> new OrderTransition("o", "e", 1, OrderStatus.QUEUED, OrderStatus.RESERVED,
                null, MOVE, null, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void finalTargetMustNotHaveDeadline() {
        assertThatThrownBy(() -> new OrderTransition("o", "e", 1, OrderStatus.PENDING_CONFIRMATION, OrderStatus.SOLD,
                null, null, NOW, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reasonIsRequiredOnlyForRejected() {
        assertThatThrownBy(() -> new OrderTransition("o", "e", 1, OrderStatus.QUEUED, OrderStatus.REJECTED,
                null, null, null, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderTransition("o", "e", 1, OrderStatus.PENDING_CONFIRMATION, OrderStatus.SOLD,
                RejectionReason.SOLD_OUT, null, null, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void quantityMustBePositiveAndFieldsRequired() {
        assertThatThrownBy(() -> new OrderTransition("o", "e", 0, OrderStatus.QUEUED, OrderStatus.RESERVED,
                null, MOVE, NOW, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderTransition("o", "e", 1, OrderStatus.QUEUED, OrderStatus.RESERVED,
                null, MOVE, NOW, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void inventoryMovementNeedsTwoDifferentCounters() {
        assertThatThrownBy(() -> new InventoryMovement(TicketState.SOLD, TicketState.SOLD))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InventoryMovement(null, TicketState.SOLD))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void finalStatesAreClassified() {
        assertThat(OrderStatus.SOLD.isFinal()).isTrue();
        assertThat(OrderStatus.COMPLIMENTARY.isFinal()).isTrue();
        assertThat(OrderStatus.REJECTED.isFinal()).isTrue();
        assertThat(OrderStatus.EXPIRED.isFinal()).isTrue();
        assertThat(OrderStatus.QUEUED.isFinal()).isFalse();
        assertThat(OrderStatus.RESERVED.isFinal()).isFalse();
        assertThat(OrderStatus.PENDING_CONFIRMATION.isFinal()).isFalse();
    }
}
