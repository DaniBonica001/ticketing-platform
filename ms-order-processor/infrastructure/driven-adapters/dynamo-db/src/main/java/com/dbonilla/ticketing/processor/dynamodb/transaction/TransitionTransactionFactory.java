package com.dbonilla.ticketing.processor.dynamodb.transaction;

import com.dbonilla.ticketing.processor.dynamodb.Attributes;
import com.dbonilla.ticketing.processor.dynamodb.config.TableNames;
import com.dbonilla.ticketing.processor.model.order.InventoryMovement;
import com.dbonilla.ticketing.processor.model.order.OrderTransition;
import com.dbonilla.ticketing.processor.model.order.TicketState;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the exact {@code TransactWriteItems} of docs/design/dynamodb.md, section 4, for a domain transition.
 * Item order: T2 = events, orders, audit; T3 = orders, audit; T5/T6 = orders, events, audit.
 */
public final class TransitionTransactionFactory {

    public static final String ACTOR = "order-processor";

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final TableNames tables;

    public TransitionTransactionFactory(TableNames tables) {
        this.tables = tables;
    }

    public TransactionPlan create(OrderTransition transition, String traceId) {
        String timestamp = format(transition.occurredAt());
        List<ItemRole> roles = new ArrayList<>();
        List<TransactWriteItem> items = new ArrayList<>();
        boolean eventFirst = transition.inventoryMovement()
                .map(movement -> movement.from() == TicketState.AVAILABLE)
                .orElse(false);
        if (eventFirst) {
            add(roles, items, ItemRole.EVENT, eventUpdate(transition, transition.movement(), timestamp));
        }
        add(roles, items, ItemRole.ORDER, orderUpdate(transition, timestamp));
        if (!eventFirst) {
            transition.inventoryMovement().ifPresent(movement ->
                    add(roles, items, ItemRole.EVENT, eventUpdate(transition, movement, timestamp)));
        }
        add(roles, items, ItemRole.AUDIT, auditPut(transition, traceId, timestamp));
        return new TransactionPlan(roles, items);
    }

    public static String format(Instant instant) {
        return TIMESTAMP.format(instant.truncatedTo(ChronoUnit.MILLIS));
    }

    private static void add(List<ItemRole> roles, List<TransactWriteItem> items, ItemRole role, TransactWriteItem item) {
        roles.add(role);
        items.add(item);
    }

    private TransactWriteItem eventUpdate(OrderTransition transition, InventoryMovement movement, String timestamp) {
        Update update = Update.builder()
                .tableName(tables.events())
                .key(Map.of(Attributes.EVENT_ID, s(transition.eventId())))
                .updateExpression("SET #from = #from - :q, #to = #to + :q, #updatedAt = :ts")
                .conditionExpression("#from >= :q")
                .expressionAttributeNames(Map.of(
                        "#from", counter(movement.from()),
                        "#to", counter(movement.to()),
                        "#updatedAt", Attributes.UPDATED_AT))
                .expressionAttributeValues(Map.of(
                        ":q", n(transition.quantity()),
                        ":ts", s(timestamp)))
                .build();
        return TransactWriteItem.builder().update(update).build();
    }

    private TransactWriteItem orderUpdate(OrderTransition transition, String timestamp) {
        Map<String, String> names = new HashMap<>(Map.of(
                "#status", Attributes.STATUS,
                "#updatedAt", Attributes.UPDATED_AT,
                "#deadlineAt", Attributes.DEADLINE_AT));
        Map<String, AttributeValue> values = new HashMap<>(Map.of(
                ":from", s(transition.from().name()),
                ":to", s(transition.to().name()),
                ":ts", s(timestamp)));
        StringBuilder set = new StringBuilder("SET #status = :to, #updatedAt = :ts");
        transition.rejectionReason().ifPresent(reason -> {
            names.put("#reason", Attributes.REASON);
            values.put(":reason", s(reason.name()));
            set.append(", #reason = :reason");
        });
        String expression = transition.deadline()
                .map(deadline -> {
                    values.put(":deadline", n(deadline.getEpochSecond()));
                    return set.append(", #deadlineAt = :deadline").toString();
                })
                .orElseGet(() -> set.append(" REMOVE #deadlineAt").toString());
        Update update = Update.builder()
                .tableName(tables.orders())
                .key(Map.of(Attributes.ORDER_ID, s(transition.orderId())))
                .updateExpression(expression)
                .conditionExpression("#status = :from")
                .expressionAttributeNames(Map.copyOf(names))
                .expressionAttributeValues(Map.copyOf(values))
                .build();
        return TransactWriteItem.builder().update(update).build();
    }

    private TransactWriteItem auditPut(OrderTransition transition, String traceId, String timestamp) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Attributes.ORDER_ID, s(transition.orderId()));
        item.put(Attributes.TRANSITION_ID, s(timestamp + "#" + transition.to().name()));
        item.put(Attributes.EVENT_ID, s(transition.eventId()));
        item.put(Attributes.FROM_STATUS, s(transition.from().name()));
        item.put(Attributes.TO_STATUS, s(transition.to().name()));
        item.put(Attributes.QUANTITY, n(transition.quantity()));
        item.put(Attributes.ACTOR, s(ACTOR));
        item.put(Attributes.OCCURRED_AT, s(timestamp));
        transition.rejectionReason().ifPresent(reason -> item.put(Attributes.REASON, s(reason.name())));
        if (traceId != null && !traceId.isBlank()) {
            item.put(Attributes.TRACE_ID, s(traceId));
        }
        Put put = Put.builder().tableName(tables.audit()).item(Map.copyOf(item)).build();
        return TransactWriteItem.builder().put(put).build();
    }

    static String counter(TicketState state) {
        return switch (state) {
            case AVAILABLE -> Attributes.AVAILABLE;
            case RESERVED -> Attributes.RESERVED;
            case PENDING_CONFIRMATION -> Attributes.PENDING_CONFIRMATION;
            case SOLD -> Attributes.SOLD;
            case COMPLIMENTARY -> Attributes.COMPLIMENTARY;
        };
    }

    private static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    private static AttributeValue n(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }
}
