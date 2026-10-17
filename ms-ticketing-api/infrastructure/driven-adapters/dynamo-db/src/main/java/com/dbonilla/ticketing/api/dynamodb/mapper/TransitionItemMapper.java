package com.dbonilla.ticketing.api.dynamodb.mapper;

import com.dbonilla.ticketing.api.dynamodb.support.Attributes;
import com.dbonilla.ticketing.api.model.order.OrderStatus;
import com.dbonilla.ticketing.api.model.order.RejectionReason;
import com.dbonilla.ticketing.api.model.ordertransition.OrderTransition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.instant;
import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.n;
import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.number;
import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.optionalString;
import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.s;
import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.string;
import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.timestamp;

/**
 * Item layout of {@code ticketing-order-audit} (dynamodb.md, section 3). Append-only.
 */
public final class TransitionItemMapper {

    public static final String ORDER_ID = "orderId";
    public static final String TRANSITION_ID = "transitionId";
    public static final String EVENT_ID = "eventId";
    public static final String FROM_STATUS = "fromStatus";
    public static final String TO_STATUS = "toStatus";
    public static final String QUANTITY = "quantity";
    public static final String REASON = "reason";
    public static final String ACTOR = "actor";
    public static final String TRACE_ID = "traceId";
    public static final String OCCURRED_AT = "occurredAt";

    private TransitionItemMapper() {
    }

    /** {@code <occurredAt>#<toStatus>}, which sorts the trail chronologically. */
    public static String transitionId(OrderTransition transition) {
        return Attributes.TIMESTAMP.format(transition.occurredAt()) + "#" + transition.toStatus().name();
    }

    public static Map<String, AttributeValue> toItem(OrderTransition transition, Optional<String> traceId) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(ORDER_ID, s(transition.orderId()));
        item.put(TRANSITION_ID, s(transitionId(transition)));
        item.put(EVENT_ID, s(transition.eventId()));
        item.put(TO_STATUS, s(transition.toStatus().name()));
        item.put(QUANTITY, n(transition.quantity()));
        item.put(ACTOR, s(transition.actor()));
        item.put(OCCURRED_AT, timestamp(transition.occurredAt()));
        if (transition.fromStatus() != null) {
            item.put(FROM_STATUS, s(transition.fromStatus().name()));
        }
        if (transition.reason() != null) {
            item.put(REASON, s(transition.reason().name()));
        }
        traceId.ifPresent(value -> item.put(TRACE_ID, s(value)));
        return Map.copyOf(item);
    }

    public static OrderTransition toTransition(Map<String, AttributeValue> item) {
        return new OrderTransition(string(item, ORDER_ID), string(item, EVENT_ID),
                optionalString(item, FROM_STATUS).map(OrderStatus::valueOf).orElse(null),
                OrderStatus.valueOf(string(item, TO_STATUS)), Math.toIntExact(number(item, QUANTITY)),
                optionalString(item, REASON).map(RejectionReason::valueOf).orElse(null),
                string(item, ACTOR), instant(item, OCCURRED_AT));
    }
}
