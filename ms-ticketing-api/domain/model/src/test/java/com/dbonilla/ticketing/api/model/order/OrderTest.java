package com.dbonilla.ticketing.api.model.order;

import com.dbonilla.ticketing.api.model.TestData;
import com.dbonilla.ticketing.api.model.exception.DomainValidationException;
import com.dbonilla.ticketing.api.model.exception.InvalidOrderStateException;
import com.dbonilla.ticketing.api.model.exception.ReservationExpiredException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;

import static com.dbonilla.ticketing.api.model.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static final OrderPolicy POLICY = new OrderPolicy(Duration.ofMinutes(5));

    @Test
    void placedOrderIsQueuedWithReconciliationDeadline() {
        Order order = Order.place("o1", "e1", "u1", 2, "hash", NOW, POLICY);

        assertThat(order.status()).isEqualTo(OrderStatus.QUEUED);
        assertThat(order.deadlineAt()).isEqualTo(Instant.ofEpochSecond(NOW.getEpochSecond() + 300));
        assertThat(order.expiresAt()).isNull();
        assertThat(order.matchesRequest("hash")).isTrue();
        assertThat(order.matchesRequest("other")).isFalse();
    }

    @Test
    void complimentaryOrderIsFinalAndHasNoDeadline() {
        Order order = Order.complimentary("o1", "e1", "vip", 3, "hash", NOW);

        assertThat(order.status()).isEqualTo(OrderStatus.COMPLIMENTARY);
        assertThat(order.status().isFinal()).isTrue();
        assertThat(order.deadlineAt()).isNull();
        assertThat(order.userId()).isEqualTo("vip");
    }

    @Test
    void rejectsQuantityOutOfRange() {
        assertThatThrownBy(() -> Order.place("o1", "e1", "u1", 0, "h", NOW, POLICY))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Order.complimentary("o1", "e1", "u1", 11, "h", NOW))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void confirmsReservedOrderBeforeDeadline() {
        Order reserved = TestData.order(OrderStatus.RESERVED, NOW.plusSeconds(60), null);

        ConfirmDecision decision = reserved.confirm("PAY-1", NOW, POLICY);

        assertThat(decision).isInstanceOf(ConfirmDecision.Apply.class);
        Order next = ((ConfirmDecision.Apply) decision).next();
        assertThat(next.status()).isEqualTo(OrderStatus.PENDING_CONFIRMATION);
        assertThat(next.paymentReference()).isEqualTo("PAY-1");
        assertThat(next.deadlineAt()).isEqualTo(Instant.ofEpochSecond(NOW.getEpochSecond() + 300));
        assertThat(next.updatedAt()).isEqualTo(NOW);
        assertThat(next.createdAt()).isEqualTo(reserved.createdAt());
        assertThat(reserved.expiresAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void rejectsConfirmationAfterDeadline() {
        Order reserved = TestData.order(OrderStatus.RESERVED, NOW, null);

        assertThatThrownBy(() -> reserved.confirm("PAY-1", NOW, POLICY))
                .isInstanceOf(ReservationExpiredException.class);
    }

    @Test
    void rejectsConfirmationOfReservedOrderWithoutDeadline() {
        Order reserved = TestData.order(OrderStatus.RESERVED, null, null);

        assertThatThrownBy(() -> reserved.confirm("PAY-1", NOW, POLICY))
                .isInstanceOf(ReservationExpiredException.class);
    }

    @Test
    void repeatedConfirmationWithSameReferenceIsIdempotent() {
        Order pending = TestData.order(OrderStatus.PENDING_CONFIRMATION, NOW.plusSeconds(60), "PAY-1");

        assertThat(pending.confirm("PAY-1", NOW, POLICY)).isEqualTo(new ConfirmDecision.AlreadyConfirmed(pending));
    }

    @Test
    void confirmationWithOtherReferenceIsInvalid() {
        Order pending = TestData.order(OrderStatus.PENDING_CONFIRMATION, NOW.plusSeconds(60), "PAY-1");

        assertThatThrownBy(() -> pending.confirm("PAY-2", NOW, POLICY))
                .isInstanceOf(InvalidOrderStateException.class);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"QUEUED", "SOLD", "COMPLIMENTARY", "REJECTED", "EXPIRED"})
    void cannotConfirmFromOtherStates(OrderStatus status) {
        Order order = TestData.order(status, null, null);

        assertThatThrownBy(() -> order.confirm("PAY-1", NOW, POLICY))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining(status.name());
    }

    @Test
    void finalStatesAreIdentified() {
        assertThat(OrderStatus.SOLD.isFinal()).isTrue();
        assertThat(OrderStatus.REJECTED.isFinal()).isTrue();
        assertThat(OrderStatus.EXPIRED.isFinal()).isTrue();
        assertThat(OrderStatus.QUEUED.isFinal()).isFalse();
        assertThat(OrderStatus.RESERVED.isFinal()).isFalse();
        assertThat(OrderStatus.PENDING_CONFIRMATION.isFinal()).isFalse();
    }

    @Test
    void requiresMandatoryFields() {
        assertThatThrownBy(() -> new Order(null, "e", "u", 1, OrderStatus.QUEUED, null, null, null, null, NOW, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Order("o", null, "u", 1, OrderStatus.QUEUED, null, null, null, null, NOW, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Order("o", "e", null, 1, OrderStatus.QUEUED, null, null, null, null, NOW, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Order("o", "e", "u", 1, null, null, null, null, null, NOW, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Order("o", "e", "u", 1, OrderStatus.QUEUED, null, null, null, null, null, NOW))
                .isInstanceOf(NullPointerException.class);
    }
}
