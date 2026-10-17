package com.dbonilla.ticketing.processor.dynamodb;

import com.dbonilla.ticketing.processor.model.order.Order;
import com.dbonilla.ticketing.processor.model.order.OrderStatus;
import com.dbonilla.ticketing.processor.model.order.RejectionReason;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Maps items of the orders table to the domain model.
 */
final class OrderItemMapper {

    private OrderItemMapper() {
    }

    static Order toOrder(Map<String, AttributeValue> item) {
        return new Order(
                requiredString(item, Attributes.ORDER_ID),
                requiredString(item, Attributes.EVENT_ID),
                optionalString(item, Attributes.USER_ID).orElse(null),
                Integer.parseInt(requiredNumber(item, Attributes.QUANTITY)),
                OrderStatus.valueOf(requiredString(item, Attributes.STATUS)),
                optionalString(item, Attributes.REASON).map(RejectionReason::valueOf).orElse(null),
                optionalNumber(item, Attributes.DEADLINE_AT).map(Long::parseLong).map(Instant::ofEpochSecond).orElse(null),
                optionalString(item, Attributes.PAYMENT_REFERENCE).orElse(null));
    }

    private static String requiredString(Map<String, AttributeValue> item, String name) {
        return optionalString(item, name)
                .orElseThrow(() -> new IllegalStateException("Order item without attribute " + name));
    }

    private static String requiredNumber(Map<String, AttributeValue> item, String name) {
        return optionalNumber(item, name)
                .orElseThrow(() -> new IllegalStateException("Order item without attribute " + name));
    }

    private static Optional<String> optionalString(Map<String, AttributeValue> item, String name) {
        return Optional.ofNullable(item.get(name)).map(AttributeValue::s);
    }

    private static Optional<String> optionalNumber(Map<String, AttributeValue> item, String name) {
        return Optional.ofNullable(item.get(name)).map(AttributeValue::n);
    }
}
