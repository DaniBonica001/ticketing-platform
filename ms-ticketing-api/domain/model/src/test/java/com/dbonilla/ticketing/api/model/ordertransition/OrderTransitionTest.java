package com.dbonilla.ticketing.api.model.ordertransition;

import com.dbonilla.ticketing.api.model.TestData;
import com.dbonilla.ticketing.api.model.order.Order;
import com.dbonilla.ticketing.api.model.order.OrderStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTransitionTest {

    @Test
    void creationTransitionHasNoOrigin() {
        Order order = TestData.order(OrderStatus.QUEUED, null, null);

        OrderTransition transition = OrderTransition.created(order);

        assertThat(transition.fromStatus()).isNull();
        assertThat(transition.toStatus()).isEqualTo(OrderStatus.QUEUED);
        assertThat(transition.actor()).isEqualTo(OrderTransition.API_ACTOR);
        assertThat(transition.occurredAt()).isEqualTo(order.updatedAt());
        assertThat(transition.quantity()).isEqualTo(order.quantity());
    }

    @Test
    void transitionBetweenStatesKeepsBoth() {
        Order from = TestData.order(OrderStatus.RESERVED, null, null);
        Order to = TestData.order(OrderStatus.PENDING_CONFIRMATION, null, "PAY");

        OrderTransition transition = OrderTransition.between(from, to);

        assertThat(transition.fromStatus()).isEqualTo(OrderStatus.RESERVED);
        assertThat(transition.toStatus()).isEqualTo(OrderStatus.PENDING_CONFIRMATION);
        assertThat(transition.orderId()).isEqualTo(TestData.ORDER_ID);
        assertThat(transition.eventId()).isEqualTo(TestData.EVENT_ID);
    }

    @Test
    void requiresMandatoryFields() {
        assertThatThrownBy(() -> new OrderTransition(null, "e", null, OrderStatus.QUEUED, 1, null, "a", TestData.NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderTransition("o", "e", null, null, 1, null, "a", TestData.NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderTransition("o", "e", null, OrderStatus.QUEUED, 1, null, null, TestData.NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderTransition("o", "e", null, OrderStatus.QUEUED, 1, null, "a", null))
                .isInstanceOf(NullPointerException.class);
    }
}
