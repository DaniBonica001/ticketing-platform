package com.dbonilla.ticketing.expirer.dynamodb;

import com.dbonilla.ticketing.expirer.model.execution.ExecutionContext;
import com.dbonilla.ticketing.expirer.model.order.DeadlineUpdate;
import com.dbonilla.ticketing.expirer.model.order.ExpirationOutcome;
import com.dbonilla.ticketing.expirer.model.order.InventoryInvariantViolationException;
import com.dbonilla.ticketing.expirer.model.order.Order;
import com.dbonilla.ticketing.expirer.model.order.OrderPage;
import com.dbonilla.ticketing.expirer.model.order.OverdueOrdersQuery;
import com.dbonilla.ticketing.expirer.model.order.gateways.OrderRepository;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.Instant;

/**
 * Orders gateway over the async DynamoDB client. Transient errors are retried with the shared policy;
 * conditional failures are business outcomes and are mapped, never retried.
 */
public class DynamoDbOrderRepository implements OrderRepository {

    private final DynamoDbAsyncClient client;
    private final Retry retry;
    private final OverdueOrdersQueryFactory queryFactory;
    private final ExpireOrderTransactionFactory transactionFactory;
    private final PostponeDeadlineRequestFactory postponeFactory;
    private final OrderItemMapper mapper;

    public DynamoDbOrderRepository(DynamoDbAsyncClient client, DynamoTables tables, Retry retry) {
        this.client = client;
        this.retry = retry;
        this.queryFactory = new OverdueOrdersQueryFactory(tables.orders());
        this.transactionFactory = new ExpireOrderTransactionFactory(tables);
        this.postponeFactory = new PostponeDeadlineRequestFactory(tables.orders());
        this.mapper = new OrderItemMapper();
    }

    @Override
    public Mono<OrderPage> findOverdue(OverdueOrdersQuery query) {
        return Mono.fromFuture(() -> client.query(queryFactory.build(query)))
                .retryWhen(retry)
                .map(mapper::toPage);
    }

    @Override
    public Mono<ExpirationOutcome> expire(Order order, ExecutionContext context) {
        return Mono.fromFuture(() -> client.transactWriteItems(transactionFactory.build(order, context)))
                .retryWhen(retry)
                .thenReturn(ExpirationOutcome.EXPIRED)
                .onErrorResume(TransactionCanceledException.class, cancelled -> classify(cancelled, order));
    }

    @Override
    public Mono<DeadlineUpdate> postponeDeadline(Order order, Instant newDeadline, ExecutionContext context) {
        return Mono.fromFuture(() -> client.updateItem(postponeFactory.build(order, newDeadline, context)))
                .retryWhen(retry)
                .thenReturn(DeadlineUpdate.POSTPONED)
                .onErrorResume(ConditionalCheckFailedException.class,
                        stateChanged -> Mono.just(DeadlineUpdate.STATE_CHANGED));
    }

    private static Mono<ExpirationOutcome> classify(TransactionCanceledException cancelled, Order order) {
        return switch (CancellationCause.of(cancelled)) {
            case ORDER_CONDITION_FAILED -> Mono.just(ExpirationOutcome.ALREADY_TRANSITIONED);
            case EVENT_CONDITION_FAILED ->
                    Mono.error(new InventoryInvariantViolationException(order.orderId(), order.eventId()));
            case OTHER -> Mono.error(cancelled);
        };
    }
}
