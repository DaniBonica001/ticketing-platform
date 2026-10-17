package com.dbonilla.ticketing.api.dynamodb.mapper;

import com.dbonilla.ticketing.api.model.order.Order;
import com.dbonilla.ticketing.api.model.order.OrderStatus;
import com.dbonilla.ticketing.api.model.order.RejectionReason;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.instant;
import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.n;
import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.number;
import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.optionalInstant;
import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.optionalNumber;
import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.optionalString;
import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.s;
import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.string;
import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.timestamp;

/**
 * Item layout of {@code ticketing-orders} (dynamodb.md, section 2). {@code deadlineAt} is epoch seconds.
 */
public final class OrderItemMapper {

    public static final String ORDER_ID = "orderId";
    public static final String EVENT_ID = "eventId";
    public static final String USER_ID = "userId";
    public static final String QUANTITY = "quantity";
    public static final String STATUS = "status";
    public static final String REASON = "reason";
    public static final String DEADLINE_AT = "deadlineAt";
    public static final String REQUEST_HASH = "requestHash";
    public static final String PAYMENT_REFERENCE = "paymentReference";
    public static final String CREATED_AT = "createdAt";
    public static final String UPDATED_AT = "updatedAt";

    private OrderItemMapper() {
    }

    public static Map<String, AttributeValue> key(String orderId) {
        return Map.of(ORDER_ID, s(orderId));
    }

    public static Map<String, AttributeValue> toItem(Order order) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(ORDER_ID, s(order.orderId()));
        item.put(EVENT_ID, s(order.eventId()));
        item.put(USER_ID, s(order.userId()));
        item.put(QUANTITY, n(order.quantity()));
        item.put(STATUS, s(order.status().name()));
        item.put(CREATED_AT, timestamp(order.createdAt()));
        item.put(UPDATED_AT, timestamp(order.updatedAt()));
        if (order.reason() != null) {
            item.put(REASON, s(order.reason().name()));
        }
        if (order.deadlineAt() != null) {
            item.put(DEADLINE_AT, n(order.deadlineAt().getEpochSecond()));
        }
        if (order.requestHash() != null) {
            item.put(REQUEST_HASH, s(order.requestHash()));
        }
        if (order.paymentReference() != null) {
            item.put(PAYMENT_REFERENCE, s(order.paymentReference()));
        }
        return Map.copyOf(item);
    }

    public static Order toOrder(Map<String, AttributeValue> item) {
        return new Order(string(item, ORDER_ID), string(item, EVENT_ID), string(item, USER_ID),
                Math.toIntExact(number(item, QUANTITY)), OrderStatus.valueOf(string(item, STATUS)),
                optionalString(item, REASON).map(RejectionReason::valueOf).orElse(null),
                optionalNumber(item, DEADLINE_AT).map(Instant::ofEpochSecond).orElse(null),
                optionalString(item, REQUEST_HASH).orElse(null),
                optionalString(item, PAYMENT_REFERENCE).orElse(null),
                instant(item, CREATED_AT), optionalInstant(item, UPDATED_AT).orElse(null));
    }
}
