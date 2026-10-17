package com.dbonilla.ticketing.processor.dynamodb;

import com.dbonilla.ticketing.processor.awserrors.TransientErrors;
import com.dbonilla.ticketing.processor.dynamodb.config.TableNames;
import com.dbonilla.ticketing.processor.dynamodb.transaction.TransactionErrorMapper;
import com.dbonilla.ticketing.processor.dynamodb.transaction.TransactionPlan;
import com.dbonilla.ticketing.processor.dynamodb.transaction.TransitionTransactionFactory;
import com.dbonilla.ticketing.processor.metrics.BusinessMetrics;
import com.dbonilla.ticketing.processor.model.exceptions.TransientFailureException;
import com.dbonilla.ticketing.processor.model.order.Order;
import com.dbonilla.ticketing.processor.model.order.OrderTransition;
import com.dbonilla.ticketing.processor.model.order.gateways.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

import java.util.Map;

@Repository
public class DynamoOrderRepository implements OrderRepository {

    private static final Logger log = LoggerFactory.getLogger(DynamoOrderRepository.class);

    private final DynamoDbAsyncClient client;
    private final TableNames tables;
    private final TransitionTransactionFactory transactionFactory;
    private final BusinessMetrics metrics;

    public DynamoOrderRepository(DynamoDbAsyncClient client, TableNames tables, BusinessMetrics metrics) {
        this.client = client;
        this.tables = tables;
        this.transactionFactory = new TransitionTransactionFactory(tables);
        this.metrics = metrics;
    }

    @Override
    public Mono<Order> findById(String orderId) {
        GetItemRequest request = GetItemRequest.builder()
                .tableName(tables.orders())
                .key(Map.of(Attributes.ORDER_ID, AttributeValue.fromS(orderId)))
                .consistentRead(true)
                .build();
        return Mono.fromFuture(() -> client.getItem(request))
                .filter(response -> response.hasItem() && !response.item().isEmpty())
                .map(response -> OrderItemMapper.toOrder(response.item()))
                .onErrorMap(TransientErrors::isTransient,
                        error -> new TransientFailureException("Transient DynamoDB failure reading an order", error));
    }

    @Override
    public Mono<Void> apply(OrderTransition transition, String traceId) {
        TransactionPlan plan = transactionFactory.create(transition, traceId);
        return Mono.fromFuture(() -> client.transactWriteItems(plan.toRequest()))
                .doOnSuccess(response -> recordTransition(transition))
                .doOnError(TransactionErrorMapper::hasTransactionConflict, conflict -> metrics.transactionConflict())
                .onErrorMap(error -> TransactionErrorMapper.map(error, plan, transition))
                .then();
    }

    private void recordTransition(OrderTransition transition) {
        metrics.transitionApplied(transition.from().name(), transition.to().name());
        transition.rejectionReason().ifPresent(reason -> metrics.orderRejected(reason.name()));
        log.info("Order {} moved from {} to {}", transition.orderId(), transition.from(), transition.to());
    }
}
