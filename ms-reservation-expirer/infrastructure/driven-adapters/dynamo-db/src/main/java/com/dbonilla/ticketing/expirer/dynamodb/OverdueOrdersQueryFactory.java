package com.dbonilla.ticketing.expirer.dynamodb;

import com.dbonilla.ticketing.expirer.model.order.OverdueOrdersQuery;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.util.Map;

import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.DEADLINE_AT;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.EVENT_ID;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.ORDER_ID;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.QUANTITY;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.STATUS;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.epochSeconds;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.text;

/**
 * {@code Query status-deadlineAt-index: status = :status AND deadlineAt < :now}, paginated with {@code Limit}.
 */
final class OverdueOrdersQueryFactory {

    private final String ordersTable;

    OverdueOrdersQueryFactory(String ordersTable) {
        this.ordersTable = ordersTable;
    }

    QueryRequest build(OverdueOrdersQuery query) {
        QueryRequest.Builder builder = QueryRequest.builder()
                .tableName(ordersTable)
                .indexName(DynamoTables.STATUS_DEADLINE_INDEX)
                .keyConditionExpression("#status = :status AND #deadlineAt < :now")
                .projectionExpression("#orderId, #eventId, #quantity, #status, #deadlineAt")
                .expressionAttributeNames(Map.of(
                        "#orderId", ORDER_ID, "#eventId", EVENT_ID, "#quantity", QUANTITY,
                        "#status", STATUS, "#deadlineAt", DEADLINE_AT))
                .expressionAttributeValues(Map.of(
                        ":status", text(query.status().name()),
                        ":now", epochSeconds(query.dueBefore())))
                .limit(query.limit());
        query.exclusiveStart().ifPresent(cursor -> builder.exclusiveStartKey(Map.<String, AttributeValue>of(
                ORDER_ID, text(cursor.orderId()),
                STATUS, text(cursor.status().name()),
                DEADLINE_AT, epochSeconds(cursor.deadlineAt()))));
        return builder.build();
    }
}
