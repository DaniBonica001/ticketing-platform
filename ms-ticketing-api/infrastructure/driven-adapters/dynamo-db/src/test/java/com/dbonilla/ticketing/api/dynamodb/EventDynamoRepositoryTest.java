package com.dbonilla.ticketing.api.dynamodb;

import com.dbonilla.ticketing.api.dynamodb.mapper.EventItemMapper;
import com.dbonilla.ticketing.api.model.event.Event;
import com.dbonilla.ticketing.api.model.exception.DomainValidationException;
import com.dbonilla.ticketing.api.model.exception.EventNotFoundException;
import com.dbonilla.ticketing.api.model.exception.ServiceUnavailableException;
import com.dbonilla.ticketing.api.model.exception.VersionConflictException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.dbonilla.ticketing.api.dynamodb.DynamoFixtures.EVENT_ID;
import static com.dbonilla.ticketing.api.dynamodb.DynamoFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventDynamoRepositoryTest {

    @Mock
    private DynamoDbAsyncClient client;

    private EventDynamoRepository repository;

    @BeforeEach
    void setUp() {
        repository = new EventDynamoRepository(client, DynamoFixtures.executor(new SimpleMeterRegistry()),
                DynamoFixtures.TABLES);
    }

    @Test
    void createsEventOnlyIfAbsent() {
        Event event = DynamoFixtures.event();
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(PutItemResponse.builder().build()));

        StepVerifier.create(repository.create(event)).expectNext(event).verifyComplete();

        ArgumentCaptor<PutItemRequest> request = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(client).putItem(request.capture());
        assertThat(request.getValue().tableName()).isEqualTo("ticketing-events");
        assertThat(request.getValue().conditionExpression()).isEqualTo("attribute_not_exists(#eventId)");
        assertThat(request.getValue().item()).isEqualTo(EventItemMapper.toItem(event));
    }

    @Test
    void readsWithStrongConsistency() {
        Event event = DynamoFixtures.event();
        when(client.getItem(any(GetItemRequest.class))).thenReturn(CompletableFuture.completedFuture(
                GetItemResponse.builder().item(EventItemMapper.toItem(event)).build()));

        StepVerifier.create(repository.findById(EVENT_ID)).expectNext(event).verifyComplete();

        ArgumentCaptor<GetItemRequest> request = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(client).getItem(request.capture());
        assertThat(request.getValue().consistentRead()).isTrue();
    }

    @Test
    void missingEventIsEmpty() {
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetItemResponse.builder().build()));

        StepVerifier.create(repository.findById(EVENT_ID)).verifyComplete();
    }

    @Test
    void retriesTransientErrorsThenReportsUnavailable() {
        AwsServiceException throttled = AwsServiceException.builder().statusCode(500).message("boom").build();
        when(client.getItem(any(GetItemRequest.class))).thenReturn(CompletableFuture.failedFuture(throttled));

        StepVerifier.create(repository.findById(EVENT_ID)).verifyError(ServiceUnavailableException.class);
        verify(client, times(4)).getItem(any(GetItemRequest.class));
    }

    @Test
    void scansPageAndEncodesCursor() {
        Event event = DynamoFixtures.event();
        when(client.scan(any(ScanRequest.class))).thenReturn(CompletableFuture.completedFuture(ScanResponse.builder()
                .items(java.util.List.of(EventItemMapper.toItem(event)))
                .lastEvaluatedKey(EventItemMapper.key(EVENT_ID))
                .build()));

        StepVerifier.create(repository.findPage(1, null))
                .assertNext(page -> {
                    assertThat(page.items()).containsExactly(event);
                    assertThat(page.nextCursor()).isEqualTo(EventCursor.encode(EventItemMapper.key(EVENT_ID)));
                })
                .verifyComplete();
    }

    @Test
    void continuesFromCursorAndEndsWithoutNextCursor() {
        String cursor = EventCursor.encode(EventItemMapper.key(EVENT_ID));
        when(client.scan(any(ScanRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(ScanResponse.builder().build()));

        StepVerifier.create(repository.findPage(10, cursor))
                .assertNext(page -> assertThat(page.nextCursor()).isNull())
                .verifyComplete();

        ArgumentCaptor<ScanRequest> request = ArgumentCaptor.forClass(ScanRequest.class);
        verify(client).scan(request.capture());
        assertThat(request.getValue().exclusiveStartKey()).isEqualTo(EventItemMapper.key(EVENT_ID));
        assertThat(request.getValue().limit()).isEqualTo(10);
    }

    @Test
    void invalidCursorIsAValidationError() {
        StepVerifier.create(repository.findPage(10, "not-a-cursor!")).verifyError(DomainValidationException.class);
        assertThat(EventCursor.encode(null)).isNull();
        assertThat(EventCursor.encode(Map.of())).isNull();
    }

    @Test
    void updatesDetailsWithOptimisticLock() {
        Event event = DynamoFixtures.event();
        when(client.updateItem(any(UpdateItemRequest.class))).thenReturn(CompletableFuture.completedFuture(
                UpdateItemResponse.builder().attributes(EventItemMapper.toItem(event)).build()));

        StepVerifier.create(repository.updateDetails(EVENT_ID, event.details(), 1, NOW)).expectNext(event).verifyComplete();

        ArgumentCaptor<UpdateItemRequest> request = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(client).updateItem(request.capture());
        assertThat(request.getValue().conditionExpression()).isEqualTo(EventDynamoRepository.VERSION_CONDITION);
        assertThat(request.getValue().updateExpression()).isEqualTo(EventDynamoRepository.UPDATE_DETAILS);
        assertThat(request.getValue().expressionAttributeValues().get(":expectedVersion").n()).isEqualTo("1");
        assertThat(request.getValue().returnValues()).isEqualTo(ReturnValue.ALL_NEW);
        assertThat(request.getValue().returnValuesOnConditionCheckFailure())
                .isEqualTo(ReturnValuesOnConditionCheckFailure.ALL_OLD);
    }

    @Test
    void staleVersionIsAConflict() {
        Event event = DynamoFixtures.event();
        when(client.updateItem(any(UpdateItemRequest.class))).thenReturn(CompletableFuture.failedFuture(
                ConditionalCheckFailedException.builder().item(EventItemMapper.toItem(event)).statusCode(400).build()));

        StepVerifier.create(repository.updateDetails(EVENT_ID, event.details(), 1, NOW))
                .verifyError(VersionConflictException.class);
        verify(client, times(1)).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void conflictWithoutOldItemIsResolvedByReading() {
        Event event = DynamoFixtures.event();
        when(client.updateItem(any(UpdateItemRequest.class))).thenReturn(CompletableFuture.failedFuture(
                ConditionalCheckFailedException.builder().statusCode(400).build()));
        when(client.getItem(any(GetItemRequest.class))).thenReturn(CompletableFuture.completedFuture(
                GetItemResponse.builder().item(EventItemMapper.toItem(event)).build()));

        StepVerifier.create(repository.updateDetails(EVENT_ID, event.details(), 1, NOW))
                .verifyError(VersionConflictException.class);
    }

    @Test
    void updatingMissingEventIsNotFound() {
        Event event = DynamoFixtures.event();
        when(client.updateItem(any(UpdateItemRequest.class))).thenReturn(CompletableFuture.failedFuture(
                ConditionalCheckFailedException.builder().statusCode(400).build()));
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetItemResponse.builder().build()));

        StepVerifier.create(repository.updateDetails(EVENT_ID, event.details(), 1, NOW))
                .verifyError(EventNotFoundException.class);
    }
}
