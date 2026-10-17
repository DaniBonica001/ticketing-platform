package com.dbonilla.ticketing.expirer.dynamodb;

import com.dbonilla.ticketing.expirer.model.execution.ExecutionContext;
import com.dbonilla.ticketing.expirer.model.execution.UtcTimestamps;
import com.dbonilla.ticketing.expirer.model.order.Order;
import com.dbonilla.ticketing.expirer.model.order.OrderStatus;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.util.Map;

import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.ACTOR;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.ACTOR_NAME;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.AVAILABLE;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.DEADLINE_AT;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.EVENT_ID;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.FROM_STATUS;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.OCCURRED_AT;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.ORDER_ID;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.QUANTITY;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.RESERVED;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.STATUS;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.TO_STATUS;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.TRACE_ID;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.TRANSITION_ID;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.UPDATED_AT;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.epochSeconds;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.number;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.text;

/**
 * T7 as one {@code TransactWriteItems}: order RESERVED to EXPIRED (REMOVE deadlineAt), event
 * {@code reserved -= q, available += q} and the audit row. Item order matters to read cancellation reasons.
 */
final class ExpireOrderTransactionFactory {

    static final int ORDER_ITEM = 0;
    static final int EVENT_ITEM = 1;

    private final DynamoTables tables;

    ExpireOrderTransactionFactory(DynamoTables tables) {
        this.tables = tables;
    }

    TransactWriteItemsRequest build(Order order, ExecutionContext context) {
        String timestamp = UtcTimestamps.format(context.now());
        return TransactWriteItemsRequest.builder()
                .transactItems(
                        TransactWriteItem.builder().update(expireOrder(order, context, timestamp)).build(),
                        TransactWriteItem.builder().update(releaseInventory(order, timestamp)).build(),
                        TransactWriteItem.builder().put(auditRow(order, context, timestamp)).build())
                .build();
    }

    private Update expireOrder(Order order, ExecutionContext context, String timestamp) {
        return Update.builder()
                .tableName(tables.orders())
                .key(Map.of(ORDER_ID, text(order.orderId())))
                .updateExpression("SET #status = :expired, #updatedAt = :ts REMOVE #deadlineAt")
                .conditionExpression("#status = :reserved AND #deadlineAt < :now")
                .expressionAttributeNames(Map.of(
                        "#status", STATUS, "#updatedAt", UPDATED_AT, "#deadlineAt", DEADLINE_AT))
                .expressionAttributeValues(Map.of(
                        ":expired", text(OrderStatus.EXPIRED.name()),
                        ":reserved", text(OrderStatus.RESERVED.name()),
                        ":ts", text(timestamp),
                        ":now", epochSeconds(context.now())))
                .build();
    }

    private Update releaseInventory(Order order, String timestamp) {
        return Update.builder()
                .tableName(tables.events())
                .key(Map.of(EVENT_ID, text(order.eventId())))
                .updateExpression("SET #reserved = #reserved - :q, #available = #available + :q, #updatedAt = :ts")
                .conditionExpression("#reserved >= :q")
                .expressionAttributeNames(Map.of(
                        "#reserved", RESERVED, "#available", AVAILABLE, "#updatedAt", UPDATED_AT))
                .expressionAttributeValues(Map.of(
                        ":q", number(order.quantity()),
                        ":ts", text(timestamp)))
                .build();
    }

    private Put auditRow(Order order, ExecutionContext context, String timestamp) {
        return Put.builder()
                .tableName(tables.audit())
                .item(Map.of(
                        ORDER_ID, text(order.orderId()),
                        TRANSITION_ID, text(timestamp + "#" + OrderStatus.EXPIRED.name()),
                        EVENT_ID, text(order.eventId()),
                        FROM_STATUS, text(OrderStatus.RESERVED.name()),
                        TO_STATUS, text(OrderStatus.EXPIRED.name()),
                        QUANTITY, number(order.quantity()),
                        ACTOR, text(ACTOR_NAME),
                        TRACE_ID, text(context.traceId()),
                        OCCURRED_AT, text(timestamp)))
                .build();
    }
}
