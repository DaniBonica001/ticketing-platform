package com.dbonilla.ticketing.api.dynamodb;

import com.dbonilla.ticketing.api.dynamodb.mapper.OrderItemMapper;
import com.dbonilla.ticketing.api.model.exception.InsufficientInventoryException;
import com.dbonilla.ticketing.api.model.exception.ServiceUnavailableException;
import com.dbonilla.ticketing.api.model.order.ConfirmWriteResult;
import com.dbonilla.ticketing.api.model.order.Order;
import com.dbonilla.ticketing.api.model.order.OrderStatus;
import com.dbonilla.ticketing.api.model.order.OrderWriteResult;
import com.dbonilla.ticketing.api.model.ordertransition.OrderTransition;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.util.concurrent.CompletableFuture;

import static com.dbonilla.ticketing.api.dynamodb.DynamoFixtures.NOW;
import static com.dbonilla.ticketing.api.dynamodb.DynamoFixtures.ORDER_ID;
import static com.dbonilla.ticketing.api.dynamodb.DynamoFixtures.canceled;
import static com.dbonilla.ticketing.api.dynamodb.DynamoFixtures.reason;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderDynamoRepositoryTest {

    private static final Order QUEUED = DynamoFixtures.order(OrderStatus.QUEUED);
    private static final Order COMPLIMENTARY = new Order(ORDER_ID, DynamoFixtures.EVENT_ID, "vip", 2,
            OrderStatus.COMPLIMENTARY, null, null, "hash", null, NOW, NOW);

    @Mock
    private DynamoDbAsyncClient client;

    private SimpleMeterRegistry registry;
    private OrderDynamoRepository repository;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        repository = new OrderDynamoRepository(client, DynamoFixtures.executor(registry),
                new com.dbonilla.ticketing.api.dynamodb.support.TransactionMetrics(registry), DynamoFixtures.TABLES);
    }

    private void transactionSucceeds() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));
    }

    private void transactionFails(Throwable error) {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(error));
    }

    private TransactWriteItemsRequest sentTransaction() {
        ArgumentCaptor<TransactWriteItemsRequest> request = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(request.capture());
        return request.getValue();
    }

    @Test
    void placeWritesOrderAndAuditInOneTransaction() {
        transactionSucceeds();

        StepVerifier.create(repository.place(QUEUED, OrderTransition.created(QUEUED)))
                .expectNext(new OrderWriteResult.Created(QUEUED))
                .verifyComplete();

        TransactWriteItemsRequest request = sentTransaction();
        assertThat(request.transactItems()).hasSize(2);
        Put order = request.transactItems().get(0).put();
        assertThat(order.tableName()).isEqualTo("ticketing-orders");
        assertThat(order.conditionExpression()).isEqualTo("attribute_not_exists(orderId)");
        assertThat(order.returnValuesOnConditionCheckFailure()).isEqualTo(ReturnValuesOnConditionCheckFailure.ALL_OLD);
        assertThat(order.item().get("status").s()).isEqualTo("QUEUED");
        Put audit = request.transactItems().get(1).put();
        assertThat(audit.tableName()).isEqualTo("ticketing-order-audit");
        assertThat(audit.item().get("actor").s()).isEqualTo("ticketing-api");
        assertThat(registry.get("ticketing.orders.created").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ticketing.orders.transitions").tag("from", "NONE").counter().count()).isEqualTo(1.0);
    }

    @Test
    void placeReturnsStoredOrderWhenItAlreadyExists() {
        Order stored = DynamoFixtures.order(OrderStatus.RESERVED);
        transactionFails(canceled(reason("ConditionalCheckFailed", OrderItemMapper.toItem(stored)), reason("None")));

        StepVerifier.create(repository.place(QUEUED, OrderTransition.created(QUEUED)))
                .expectNext(new OrderWriteResult.AlreadyExists(stored))
                .verifyComplete();
        verify(client, times(1)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void placeReadsStoredOrderWhenTheBackendDoesNotReturnIt() {
        Order stored = DynamoFixtures.order(OrderStatus.RESERVED);
        transactionFails(canceled(reason("ConditionalCheckFailed"), reason("None")));
        when(client.getItem(any(GetItemRequest.class))).thenReturn(CompletableFuture.completedFuture(
                GetItemResponse.builder().item(OrderItemMapper.toItem(stored)).build()));

        StepVerifier.create(repository.place(QUEUED, OrderTransition.created(QUEUED)))
                .expectNext(new OrderWriteResult.AlreadyExists(stored))
                .verifyComplete();
    }

    @Test
    void placeRetriesTransactionConflictsAndCountsThem() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(canceled(reason("TransactionConflict"), reason("None"))))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));

        StepVerifier.create(repository.place(QUEUED, OrderTransition.created(QUEUED)))
                .expectNext(new OrderWriteResult.Created(QUEUED))
                .verifyComplete();
        verify(client, times(2)).transactWriteItems(any(TransactWriteItemsRequest.class));
        assertThat(registry.get("ticketing.dynamodb.transaction.conflicts").counter().count()).isEqualTo(1.0);
    }

    @Test
    void placeReportsUnavailableWhenConflictsPersist() {
        transactionFails(canceled(reason("TransactionConflict"), reason("None")));

        StepVerifier.create(repository.place(QUEUED, OrderTransition.created(QUEUED)))
                .verifyError(ServiceUnavailableException.class);
        verify(client, times(4)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void placePropagatesOtherCancellations() {
        transactionFails(canceled(reason("None"), reason("ValidationError")));

        StepVerifier.create(repository.place(QUEUED, OrderTransition.created(QUEUED)))
                .verifyError(TransactionCanceledException.class);
    }

    @Test
    void complimentaryMovesInventoryAndCreatesOrder() {
        transactionSucceeds();

        StepVerifier.create(repository.issueComplimentary(COMPLIMENTARY, OrderTransition.created(COMPLIMENTARY)))
                .expectNext(new OrderWriteResult.Created(COMPLIMENTARY))
                .verifyComplete();

        TransactWriteItemsRequest request = sentTransaction();
        assertThat(request.transactItems()).hasSize(3);
        Update event = request.transactItems().get(0).update();
        assertThat(event.tableName()).isEqualTo("ticketing-events");
        assertThat(event.updateExpression()).isEqualTo(OrderTransactions.COMPLIMENTARY_UPDATE);
        assertThat(event.conditionExpression()).isEqualTo("available >= :q");
        assertThat(event.expressionAttributeValues().get(":q").n()).isEqualTo("2");
        assertThat(request.transactItems().get(1).put().item().get("userId").s()).isEqualTo("vip");
        assertThat(request.transactItems().get(2).put().tableName()).isEqualTo("ticketing-order-audit");
    }

    @Test
    void complimentaryWithoutInventoryIsRejected() {
        transactionFails(canceled(reason("ConditionalCheckFailed"), reason("None"), reason("None")));

        StepVerifier.create(repository.issueComplimentary(COMPLIMENTARY, OrderTransition.created(COMPLIMENTARY)))
                .verifyError(InsufficientInventoryException.class);
    }

    @Test
    void complimentaryReplayReturnsStoredOrderEvenWithoutInventory() {
        transactionFails(canceled(reason("ConditionalCheckFailed"),
                reason("ConditionalCheckFailed", OrderItemMapper.toItem(COMPLIMENTARY)), reason("None")));

        StepVerifier.create(repository.issueComplimentary(COMPLIMENTARY, OrderTransition.created(COMPLIMENTARY)))
                .expectNext(new OrderWriteResult.AlreadyExists(COMPLIMENTARY))
                .verifyComplete();
    }

    @Test
    void complimentaryPropagatesOtherCancellations() {
        transactionFails(canceled(reason("None"), reason("None"), reason("ValidationError")));

        StepVerifier.create(repository.issueComplimentary(COMPLIMENTARY, OrderTransition.created(COMPLIMENTARY)))
                .verifyError(TransactionCanceledException.class);
    }

    @Test
    void confirmUpdatesOrderAndEventWithExactConditions() {
        Order reserved = DynamoFixtures.order(OrderStatus.RESERVED);
        Order next = new Order(ORDER_ID, reserved.eventId(), reserved.userId(), 2, OrderStatus.PENDING_CONFIRMATION,
                null, NOW.plusSeconds(300), "hash", "PAY-1", NOW, NOW);
        transactionSucceeds();

        StepVerifier.create(repository.confirm(next, OrderTransition.between(reserved, next), "user-1", NOW))
                .expectNext(new ConfirmWriteResult.Confirmed(next))
                .verifyComplete();

        TransactWriteItemsRequest request = sentTransaction();
        Update order = request.transactItems().get(0).update();
        assertThat(order.conditionExpression()).isEqualTo(OrderTransactions.CONFIRM_ORDER_CONDITION);
        assertThat(order.updateExpression()).isEqualTo(OrderTransactions.CONFIRM_ORDER_UPDATE);
        assertThat(order.expressionAttributeValues().get(":now").n()).isEqualTo(Long.toString(NOW.getEpochSecond()));
        assertThat(order.expressionAttributeValues().get(":caller").s()).isEqualTo("user-1");
        assertThat(order.expressionAttributeValues().get(":ref").s()).isEqualTo("PAY-1");
        assertThat(order.expressionAttributeNames()).containsEntry("#status", "status");
        Update event = request.transactItems().get(1).update();
        assertThat(event.updateExpression()).isEqualTo(OrderTransactions.CONFIRM_EVENT_UPDATE);
        assertThat(event.conditionExpression()).isEqualTo("reserved >= :q");
        assertThat(request.transactItems().get(2).put().item().get("fromStatus").s()).isEqualTo("RESERVED");
        assertThat(registry.get("ticketing.orders.transitions").tag("to", "PENDING_CONFIRMATION").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void confirmConflictReturnsTheStoredOrder() {
        Order expired = DynamoFixtures.order(OrderStatus.EXPIRED);
        transactionFails(canceled(reason("ConditionalCheckFailed", OrderItemMapper.toItem(expired)),
                reason("None"), reason("None")));

        StepVerifier.create(repository.confirm(QUEUED, OrderTransition.created(QUEUED), "user-1", NOW))
                .expectNext(new ConfirmWriteResult.Conflict(expired))
                .verifyComplete();
    }

    @Test
    void confirmConflictOnMissingOrderHasNoCurrentOrder() {
        transactionFails(canceled(reason("ConditionalCheckFailed"), reason("None"), reason("None")));
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetItemResponse.builder().build()));

        StepVerifier.create(repository.confirm(QUEUED, OrderTransition.created(QUEUED), "user-1", NOW))
                .expectNext(new ConfirmWriteResult.Conflict(null))
                .verifyComplete();
    }

    @Test
    void confirmWithInconsistentInventoryFails() {
        transactionFails(canceled(reason("None"), reason("ConditionalCheckFailed"), reason("None")));

        StepVerifier.create(repository.confirm(QUEUED, OrderTransition.created(QUEUED), "user-1", NOW))
                .verifyError(IllegalStateException.class);
    }

    @Test
    void confirmPropagatesOtherCancellations() {
        transactionFails(canceled(reason("ValidationError")));

        StepVerifier.create(repository.confirm(QUEUED, OrderTransition.created(QUEUED), "user-1", NOW))
                .verifyError(TransactionCanceledException.class);
    }

    @Test
    void findsOrderWithStrongConsistency() {
        when(client.getItem(any(GetItemRequest.class))).thenReturn(CompletableFuture.completedFuture(
                GetItemResponse.builder().item(OrderItemMapper.toItem(QUEUED)).build()));

        StepVerifier.create(repository.findById(ORDER_ID)).expectNext(QUEUED).verifyComplete();

        ArgumentCaptor<GetItemRequest> request = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(client).getItem(request.capture());
        assertThat(request.getValue().consistentRead()).isTrue();
        assertThat(request.getValue().tableName()).isEqualTo("ticketing-orders");
    }
}
