package com.dbonilla.ticketing.api.dynamodb;

import com.dbonilla.ticketing.api.dynamodb.mapper.EventItemMapper;
import com.dbonilla.ticketing.api.dynamodb.mapper.OrderItemMapper;
import com.dbonilla.ticketing.api.dynamodb.mapper.TransitionItemMapper;
import com.dbonilla.ticketing.api.dynamodb.support.Attributes;
import com.dbonilla.ticketing.api.model.event.Event;
import com.dbonilla.ticketing.api.model.order.Order;
import com.dbonilla.ticketing.api.model.order.OrderStatus;
import com.dbonilla.ticketing.api.model.order.RejectionReason;
import com.dbonilla.ticketing.api.model.ordertransition.OrderTransition;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Map;
import java.util.Optional;

import static com.dbonilla.ticketing.api.dynamodb.DynamoFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MappersTest {

    @Test
    void eventRoundTrip() {
        Event event = DynamoFixtures.event();

        Map<String, AttributeValue> item = EventItemMapper.toItem(event);

        assertThat(item.get("eventDate").s()).isEqualTo("2026-12-20T20:00:00-05:00");
        assertThat(item.get("createdAt").s()).isEqualTo("2026-10-16T10:00:00.123Z");
        assertThat(item.get("available").n()).isEqualTo("3");
        assertThat(EventItemMapper.toEvent(item)).isEqualTo(event);
    }

    @Test
    void orderRoundTripWithOptionalAttributes() {
        Order order = new Order("o", "e", "u", 1, OrderStatus.REJECTED, RejectionReason.SOLD_OUT,
                NOW.minusMillis(NOW.toEpochMilli() % 1000), "h", "PAY", NOW, NOW);

        Map<String, AttributeValue> item = OrderItemMapper.toItem(order);

        assertThat(item.get("deadlineAt").n()).isEqualTo(Long.toString(NOW.getEpochSecond()));
        assertThat(OrderItemMapper.toOrder(item)).isEqualTo(order);
    }

    @Test
    void orderWithoutOptionalAttributes() {
        Order order = new Order("o", "e", "u", 1, OrderStatus.COMPLIMENTARY, null, null, null, null, NOW, NOW);

        Map<String, AttributeValue> item = OrderItemMapper.toItem(order);

        assertThat(item).doesNotContainKeys("reason", "deadlineAt", "requestHash", "paymentReference");
        assertThat(OrderItemMapper.toOrder(item)).isEqualTo(order);
    }

    @Test
    void transitionRoundTripWithTraceId() {
        OrderTransition transition = new OrderTransition("o", "e", OrderStatus.QUEUED, OrderStatus.REJECTED, 2,
                RejectionReason.SOLD_OUT, "order-processor", NOW);

        Map<String, AttributeValue> item = TransitionItemMapper.toItem(transition, Optional.of("trace"));

        assertThat(item.get("transitionId").s()).isEqualTo("2026-10-16T10:00:00.123Z#REJECTED");
        assertThat(item.get("traceId").s()).isEqualTo("trace");
        assertThat(TransitionItemMapper.toTransition(item)).isEqualTo(transition);
    }

    @Test
    void creationTransitionHasNoOriginNorTrace() {
        OrderTransition transition = new OrderTransition("o", "e", null, OrderStatus.QUEUED, 2, null,
                "ticketing-api", NOW);

        Map<String, AttributeValue> item = TransitionItemMapper.toItem(transition, Optional.empty());

        assertThat(item).doesNotContainKeys("fromStatus", "reason", "traceId");
        assertThat(TransitionItemMapper.toTransition(item)).isEqualTo(transition);
    }

    @Test
    void missingMandatoryAttributesFailFast() {
        assertThatThrownBy(() -> Attributes.string(Map.of(), "x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Attributes.number(Map.of(), "x")).isInstanceOf(IllegalStateException.class);
        assertThat(Attributes.isPresent(null)).isFalse();
        assertThat(Attributes.isPresent(Map.of())).isFalse();
    }
}
