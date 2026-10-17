package com.dbonilla.ticketing.api.dynamodb;

import com.dbonilla.ticketing.api.dynamodb.mapper.TransitionItemMapper;
import com.dbonilla.ticketing.api.model.order.OrderStatus;
import com.dbonilla.ticketing.api.model.ordertransition.OrderTransition;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.dbonilla.ticketing.api.dynamodb.DynamoFixtures.NOW;
import static com.dbonilla.ticketing.api.dynamodb.DynamoFixtures.ORDER_ID;
import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.s;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransitionDynamoRepositoryTest {

    @Mock
    private DynamoDbAsyncClient client;

    @Test
    void followsPaginationInChronologicalOrder() {
        OrderTransition first = new OrderTransition(ORDER_ID, "e", null, OrderStatus.QUEUED, 2, null, "ticketing-api", NOW);
        OrderTransition second = new OrderTransition(ORDER_ID, "e", OrderStatus.QUEUED, OrderStatus.RESERVED, 2, null,
                "order-processor", NOW.plusSeconds(1));
        Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> lastKey =
                Map.of("orderId", s(ORDER_ID), "transitionId", s("x"));
        when(client.query(any(QueryRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(QueryResponse.builder()
                        .items(java.util.List.of(TransitionItemMapper.toItem(first, Optional.empty()))).lastEvaluatedKey(lastKey).build()))
                .thenReturn(CompletableFuture.completedFuture(QueryResponse.builder()
                        .items(java.util.List.of(TransitionItemMapper.toItem(second, Optional.of("t")))).build()));
        TransitionDynamoRepository repository = new TransitionDynamoRepository(client,
                DynamoFixtures.executor(new SimpleMeterRegistry()), DynamoFixtures.TABLES);

        StepVerifier.create(repository.findByOrderId(ORDER_ID)).expectNext(first, second).verifyComplete();

        ArgumentCaptor<QueryRequest> requests = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client, times(2)).query(requests.capture());
        assertThat(requests.getAllValues().get(0).exclusiveStartKey()).isEmpty();
        assertThat(requests.getAllValues().get(0).consistentRead()).isTrue();
        assertThat(requests.getAllValues().get(0).tableName()).isEqualTo("ticketing-order-audit");
        assertThat(requests.getAllValues().get(1).exclusiveStartKey()).isEqualTo(lastKey);
    }
}
