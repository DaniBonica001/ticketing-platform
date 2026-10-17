package com.dbonilla.ticketing.api.dynamodb;

import com.dbonilla.ticketing.api.dynamodb.config.DynamoTablesProperties;
import com.dbonilla.ticketing.api.dynamodb.mapper.TransitionItemMapper;
import com.dbonilla.ticketing.api.dynamodb.support.DynamoExecutor;
import com.dbonilla.ticketing.api.model.ordertransition.OrderTransition;
import com.dbonilla.ticketing.api.model.ordertransition.gateways.OrderTransitionRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.util.Map;

import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.s;

@Repository
public class TransitionDynamoRepository implements OrderTransitionRepository {

    private final DynamoDbAsyncClient client;
    private final DynamoExecutor executor;
    private final String table;

    public TransitionDynamoRepository(DynamoDbAsyncClient client, DynamoExecutor executor,
                                      DynamoTablesProperties tables) {
        this.client = client;
        this.executor = executor;
        this.table = tables.auditTable();
    }

    @Override
    public Flux<OrderTransition> findByOrderId(String orderId) {
        return queryPage(orderId, Map.of())
                .expand(response -> response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty()
                        ? queryPage(orderId, response.lastEvaluatedKey())
                        : Mono.empty())
                .flatMapIterable(QueryResponse::items)
                .map(TransitionItemMapper::toTransition);
    }

    private Mono<QueryResponse> queryPage(String orderId, Map<String, AttributeValue> startKey) {
        QueryRequest.Builder builder = QueryRequest.builder()
                .tableName(table)
                .keyConditionExpression("orderId = :orderId")
                .expressionAttributeValues(Map.of(":orderId", s(orderId)))
                .scanIndexForward(true)
                .consistentRead(true);
        if (!startKey.isEmpty()) {
            builder.exclusiveStartKey(startKey);
        }
        QueryRequest request = builder.build();
        return executor.execute(() -> client.query(request));
    }
}
