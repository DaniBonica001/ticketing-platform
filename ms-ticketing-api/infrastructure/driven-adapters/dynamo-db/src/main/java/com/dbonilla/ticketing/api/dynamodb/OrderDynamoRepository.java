package com.dbonilla.ticketing.api.dynamodb;

import com.dbonilla.ticketing.api.dynamodb.config.DynamoTablesProperties;
import com.dbonilla.ticketing.api.dynamodb.mapper.OrderItemMapper;
import com.dbonilla.ticketing.api.dynamodb.support.Attributes;
import com.dbonilla.ticketing.api.dynamodb.support.Cancellations;
import com.dbonilla.ticketing.api.dynamodb.support.DynamoExecutor;
import com.dbonilla.ticketing.api.dynamodb.support.TransactionMetrics;
import com.dbonilla.ticketing.api.model.exception.InsufficientInventoryException;
import com.dbonilla.ticketing.api.model.order.ConfirmWriteResult;
import com.dbonilla.ticketing.api.model.order.Order;
import com.dbonilla.ticketing.api.model.order.OrderWriteResult;
import com.dbonilla.ticketing.api.model.order.gateways.OrderRepository;
import com.dbonilla.ticketing.api.model.ordertransition.OrderTransition;
import com.dbonilla.ticketing.api.resilience.TraceContext;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.Instant;

import static com.dbonilla.ticketing.api.dynamodb.OrderTransactions.COMPLIMENTARY_EVENT_INDEX;
import static com.dbonilla.ticketing.api.dynamodb.OrderTransactions.COMPLIMENTARY_ORDER_INDEX;
import static com.dbonilla.ticketing.api.dynamodb.OrderTransactions.CONFIRM_EVENT_INDEX;
import static com.dbonilla.ticketing.api.dynamodb.OrderTransactions.CONFIRM_ORDER_INDEX;
import static com.dbonilla.ticketing.api.dynamodb.OrderTransactions.PLACE_ORDER_INDEX;

@Repository
public class OrderDynamoRepository implements OrderRepository {

    private final DynamoDbAsyncClient client;
    private final DynamoExecutor executor;
    private final TransactionMetrics metrics;
    private final OrderTransactions transactions;
    private final String table;

    public OrderDynamoRepository(DynamoDbAsyncClient client, DynamoExecutor executor, TransactionMetrics metrics,
                                 DynamoTablesProperties tables) {
        this.client = client;
        this.executor = executor;
        this.metrics = metrics;
        this.transactions = new OrderTransactions(tables);
        this.table = tables.ordersTable();
    }

    @Override
    public Mono<Order> findById(String orderId) {
        GetItemRequest request = GetItemRequest.builder()
                .tableName(table)
                .key(OrderItemMapper.key(orderId))
                .consistentRead(true)
                .build();
        return executor.execute(() -> client.getItem(request))
                .filter(response -> Attributes.isPresent(response.item()))
                .map(response -> OrderItemMapper.toOrder(response.item()));
    }

    @Override
    public Mono<OrderWriteResult> place(Order order, OrderTransition transition) {
        TransactWriteItemsRequest request = transactions.place(order, transition, TraceContext.currentTraceId());
        return create(request, order, transition, PLACE_ORDER_INDEX);
    }

    @Override
    public Mono<OrderWriteResult> issueComplimentary(Order order, OrderTransition transition) {
        TransactWriteItemsRequest request =
                transactions.complimentary(order, transition, TraceContext.currentTraceId());
        return create(request, order, transition, COMPLIMENTARY_ORDER_INDEX)
                .onErrorMap(TransactionCanceledException.class,
                        error -> Cancellations.conditionFailedAt(error, COMPLIMENTARY_EVENT_INDEX)
                                ? new InsufficientInventoryException(order.eventId(), order.quantity())
                                : error);
    }

    @Override
    public Mono<ConfirmWriteResult> confirm(Order next, OrderTransition transition, String callerId, Instant now) {
        TransactWriteItemsRequest request =
                transactions.confirm(next, transition, callerId, now, TraceContext.currentTraceId());
        return executor.execute(() -> client.transactWriteItems(request))
                .doOnNext(response -> metrics.transitionApplied(transition))
                .<ConfirmWriteResult>thenReturn(new ConfirmWriteResult.Confirmed(next))
                .onErrorResume(TransactionCanceledException.class, error -> confirmFailure(error, next));
    }

    /** Creation transaction: when the order condition fails, the stored order is returned instead. */
    private Mono<OrderWriteResult> create(TransactWriteItemsRequest request, Order order, OrderTransition transition,
                                          int orderIndex) {
        return executor.execute(() -> client.transactWriteItems(request))
                .doOnNext(response -> metrics.orderCreated(transition))
                .<OrderWriteResult>thenReturn(new OrderWriteResult.Created(order))
                .onErrorResume(TransactionCanceledException.class,
                        error -> Cancellations.conditionFailedAt(error, orderIndex)
                                ? storedOrder(error, orderIndex, order.orderId()).map(OrderWriteResult.AlreadyExists::new)
                                : Mono.error(error));
    }

    private Mono<ConfirmWriteResult> confirmFailure(TransactionCanceledException error, Order next) {
        if (Cancellations.conditionFailedAt(error, CONFIRM_ORDER_INDEX)) {
            return storedOrder(error, CONFIRM_ORDER_INDEX, next.orderId())
                    .<ConfirmWriteResult>map(ConfirmWriteResult.Conflict::new)
                    .defaultIfEmpty(new ConfirmWriteResult.Conflict(null));
        }
        if (Cancellations.conditionFailedAt(error, CONFIRM_EVENT_INDEX)) {
            return Mono.error(new IllegalStateException(
                    "Reserved inventory of event " + next.eventId() + " is lower than order " + next.orderId(), error));
        }
        return Mono.error(error);
    }

    /** {@code ALL_OLD} item of the failed condition, or a fresh read when the backend did not return it. */
    private Mono<Order> storedOrder(TransactionCanceledException error, int index, String orderId) {
        return Cancellations.oldItemAt(error, index)
                .map(item -> Mono.just(OrderItemMapper.toOrder(item)))
                .orElseGet(() -> findById(orderId));
    }
}
