package com.dbonilla.ticketing.expirer.dynamodb;

import com.dbonilla.ticketing.expirer.model.execution.ExecutionContext;
import com.dbonilla.ticketing.expirer.model.execution.UtcTimestamps;
import com.dbonilla.ticketing.expirer.model.order.Order;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.util.Map;

import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.DEADLINE_AT;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.ORDER_ID;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.STATUS;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.UPDATED_AT;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.epochSeconds;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.text;

/**
 * R1/R2 deadline move: {@code SET deadlineAt = :deadline} with condition {@code status = <same status>}.
 */
final class PostponeDeadlineRequestFactory {

    private final String ordersTable;

    PostponeDeadlineRequestFactory(String ordersTable) {
        this.ordersTable = ordersTable;
    }

    UpdateItemRequest build(Order order, Instant newDeadline, ExecutionContext context) {
        return UpdateItemRequest.builder()
                .tableName(ordersTable)
                .key(Map.of(ORDER_ID, text(order.orderId())))
                .updateExpression("SET #deadlineAt = :deadline, #updatedAt = :ts")
                .conditionExpression("#status = :status")
                .expressionAttributeNames(Map.of(
                        "#deadlineAt", DEADLINE_AT, "#updatedAt", UPDATED_AT, "#status", STATUS))
                .expressionAttributeValues(Map.of(
                        ":deadline", epochSeconds(newDeadline),
                        ":ts", text(UtcTimestamps.format(context.now())),
                        ":status", text(order.status().name())))
                .build();
    }
}
