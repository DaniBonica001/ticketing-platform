package com.dbonilla.ticketing.api.dynamodb;

import com.dbonilla.ticketing.api.dynamodb.config.DynamoTablesProperties;
import com.dbonilla.ticketing.api.dynamodb.mapper.EventItemMapper;
import com.dbonilla.ticketing.api.dynamodb.mapper.OrderItemMapper;
import com.dbonilla.ticketing.api.dynamodb.mapper.TransitionItemMapper;
import com.dbonilla.ticketing.api.model.order.Order;
import com.dbonilla.ticketing.api.model.order.OrderStatus;
import com.dbonilla.ticketing.api.model.ordertransition.OrderTransition;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.n;
import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.s;
import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.timestamp;

/**
 * Builds the exact {@code TransactWriteItems} of dynamodb.md, section 4. Item positions matter: they are
 * the indexes of {@code CancellationReasons}.
 */
final class OrderTransactions {

    static final int PLACE_ORDER_INDEX = 0;
    static final int COMPLIMENTARY_EVENT_INDEX = 0;
    static final int COMPLIMENTARY_ORDER_INDEX = 1;
    static final int CONFIRM_ORDER_INDEX = 0;
    static final int CONFIRM_EVENT_INDEX = 1;

    static final String NEW_ORDER_CONDITION = "attribute_not_exists(orderId)";
    static final String COMPLIMENTARY_UPDATE =
            "SET available = available - :q, complimentary = complimentary + :q, updatedAt = :ts";
    static final String AVAILABLE_CONDITION = "available >= :q";
    static final String CONFIRM_ORDER_UPDATE = "SET #status = :pendingConfirmation, paymentReference = :ref, "
            + "deadlineAt = :deadline, updatedAt = :ts";
    static final String CONFIRM_ORDER_CONDITION =
            "#status = :reserved AND deadlineAt > :now AND userId = :caller";
    static final String CONFIRM_EVENT_UPDATE =
            "SET reserved = reserved - :q, pendingConfirmation = pendingConfirmation + :q, updatedAt = :ts";
    static final String RESERVED_CONDITION = "reserved >= :q";

    private final DynamoTablesProperties tables;

    OrderTransactions(DynamoTablesProperties tables) {
        this.tables = tables;
    }

    /** T1: Put order QUEUED [attribute_not_exists(orderId)] + Put audit. */
    TransactWriteItemsRequest place(Order order, OrderTransition transition, Optional<String> traceId) {
        return request(newOrder(order), audit(transition, traceId));
    }

    /** T8: Update event available to complimentary (and updatedAt) [available >= :q] + Put order + Put audit. */
    TransactWriteItemsRequest complimentary(Order order, OrderTransition transition, Optional<String> traceId) {
        Update event = Update.builder()
                .tableName(tables.eventsTable())
                .key(EventItemMapper.key(order.eventId()))
                .updateExpression(COMPLIMENTARY_UPDATE)
                .conditionExpression(AVAILABLE_CONDITION)
                .expressionAttributeValues(Map.of(":q", n(order.quantity()), ":ts", timestamp(order.updatedAt())))
                .build();
        return request(TransactWriteItem.builder().update(event).build(), newOrder(order), audit(transition, traceId));
    }

    /**
     * T4: Update order RESERVED to PENDING_CONFIRMATION [status, deadline and owner] + Update event
     * reserved to pendingConfirmation [reserved >= :q] + Put audit.
     */
    TransactWriteItemsRequest confirm(Order next, OrderTransition transition, String callerId, Instant now,
                                      Optional<String> traceId) {
        Update order = Update.builder()
                .tableName(tables.ordersTable())
                .key(OrderItemMapper.key(next.orderId()))
                .updateExpression(CONFIRM_ORDER_UPDATE)
                .conditionExpression(CONFIRM_ORDER_CONDITION)
                .expressionAttributeNames(Map.of("#status", OrderItemMapper.STATUS))
                .expressionAttributeValues(Map.of(
                        ":pendingConfirmation", s(OrderStatus.PENDING_CONFIRMATION.name()),
                        ":reserved", s(OrderStatus.RESERVED.name()),
                        ":ref", s(next.paymentReference()),
                        ":deadline", n(next.deadlineAt().getEpochSecond()),
                        ":ts", timestamp(next.updatedAt()),
                        ":now", n(now.getEpochSecond()),
                        ":caller", s(callerId)))
                .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
                .build();
        Update event = Update.builder()
                .tableName(tables.eventsTable())
                .key(EventItemMapper.key(next.eventId()))
                .updateExpression(CONFIRM_EVENT_UPDATE)
                .conditionExpression(RESERVED_CONDITION)
                .expressionAttributeValues(Map.of(":q", n(next.quantity()), ":ts", timestamp(next.updatedAt())))
                .build();
        return request(TransactWriteItem.builder().update(order).build(),
                TransactWriteItem.builder().update(event).build(), audit(transition, traceId));
    }

    private TransactWriteItem newOrder(Order order) {
        Put put = Put.builder()
                .tableName(tables.ordersTable())
                .item(OrderItemMapper.toItem(order))
                .conditionExpression(NEW_ORDER_CONDITION)
                .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
                .build();
        return TransactWriteItem.builder().put(put).build();
    }

    private TransactWriteItem audit(OrderTransition transition, Optional<String> traceId) {
        Put put = Put.builder()
                .tableName(tables.auditTable())
                .item(TransitionItemMapper.toItem(transition, traceId))
                .build();
        return TransactWriteItem.builder().put(put).build();
    }

    private static TransactWriteItemsRequest request(TransactWriteItem... items) {
        return TransactWriteItemsRequest.builder().transactItems(items).build();
    }
}
