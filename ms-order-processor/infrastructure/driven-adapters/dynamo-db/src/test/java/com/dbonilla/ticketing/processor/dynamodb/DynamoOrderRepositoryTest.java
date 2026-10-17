package com.dbonilla.ticketing.processor.dynamodb;

import com.dbonilla.ticketing.processor.dynamodb.config.TableNames;
import com.dbonilla.ticketing.processor.metrics.BusinessMetrics;
import com.dbonilla.ticketing.processor.model.exceptions.InventoryConditionFailedException;
import com.dbonilla.ticketing.processor.model.exceptions.TransientFailureException;
import com.dbonilla.ticketing.processor.model.order.Order;
import com.dbonilla.ticketing.processor.model.order.OrderStatus;
import com.dbonilla.ticketing.processor.model.order.RejectionReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamoOrderRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");

    private final DynamoDbAsyncClient client = mock(DynamoDbAsyncClient.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private DynamoOrderRepository repository;

    @BeforeEach
    void setUp() {
        repository = new DynamoOrderRepository(client, new TableNames("events", "orders", "audit"),
                new BusinessMetrics(registry));
    }

    private static Map<String, AttributeValue> item() {
        return Map.of(
                "orderId", AttributeValue.fromS("order-1"),
                "eventId", AttributeValue.fromS("event-1"),
                "userId", AttributeValue.fromS("user-1"),
                "quantity", AttributeValue.fromN("2"),
                "status", AttributeValue.fromS("PENDING_CONFIRMATION"),
                "deadlineAt", AttributeValue.fromN("1792145100"),
                "paymentReference", AttributeValue.fromS("REF-1"));
    }

    @Test
    void readsOrderWithConsistentRead() {
        // Arrange
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetItemResponse.builder().item(item()).build()));

        // Act & Assert
        StepVerifier.create(repository.findById("order-1"))
                .assertNext(order -> {
                    assertThat(order.status()).isEqualTo(OrderStatus.PENDING_CONFIRMATION);
                    assertThat(order.quantity()).isEqualTo(2);
                    assertThat(order.paymentReference()).isEqualTo("REF-1");
                    assertThat(order.deadlineAt()).isEqualTo(Instant.ofEpochSecond(1792145100L));
                    assertThat(order.reason()).isNull();
                })
                .verifyComplete();
        ArgumentCaptor<GetItemRequest> captor = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(client).getItem(captor.capture());
        assertThat(captor.getValue().consistentRead()).isTrue();
        assertThat(captor.getValue().tableName()).isEqualTo("orders");
    }

    @Test
    void mapsRejectionReason() {
        Map<String, AttributeValue> rejected = new java.util.HashMap<>(item());
        rejected.put("status", AttributeValue.fromS("REJECTED"));
        rejected.put("reason", AttributeValue.fromS("SOLD_OUT"));
        rejected.remove("deadlineAt");
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetItemResponse.builder().item(rejected).build()));

        StepVerifier.create(repository.findById("order-1"))
                .assertNext(order -> {
                    assertThat(order.reason()).isEqualTo(RejectionReason.SOLD_OUT);
                    assertThat(order.deadlineAt()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void returnsEmptyWhenTheOrderDoesNotExist() {
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetItemResponse.builder().build()));

        StepVerifier.create(repository.findById("missing")).verifyComplete();
    }

    @Test
    void failsWhenAnItemIsIncomplete() {
        when(client.getItem(any(GetItemRequest.class))).thenReturn(CompletableFuture.completedFuture(
                GetItemResponse.builder().item(Map.of("orderId", AttributeValue.fromS("o"))).build()));

        StepVerifier.create(repository.findById("o")).expectError(IllegalStateException.class).verify();
    }

    @Test
    void failsWhenAnItemHasNoQuantity() {
        Map<String, AttributeValue> withoutQuantity = new java.util.HashMap<>(item());
        withoutQuantity.remove("quantity");
        when(client.getItem(any(GetItemRequest.class))).thenReturn(CompletableFuture.completedFuture(
                GetItemResponse.builder().item(withoutQuantity).build()));

        StepVerifier.create(repository.findById("o")).expectError(IllegalStateException.class).verify();
    }

    @Test
    void wrapsTransientReadErrors() {
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(SdkClientException.create("timeout")));

        StepVerifier.create(repository.findById("order-1")).expectError(TransientFailureException.class).verify();
    }

    @Test
    void appliesTransitionAndRecordsMetrics() {
        // Arrange
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));
        Order pending = new Order("order-1", "event-1", "u", 2, OrderStatus.PENDING_CONFIRMATION, null, null, "DECLINED-1");

        // Act
        StepVerifier.create(repository.apply(pending.rejectPaymentDeclined(NOW), "trace")).verifyComplete();

        // Assert
        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(captor.capture());
        assertThat(captor.getValue().transactItems()).hasSize(3);
        assertThat(registry.get("ticketing.orders.transitions").tag("from", "PENDING_CONFIRMATION")
                .tag("to", "REJECTED").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ticketing.orders.rejected").tag("reason", "PAYMENT_DECLINED").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void mapsCancellationAndCountsConflicts() {
        Order queued = new Order("order-1", "event-1", "u", 2, OrderStatus.QUEUED, null, null, null);
        TransactionCanceledException conflict = TransactionCanceledException.builder()
                .cancellationReasons(CancellationReason.builder().code("TransactionConflict").build(),
                        CancellationReason.builder().code("None").build(),
                        CancellationReason.builder().code("None").build())
                .build();
        TransactionCanceledException soldOut = TransactionCanceledException.builder()
                .cancellationReasons(CancellationReason.builder().code("ConditionalCheckFailed").build(),
                        CancellationReason.builder().code("None").build(),
                        CancellationReason.builder().code("None").build())
                .build();
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(conflict))
                .thenReturn(CompletableFuture.failedFuture(soldOut));

        StepVerifier.create(repository.apply(queued.reserve(NOW, Duration.ofMinutes(10)), "t"))
                .expectError(TransientFailureException.class).verify();
        StepVerifier.create(repository.apply(queued.reserve(NOW, Duration.ofMinutes(10)), "t"))
                .expectError(InventoryConditionFailedException.class).verify();
        assertThat(registry.get("ticketing.dynamodb.transaction.conflicts").counter().count()).isEqualTo(1.0);
    }
}
