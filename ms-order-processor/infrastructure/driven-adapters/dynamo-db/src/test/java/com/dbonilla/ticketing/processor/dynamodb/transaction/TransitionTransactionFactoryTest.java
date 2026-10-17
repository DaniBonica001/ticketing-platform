package com.dbonilla.ticketing.processor.dynamodb.transaction;

import com.dbonilla.ticketing.processor.dynamodb.config.TableNames;
import com.dbonilla.ticketing.processor.model.order.Order;
import com.dbonilla.ticketing.processor.model.order.OrderStatus;
import com.dbonilla.ticketing.processor.model.order.TicketState;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransitionTransactionFactoryTest {

    private static final Instant NOW = Instant.parse("2026-10-16T10:00:01.123456Z");
    private static final String TS = "2026-10-16T10:00:01.123Z";
    private final TransitionTransactionFactory factory =
            new TransitionTransactionFactory(new TableNames("ticketing-events", "ticketing-orders", "ticketing-order-audit"));

    private static Order order(OrderStatus status) {
        return new Order("order-1", "event-1", "user-1", 2, status, null, null, "REF-1");
    }

    @Test
    void reservationUpdatesEventThenOrderThenAudit() {
        // Act
        TransactionPlan plan = factory.create(order(OrderStatus.QUEUED).reserve(NOW, Duration.ofMinutes(10)), "trace-1");

        // Assert
        assertThat(plan.roles()).containsExactly(ItemRole.EVENT, ItemRole.ORDER, ItemRole.AUDIT);
        Update event = plan.items().get(0).update();
        assertThat(event.tableName()).isEqualTo("ticketing-events");
        assertThat(event.key()).containsEntry("eventId", AttributeValue.fromS("event-1"));
        assertThat(event.updateExpression()).isEqualTo("SET #from = #from - :q, #to = #to + :q, #updatedAt = :ts");
        assertThat(event.conditionExpression()).isEqualTo("#from >= :q");
        assertThat(event.expressionAttributeNames())
                .containsEntry("#from", "available").containsEntry("#to", "reserved");
        assertThat(event.expressionAttributeValues())
                .containsEntry(":q", AttributeValue.fromN("2")).containsEntry(":ts", AttributeValue.fromS(TS));

        Update order = plan.items().get(1).update();
        assertThat(order.tableName()).isEqualTo("ticketing-orders");
        assertThat(order.updateExpression())
                .isEqualTo("SET #status = :to, #updatedAt = :ts, #deadlineAt = :deadline");
        assertThat(order.conditionExpression()).isEqualTo("#status = :from");
        assertThat(order.expressionAttributeValues())
                .containsEntry(":from", AttributeValue.fromS("QUEUED"))
                .containsEntry(":to", AttributeValue.fromS("RESERVED"))
                .containsEntry(":deadline", AttributeValue.fromN(Long.toString(NOW.plusSeconds(600).getEpochSecond())));

        Map<String, AttributeValue> audit = plan.items().get(2).put().item();
        assertThat(audit)
                .containsEntry("orderId", AttributeValue.fromS("order-1"))
                .containsEntry("transitionId", AttributeValue.fromS(TS + "#RESERVED"))
                .containsEntry("fromStatus", AttributeValue.fromS("QUEUED"))
                .containsEntry("toStatus", AttributeValue.fromS("RESERVED"))
                .containsEntry("quantity", AttributeValue.fromN("2"))
                .containsEntry("actor", AttributeValue.fromS("order-processor"))
                .containsEntry("traceId", AttributeValue.fromS("trace-1"))
                .containsEntry("occurredAt", AttributeValue.fromS(TS))
                .doesNotContainKey("reason");
    }

    @Test
    void soldOutRejectionOnlyWritesOrderAndAudit() {
        TransactionPlan plan = factory.create(order(OrderStatus.QUEUED).rejectSoldOut(NOW), null);

        assertThat(plan.roles()).containsExactly(ItemRole.ORDER, ItemRole.AUDIT);
        Update order = plan.items().get(0).update();
        assertThat(order.updateExpression())
                .isEqualTo("SET #status = :to, #updatedAt = :ts, #reason = :reason REMOVE #deadlineAt");
        assertThat(order.expressionAttributeValues()).containsEntry(":reason", AttributeValue.fromS("SOLD_OUT"));
        Put audit = plan.items().get(1).put();
        assertThat(audit.tableName()).isEqualTo("ticketing-order-audit");
        assertThat(audit.item()).containsEntry("reason", AttributeValue.fromS("SOLD_OUT")).doesNotContainKey("traceId");
    }

    @Test
    void saleUpdatesOrderThenEventThenAudit() {
        TransactionPlan plan = factory.create(order(OrderStatus.PENDING_CONFIRMATION).markSold(NOW), " ");

        assertThat(plan.roles()).containsExactly(ItemRole.ORDER, ItemRole.EVENT, ItemRole.AUDIT);
        assertThat(plan.items().get(0).update().updateExpression())
                .isEqualTo("SET #status = :to, #updatedAt = :ts REMOVE #deadlineAt");
        assertThat(plan.items().get(1).update().expressionAttributeNames())
                .containsEntry("#from", "pendingConfirmation").containsEntry("#to", "sold");
        assertThat(plan.items().get(2).put().item()).doesNotContainKey("traceId");
    }

    @Test
    void declinedPaymentReturnsTicketsToAvailable() {
        TransactionPlan plan = factory.create(order(OrderStatus.PENDING_CONFIRMATION).rejectPaymentDeclined(NOW), "t");

        assertThat(plan.roles()).containsExactly(ItemRole.ORDER, ItemRole.EVENT, ItemRole.AUDIT);
        assertThat(plan.items().get(0).update().expressionAttributeValues())
                .containsEntry(":reason", AttributeValue.fromS("PAYMENT_DECLINED"));
        assertThat(plan.items().get(1).update().expressionAttributeNames())
                .containsEntry("#from", "pendingConfirmation").containsEntry("#to", "available");
        assertThat(plan.toRequest().transactItems()).hasSize(3);
    }

    @Test
    void mapsEveryTicketStateToItsCounter() {
        assertThat(TransitionTransactionFactory.counter(TicketState.AVAILABLE)).isEqualTo("available");
        assertThat(TransitionTransactionFactory.counter(TicketState.RESERVED)).isEqualTo("reserved");
        assertThat(TransitionTransactionFactory.counter(TicketState.PENDING_CONFIRMATION)).isEqualTo("pendingConfirmation");
        assertThat(TransitionTransactionFactory.counter(TicketState.SOLD)).isEqualTo("sold");
        assertThat(TransitionTransactionFactory.counter(TicketState.COMPLIMENTARY)).isEqualTo("complimentary");
    }

    @Test
    void planRequiresOneRolePerItem() {
        assertThatThrownBy(() -> new TransactionPlan(List.of(ItemRole.ORDER), List.<TransactWriteItem>of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
