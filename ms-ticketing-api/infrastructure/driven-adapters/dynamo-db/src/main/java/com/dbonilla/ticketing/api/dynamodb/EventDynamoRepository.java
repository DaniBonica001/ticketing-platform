package com.dbonilla.ticketing.api.dynamodb;

import com.dbonilla.ticketing.api.dynamodb.config.DynamoTablesProperties;
import com.dbonilla.ticketing.api.dynamodb.mapper.EventItemMapper;
import com.dbonilla.ticketing.api.dynamodb.support.Attributes;
import com.dbonilla.ticketing.api.dynamodb.support.DynamoExecutor;
import com.dbonilla.ticketing.api.model.event.Event;
import com.dbonilla.ticketing.api.model.event.EventDetails;
import com.dbonilla.ticketing.api.model.event.EventPage;
import com.dbonilla.ticketing.api.model.event.gateways.EventRepository;
import com.dbonilla.ticketing.api.model.exception.EventNotFoundException;
import com.dbonilla.ticketing.api.model.exception.VersionConflictException;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.util.Map;

import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.n;
import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.s;
import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.timestamp;

@Repository
public class EventDynamoRepository implements EventRepository {

    static final String UPDATE_DETAILS = "SET #name = :name, #eventDate = :eventDate, #venue = :venue, "
            + "#updatedAt = :updatedAt, #version = #version + :one";
    static final String VERSION_CONDITION = "attribute_exists(#eventId) AND #version = :expectedVersion";

    private final DynamoDbAsyncClient client;
    private final DynamoExecutor executor;
    private final String table;

    public EventDynamoRepository(DynamoDbAsyncClient client, DynamoExecutor executor,
                                 DynamoTablesProperties tables) {
        this.client = client;
        this.executor = executor;
        this.table = tables.eventsTable();
    }

    @Override
    public Mono<Event> create(Event event) {
        PutItemRequest request = PutItemRequest.builder()
                .tableName(table)
                .item(EventItemMapper.toItem(event))
                .conditionExpression("attribute_not_exists(#eventId)")
                .expressionAttributeNames(Map.of("#eventId", EventItemMapper.EVENT_ID))
                .build();
        return executor.execute(() -> client.putItem(request)).thenReturn(event);
    }

    @Override
    public Mono<Event> findById(String eventId) {
        GetItemRequest request = GetItemRequest.builder()
                .tableName(table)
                .key(EventItemMapper.key(eventId))
                .consistentRead(true)
                .build();
        return executor.execute(() -> client.getItem(request))
                .filter(response -> Attributes.isPresent(response.item()))
                .map(response -> EventItemMapper.toEvent(response.item()));
    }

    @Override
    public Mono<EventPage> findPage(int limit, String cursor) {
        return Mono.fromCallable(() -> scanRequest(limit, cursor))
                .flatMap(request -> executor.execute(() -> client.scan(request)))
                .map(EventDynamoRepository::toPage);
    }

    @Override
    public Mono<Event> updateDetails(String eventId, EventDetails details, long expectedVersion, Instant now) {
        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(table)
                .key(EventItemMapper.key(eventId))
                .updateExpression(UPDATE_DETAILS)
                .conditionExpression(VERSION_CONDITION)
                .expressionAttributeNames(Map.of("#eventId", EventItemMapper.EVENT_ID,
                        "#name", EventItemMapper.NAME, "#eventDate", EventItemMapper.EVENT_DATE,
                        "#venue", EventItemMapper.VENUE, "#updatedAt", EventItemMapper.UPDATED_AT,
                        "#version", EventItemMapper.VERSION))
                .expressionAttributeValues(Map.of(":name", s(details.name()),
                        ":eventDate", s(EventItemMapper.formatEventDate(details.eventDate())),
                        ":venue", s(details.venue()), ":updatedAt", timestamp(now), ":one", n(1),
                        ":expectedVersion", n(expectedVersion)))
                .returnValues(ReturnValue.ALL_NEW)
                .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
                .build();
        return executor.execute(() -> client.updateItem(request))
                .map(response -> EventItemMapper.toEvent(response.attributes()))
                .onErrorResume(ConditionalCheckFailedException.class,
                        error -> versionFailure(error, eventId, expectedVersion));
    }

    private ScanRequest scanRequest(int limit, String cursor) {
        ScanRequest.Builder builder = ScanRequest.builder().tableName(table).limit(limit).consistentRead(true);
        if (cursor != null && !cursor.isBlank()) {
            builder.exclusiveStartKey(EventCursor.decode(cursor));
        }
        return builder.build();
    }

    private static EventPage toPage(ScanResponse response) {
        return new EventPage(response.items().stream().map(EventItemMapper::toEvent).toList(),
                response.hasLastEvaluatedKey() ? EventCursor.encode(response.lastEvaluatedKey()) : null);
    }

    /**
     * The condition failed: the event either does not exist or is at another version. The old item comes
     * with the error when the backend supports {@code ReturnValuesOnConditionCheckFailure}; otherwise it is
     * read again.
     */
    private Mono<Event> versionFailure(ConditionalCheckFailedException error, String eventId, long expectedVersion) {
        Mono<Event> stored = error.hasItem() && Attributes.isPresent(error.item())
                ? Mono.just(EventItemMapper.toEvent(error.item()))
                : findById(eventId);
        return stored
                .flatMap(event -> Mono.<Event>error(new VersionConflictException(eventId, expectedVersion)))
                .switchIfEmpty(Mono.error(() -> new EventNotFoundException(eventId)));
    }
}
