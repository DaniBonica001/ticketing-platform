package com.dbonilla.ticketing.expirer.dynamodb;

import com.dbonilla.ticketing.expirer.helpers.retry.RetrySettings;
import com.dbonilla.ticketing.expirer.helpers.retry.TransientRetry;
import com.dbonilla.ticketing.expirer.model.execution.ExecutionContext;
import com.dbonilla.ticketing.expirer.model.execution.TimeBudget;
import com.dbonilla.ticketing.expirer.model.order.DeadlineUpdate;
import com.dbonilla.ticketing.expirer.model.order.ExpirationOutcome;
import com.dbonilla.ticketing.expirer.model.order.InventoryInvariantViolationException;
import com.dbonilla.ticketing.expirer.model.order.Order;
import com.dbonilla.ticketing.expirer.model.order.OrderStatus;
import com.dbonilla.ticketing.expirer.model.order.OverdueOrdersQuery;
import com.dbonilla.ticketing.expirer.model.order.PageCursor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamoDbOrderRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-16T10:12:00.123Z");
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final DynamoTables TABLES =
            new DynamoTables("ticketing-events", "ticketing-orders", "ticketing-order-audit");
    private static final RetrySettings FAST = new RetrySettings(3, Duration.ofMillis(1), Duration.ofMillis(2), 0);

    private final DynamoDbAsyncClient client = mock(DynamoDbAsyncClient.class);
    private final DynamoDbOrderRepository repository =
            new DynamoDbOrderRepository(client, TABLES, TransientRetry.backoff(FAST));
    private final ExecutionContext context = new ExecutionContext(NOW, TRACE_ID, TimeBudget.unlimited());
    private final Order order = new Order("order-1", "event-1", 3, OrderStatus.RESERVED, NOW.minusSeconds(60));

    private static Map<String, AttributeValue> item(String id, String status, String quantity, String deadline) {
        return Map.of("orderId", AttributeValue.fromS(id), "eventId", AttributeValue.fromS("event-1"),
                "quantity", AttributeValue.fromN(quantity), "status", AttributeValue.fromS(status),
                "deadlineAt", AttributeValue.fromN(deadline));
    }

    private static TransactionCanceledException cancelled(String... codes) {
        return TransactionCanceledException.builder().message("cancelled")
                .cancellationReasons(Arrays.stream(codes).map(code -> CancellationReason.builder().code(code).build())
                        .toList())
                .build();
    }

    @Test
    void queriesTheSparseIndexForOverdueOrders() {
        when(client.query(any(QueryRequest.class))).thenReturn(completedFuture(QueryResponse.builder().build()));

        StepVerifier.create(repository.findOverdue(OverdueOrdersQuery.firstPage(OrderStatus.RESERVED, NOW, 25)))
                .assertNext(page -> {
                    assertThat(page.orders()).isEmpty();
                    assertThat(page.next()).isEmpty();
                })
                .verifyComplete();

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client).query(captor.capture());
        QueryRequest request = captor.getValue();
        assertThat(request.tableName()).isEqualTo("ticketing-orders");
        assertThat(request.indexName()).isEqualTo("status-deadlineAt-index");
        assertThat(request.keyConditionExpression()).isEqualTo("#status = :status AND #deadlineAt < :now");
        assertThat(request.expressionAttributeNames()).containsEntry("#status", "status")
                .containsEntry("#deadlineAt", "deadlineAt");
        assertThat(request.expressionAttributeValues()).containsEntry(":status", AttributeValue.fromS("RESERVED"))
                .containsEntry(":now", AttributeValue.fromN(Long.toString(NOW.getEpochSecond())));
        assertThat(request.limit()).isEqualTo(25);
        assertThat(request.hasExclusiveStartKey()).isFalse();
    }

    @Test
    void continuesFromTheCursorAndReturnsTheNextOne() {
        PageCursor start = new PageCursor("order-0", OrderStatus.QUEUED, NOW.minusSeconds(100));
        QueryResponse response = QueryResponse.builder()
                .items(item("order-1", "QUEUED", "2", "1792200000"),
                        item("order-2", "NOT_A_STATUS", "1", "1792200000"),
                        Map.of("eventId", AttributeValue.fromS("event-1")))
                .lastEvaluatedKey(Map.of("orderId", AttributeValue.fromS("order-2"),
                        "status", AttributeValue.fromS("QUEUED"), "deadlineAt", AttributeValue.fromN("1792200000")))
                .build();
        when(client.query(any(QueryRequest.class))).thenReturn(completedFuture(response));

        OverdueOrdersQuery query = OverdueOrdersQuery.firstPage(OrderStatus.QUEUED, NOW, 10).nextPage(start);
        StepVerifier.create(repository.findOverdue(query))
                .assertNext(page -> {
                    assertThat(page.orders()).singleElement().satisfies(found -> {
                        assertThat(found.orderId()).isEqualTo("order-1");
                        assertThat(found.quantity()).isEqualTo(2);
                        assertThat(found.deadlineAt()).isEqualTo(Instant.ofEpochSecond(1792200000));
                    });
                    assertThat(page.next()).contains(
                            new PageCursor("order-2", OrderStatus.QUEUED, Instant.ofEpochSecond(1792200000)));
                })
                .verifyComplete();

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client).query(captor.capture());
        assertThat(captor.getValue().exclusiveStartKey())
                .containsEntry("orderId", AttributeValue.fromS("order-0"))
                .containsEntry("status", AttributeValue.fromS("QUEUED"))
                .containsEntry("deadlineAt", AttributeValue.fromN(Long.toString(NOW.minusSeconds(100).getEpochSecond())));
    }

    @Test
    void retriesTransientQueryErrors() {
        when(client.query(any(QueryRequest.class)))
                .thenReturn(failedFuture(software.amazon.awssdk.core.exception.SdkClientException.create("reset")))
                .thenReturn(completedFuture(QueryResponse.builder().build()));

        StepVerifier.create(repository.findOverdue(OverdueOrdersQuery.firstPage(OrderStatus.RESERVED, NOW, 5)))
                .expectNextCount(1)
                .verifyComplete();

        verify(client, times(2)).query(any(QueryRequest.class));
    }

    @Test
    void expiresWithOneTransactionOverOrderEventAndAudit() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(completedFuture(TransactWriteItemsResponse.builder().build()));

        StepVerifier.create(repository.expire(order, context)).expectNext(ExpirationOutcome.EXPIRED).verifyComplete();

        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(captor.capture());
        var items = captor.getValue().transactItems();
        assertThat(items).hasSize(3);

        Update orderUpdate = items.get(0).update();
        assertThat(orderUpdate.tableName()).isEqualTo("ticketing-orders");
        assertThat(orderUpdate.key()).containsEntry("orderId", AttributeValue.fromS("order-1"));
        assertThat(orderUpdate.updateExpression())
                .isEqualTo("SET #status = :expired, #updatedAt = :ts REMOVE #deadlineAt");
        assertThat(orderUpdate.conditionExpression()).isEqualTo("#status = :reserved AND #deadlineAt < :now");
        assertThat(orderUpdate.expressionAttributeValues())
                .containsEntry(":expired", AttributeValue.fromS("EXPIRED"))
                .containsEntry(":reserved", AttributeValue.fromS("RESERVED"))
                .containsEntry(":ts", AttributeValue.fromS("2026-10-16T10:12:00.123Z"))
                .containsEntry(":now", AttributeValue.fromN(Long.toString(NOW.getEpochSecond())));

        Update eventUpdate = items.get(1).update();
        assertThat(eventUpdate.tableName()).isEqualTo("ticketing-events");
        assertThat(eventUpdate.key()).containsEntry("eventId", AttributeValue.fromS("event-1"));
        assertThat(eventUpdate.updateExpression())
                .isEqualTo("SET #reserved = #reserved - :q, #available = #available + :q, #updatedAt = :ts");
        assertThat(eventUpdate.conditionExpression()).isEqualTo("#reserved >= :q");
        assertThat(eventUpdate.expressionAttributeValues()).containsEntry(":q", AttributeValue.fromN("3"));

        Put audit = items.get(2).put();
        assertThat(audit.tableName()).isEqualTo("ticketing-order-audit");
        assertThat(audit.item())
                .containsEntry("orderId", AttributeValue.fromS("order-1"))
                .containsEntry("transitionId", AttributeValue.fromS("2026-10-16T10:12:00.123Z#EXPIRED"))
                .containsEntry("fromStatus", AttributeValue.fromS("RESERVED"))
                .containsEntry("toStatus", AttributeValue.fromS("EXPIRED"))
                .containsEntry("quantity", AttributeValue.fromN("3"))
                .containsEntry("actor", AttributeValue.fromS("reservation-expirer"))
                .containsEntry("traceId", AttributeValue.fromS(TRACE_ID))
                .containsEntry("occurredAt", AttributeValue.fromS("2026-10-16T10:12:00.123Z"));
    }

    @Test
    void orderConditionFailureMeansTheUserConfirmedConcurrently() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(failedFuture(cancelled("ConditionalCheckFailed", "None", "None")));

        StepVerifier.create(repository.expire(order, context))
                .expectNext(ExpirationOutcome.ALREADY_TRANSITIONED)
                .verifyComplete();

        verify(client, times(1)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void eventConditionFailureIsAnInventoryInvariantViolation() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(failedFuture(cancelled("None", "ConditionalCheckFailed", "None")));

        StepVerifier.create(repository.expire(order, context))
                .verifyError(InventoryInvariantViolationException.class);

        verify(client, times(1)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void transactionConflictsAreRetried() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(failedFuture(cancelled("None", "TransactionConflict", "None")))
                .thenReturn(completedFuture(TransactWriteItemsResponse.builder().build()));

        StepVerifier.create(repository.expire(order, context)).expectNext(ExpirationOutcome.EXPIRED).verifyComplete();

        verify(client, times(2)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void otherCancellationsArePropagatedAfterRetries() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(failedFuture(cancelled("None", "None", "ValidationError")));

        StepVerifier.create(repository.expire(order, context)).verifyError(TransactionCanceledException.class);
    }

    @Test
    void cancellationWithoutReasonsIsUnclassified() {
        TransactionCanceledException noReasons = TransactionCanceledException.builder().message("x").build();

        assertThat(CancellationCause.of(noReasons)).isEqualTo(CancellationCause.OTHER);
    }

    @Test
    void postponesTheDeadlineOnlyInTheSameStatus() {
        Order queued = new Order("order-9", "event-1", 1, OrderStatus.QUEUED, NOW.minusSeconds(10));
        when(client.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(completedFuture(UpdateItemResponse.builder().build()));

        StepVerifier.create(repository.postponeDeadline(queued, NOW.plusSeconds(300), context))
                .expectNext(DeadlineUpdate.POSTPONED)
                .verifyComplete();

        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(client).updateItem(captor.capture());
        UpdateItemRequest request = captor.getValue();
        assertThat(request.tableName()).isEqualTo("ticketing-orders");
        assertThat(request.key()).containsEntry("orderId", AttributeValue.fromS("order-9"));
        assertThat(request.updateExpression()).isEqualTo("SET #deadlineAt = :deadline, #updatedAt = :ts");
        assertThat(request.conditionExpression()).isEqualTo("#status = :status");
        assertThat(request.expressionAttributeValues())
                .containsEntry(":status", AttributeValue.fromS("QUEUED"))
                .containsEntry(":deadline", AttributeValue.fromN(Long.toString(NOW.plusSeconds(300).getEpochSecond())));
    }

    @Test
    void postponeConditionFailureMeansTheOrderWasProcessed() {
        when(client.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(failedFuture(ConditionalCheckFailedException.builder().message("x").build()));

        StepVerifier.create(repository.postponeDeadline(order, NOW.plusSeconds(300), context))
                .expectNext(DeadlineUpdate.STATE_CHANGED)
                .verifyComplete();
    }

    @Test
    void tableNamesAreRequired() {
        assertThatThrownBy(() -> new DynamoTables(" ", "o", "a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DynamoTables("e", null, "a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DynamoTables("e", "o", "")).isInstanceOf(IllegalArgumentException.class);
    }
}
