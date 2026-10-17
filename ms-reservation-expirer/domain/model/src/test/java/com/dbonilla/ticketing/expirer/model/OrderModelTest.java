package com.dbonilla.ticketing.expirer.model;

import com.dbonilla.ticketing.expirer.model.execution.ExecutionContext;
import com.dbonilla.ticketing.expirer.model.execution.OrderFailure;
import com.dbonilla.ticketing.expirer.model.execution.TimeBudget;
import com.dbonilla.ticketing.expirer.model.execution.UtcTimestamps;
import com.dbonilla.ticketing.expirer.model.message.OrderMessage;
import com.dbonilla.ticketing.expirer.model.message.OrderMessageType;
import com.dbonilla.ticketing.expirer.model.order.InventoryInvariantViolationException;
import com.dbonilla.ticketing.expirer.model.order.Order;
import com.dbonilla.ticketing.expirer.model.order.OrderPage;
import com.dbonilla.ticketing.expirer.model.order.OrderStatus;
import com.dbonilla.ticketing.expirer.model.order.OverdueOrdersQuery;
import com.dbonilla.ticketing.expirer.model.order.PageCursor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderModelTest {

    private static final Instant NOW = Instant.parse("2026-10-16T10:12:00.123Z");
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private static Order reserved(Instant deadline) {
        return new Order("order-1", "event-1", 2, OrderStatus.RESERVED, deadline);
    }

    @Test
    void orderIsOverdueOnlyInExpectedStatusAndStrictlyBeforeNow() {
        Order order = reserved(NOW.minusSeconds(1));

        assertThat(order.isOverdue(OrderStatus.RESERVED, NOW)).isTrue();
        assertThat(order.isOverdue(OrderStatus.QUEUED, NOW)).isFalse();
        assertThat(reserved(NOW).isOverdue(OrderStatus.RESERVED, NOW)).isFalse();
    }

    @Test
    void orderRejectsInvalidData() {
        assertThatThrownBy(() -> new Order(" ", "event-1", 1, OrderStatus.RESERVED, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("orderId");
        assertThatThrownBy(() -> new Order("order-1", null, 1, OrderStatus.RESERVED, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("eventId");
        assertThatThrownBy(() -> new Order("order-1", "event-1", 0, OrderStatus.RESERVED, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("quantity");
        assertThatThrownBy(() -> new Order("order-1", "event-1", 1, null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Order("order-1", "event-1", 1, OrderStatus.RESERVED, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Order("order-1", "event-1", 1, OrderStatus.SOLD, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("final");
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"SOLD", "COMPLIMENTARY", "REJECTED", "EXPIRED"})
    void finalStatuses(OrderStatus status) {
        assertThat(status.isFinal()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"QUEUED", "RESERVED", "PENDING_CONFIRMATION"})
    void nonFinalStatuses(OrderStatus status) {
        assertThat(status.isFinal()).isFalse();
    }

    @Test
    void queryPagesKeepTheirCriteria() {
        OverdueOrdersQuery first = OverdueOrdersQuery.firstPage(OrderStatus.RESERVED, NOW, 25);
        PageCursor cursor = new PageCursor("order-9", OrderStatus.RESERVED, NOW.minusSeconds(5));

        OverdueOrdersQuery next = first.nextPage(cursor);

        assertThat(first.exclusiveStart()).isEmpty();
        assertThat(next.exclusiveStart()).contains(cursor);
        assertThat(next.status()).isEqualTo(OrderStatus.RESERVED);
        assertThat(next.dueBefore()).isEqualTo(NOW);
        assertThat(next.limit()).isEqualTo(25);
        assertThatThrownBy(() -> first.nextPage(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> OverdueOrdersQuery.firstPage(OrderStatus.RESERVED, NOW, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PageCursor(null, OrderStatus.RESERVED, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void pageIsImmutableAndExposesOptionalCursor() {
        List<Order> source = new ArrayList<>(List.of(reserved(NOW)));
        OrderPage page = OrderPage.last(source);
        source.clear();

        assertThat(page.orders()).hasSize(1);
        assertThat(page.next()).isEmpty();
        assertThatThrownBy(() -> page.orders().add(reserved(NOW))).isInstanceOf(UnsupportedOperationException.class);
        PageCursor cursor = new PageCursor("order-1", OrderStatus.RESERVED, NOW);
        assertThat(new OrderPage(List.of(), cursor).next()).contains(cursor);
    }

    @Test
    void executionContextDelegatesToBudget() {
        assertThat(new ExecutionContext(NOW, TRACE_ID, TimeBudget.unlimited()).isOutOfTime()).isFalse();
        assertThat(new ExecutionContext(NOW, TRACE_ID, () -> true).isOutOfTime()).isTrue();
        assertThatThrownBy(() -> new ExecutionContext(null, TRACE_ID, TimeBudget.unlimited()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void republishedMessageGetsNewIdAndRunData() {
        ExecutionContext context = new ExecutionContext(NOW, TRACE_ID, TimeBudget.unlimited());
        Order order = new Order("order-1", "event-1", 1, OrderStatus.QUEUED, NOW.minusSeconds(1));

        OrderMessage first = OrderMessage.republish(order, OrderMessageType.ORDER_PLACED, context);
        OrderMessage second = OrderMessage.republish(order, OrderMessageType.ORDER_PLACED, context);

        assertThat(first.messageId()).isNotEqualTo(second.messageId());
        assertThat(first.orderId()).isEqualTo("order-1");
        assertThat(first.eventId()).isEqualTo("event-1");
        assertThat(first.occurredAt()).isEqualTo(NOW);
        assertThat(first.traceId()).isEqualTo(TRACE_ID);
        assertThat(OrderMessageType.ORDER_PLACED.contractName()).isEqualTo("OrderPlaced");
        assertThat(OrderMessageType.ORDER_CONFIRMATION_REQUESTED.contractName())
                .isEqualTo("OrderConfirmationRequested");
        assertThatThrownBy(() -> new OrderMessage(UUID.randomUUID(), null, "o", "e", NOW, TRACE_ID))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void failureCapturesErrorTypeAndMessage() {
        OrderFailure failure = OrderFailure.of("expire", OrderStatus.RESERVED, "order-1",
                new IllegalStateException("boom"));

        assertThat(failure.errorType()).isEqualTo(IllegalStateException.class.getName());
        assertThat(failure.errorMessage()).isEqualTo("boom");
        assertThat(failure.orderId()).isEqualTo("order-1");
        assertThatThrownBy(() -> new OrderFailure(null, OrderStatus.RESERVED, null, "x", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void inventoryViolationKeepsOrderId() {
        InventoryInvariantViolationException error = new InventoryInvariantViolationException("order-1", "event-1");

        assertThat(error.orderId()).isEqualTo("order-1");
        assertThat(error).hasMessageContaining("event-1");
    }

    @Test
    void timestampsAlwaysHaveMilliseconds() {
        assertThat(UtcTimestamps.format(Instant.parse("2026-10-16T10:00:00Z"))).isEqualTo("2026-10-16T10:00:00.000Z");
        assertThat(UtcTimestamps.format(NOW)).isEqualTo("2026-10-16T10:12:00.123Z");
    }
}
