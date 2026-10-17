package com.dbonilla.ticketing.api.integration;

import com.dbonilla.ticketing.api.dynamodb.EventDynamoRepository;
import com.dbonilla.ticketing.api.dynamodb.OrderDynamoRepository;
import com.dbonilla.ticketing.api.dynamodb.mapper.EventItemMapper;
import com.dbonilla.ticketing.api.dynamodb.mapper.OrderItemMapper;
import com.dbonilla.ticketing.api.dynamodb.mapper.TransitionItemMapper;
import com.dbonilla.ticketing.api.dynamodb.support.Attributes;
import com.dbonilla.ticketing.api.dynamodb.support.DynamoExecutor;
import com.dbonilla.ticketing.api.dynamodb.support.TransactionMetrics;
import com.dbonilla.ticketing.api.model.event.Event;
import com.dbonilla.ticketing.api.model.event.EventDetails;
import com.dbonilla.ticketing.api.model.event.Inventory;
import com.dbonilla.ticketing.api.model.exception.InsufficientInventoryException;
import com.dbonilla.ticketing.api.model.exception.InvalidOrderStateException;
import com.dbonilla.ticketing.api.model.exception.ReservationExpiredException;
import com.dbonilla.ticketing.api.model.exception.ServiceUnavailableException;
import com.dbonilla.ticketing.api.model.order.Caller;
import com.dbonilla.ticketing.api.model.order.Order;
import com.dbonilla.ticketing.api.model.order.OrderPolicy;
import com.dbonilla.ticketing.api.model.order.OrderStatus;
import com.dbonilla.ticketing.api.model.order.gateways.OrderMessagePublisher;
import com.dbonilla.ticketing.api.model.ordertransition.OrderTransition;
import com.dbonilla.ticketing.api.resilience.RetryProperties;
import com.dbonilla.ticketing.api.resilience.TransientErrors;
import com.dbonilla.ticketing.api.resilience.TransientRetry;
import com.dbonilla.ticketing.api.usecase.confirmorder.ConfirmOrderCommand;
import com.dbonilla.ticketing.api.usecase.confirmorder.ConfirmOrderUseCase;
import com.dbonilla.ticketing.api.usecase.issuecomplimentary.IssueComplimentaryCommand;
import com.dbonilla.ticketing.api.usecase.issuecomplimentary.IssueComplimentaryUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.dbonilla.ticketing.api.integration.LocalStackDynamoDb.number;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Races the API's real transactions (T4, T8, through the use cases and the DynamoDB repository) against the
 * other services' transactions (T2 of order-processor, T7 of reservation-expirer, built exactly as in
 * docs/design/dynamodb.md) on a real DynamoDB API (LocalStack).
 */
@Testcontainers
class InventoryRaceIntegrationTest {

    private static final int CAPACITY = 10;
    private static final int COMPLIMENTARY_ATTEMPTS = 40;
    private static final int RESERVATION_ATTEMPTS = 40;
    private static final int CONFIRM_EXPIRE_ITERATIONS = 40;
    private static final int CONCURRENT_RACES = 8;
    /** A client that gets 503 retries with the same Idempotency-Key. */
    private static final int CLIENT_RETRIES = 10;
    /** Seconds left on the reservation when the race starts: the user is still on time. */
    private static final long DEADLINE_MARGIN_SECONDS = 3;
    /**
     * Random start delay of every competitor. The API use cases read before writing, so without it the raw
     * transactions of the other services would almost always win and the race would be one-sided.
     */
    private static final int MAX_START_JITTER_MILLIS = 60;
    private static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";
    private static final String STATUS = "#status";
    /** The processor and the expirer retry transient failures (SQS redelivery / next run). */
    private static final Retry OTHER_SERVICE_RETRY = Retry.backoff(10, Duration.ofMillis(50))
            .maxBackoff(Duration.ofSeconds(1)).jitter(0.5).filter(TransientErrors::isTransient);
    private static final OrderMessagePublisher NO_OP_PUBLISHER = new OrderMessagePublisher() {
        @Override
        public Mono<Void> publishOrderPlaced(Order order) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> publishConfirmationRequested(Order order) {
            return Mono.empty();
        }
    };

    @Container
    private static final LocalStackContainer LOCALSTACK = LocalStackDynamoDb.container();

    private static DynamoDbAsyncClient client;
    private static SimpleMeterRegistry meterRegistry;
    private static IssueComplimentaryUseCase complimentary;
    private static ConfirmOrderUseCase confirm;

    private enum Outcome { COMPLIMENTARY_GRANTED, COMPLIMENTARY_SOLD_OUT, RESERVED, RESERVATION_SOLD_OUT }

    private record RaceResult(Order order, boolean isConfirmed, boolean isExpired) {
    }

    @BeforeAll
    static void setUp() {
        client = LocalStackDynamoDb.client(LOCALSTACK);
        LocalStackDynamoDb.createTables(client);
        meterRegistry = new SimpleMeterRegistry();
        TransactionMetrics metrics = new TransactionMetrics(meterRegistry);
        DynamoExecutor executor = new DynamoExecutor(new TransientRetry(RetryProperties.defaults()), metrics);
        OrderDynamoRepository orders = new OrderDynamoRepository(client, executor, metrics, LocalStackDynamoDb.TABLES);
        EventDynamoRepository events = new EventDynamoRepository(client, executor, LocalStackDynamoDb.TABLES);
        complimentary = new IssueComplimentaryUseCase(events, orders, Clock.systemUTC());
        confirm = new ConfirmOrderUseCase(orders, NO_OP_PUBLISHER, Clock.systemUTC(),
                new OrderPolicy(Duration.ofMinutes(5)));
        warmUp();
    }

    /** One uncontended run of each API path, so that class loading and connection set-up do not skew the races. */
    private static void warmUp() {
        String eventId = uuid();
        LocalStackDynamoDb.putEvent(client, event(eventId, new Inventory(2, 1, 1, 0, 0, 0)));
        complimentary.issue(new IssueComplimentaryCommand(new Caller("admin-1", true), uuid(), eventId, 1, "warm-up"))
                .block(Duration.ofSeconds(30));
        Order reserved = order(eventId, "warm-up", OrderStatus.RESERVED, 60);
        LocalStackDynamoDb.putOrder(client, reserved);
        confirmAsOwner(reserved, "PAY-warm-up").block(Duration.ofSeconds(30));
    }

    @AfterAll
    static void tearDown() {
        client.close();
    }

    // ---- T8 (complimentary, API) vs T2 (reservation, order-processor) ------------------------------------------

    @Test
    void complimentaryTicketsAndReservationsNeverOversellTheEvent() {
        // Arrange
        String eventId = uuid();
        LocalStackDynamoDb.putEvent(client, event(eventId, new Inventory(CAPACITY, CAPACITY, 0, 0, 0, 0)));
        List<Order> queued = IntStream.range(0, RESERVATION_ATTEMPTS)
                .mapToObj(i -> order(eventId, "buyer-" + i, OrderStatus.QUEUED, 300)).toList();
        queued.forEach(order -> LocalStackDynamoDb.putOrder(client, order));
        List<IssueComplimentaryCommand> issuances = IntStream.range(0, COMPLIMENTARY_ATTEMPTS)
                .mapToObj(i -> new IssueComplimentaryCommand(new Caller("admin-1", true), uuid(), eventId, 1, "vip-" + i))
                .toList();
        List<Mono<Outcome>> attempts = new ArrayList<>();
        issuances.forEach(command -> attempts.add(issue(command)));
        queued.forEach(order -> attempts.add(reserve(order)));
        Collections.shuffle(attempts);

        // Act: every T8 and T2 at the same time
        Map<Outcome, Long> outcomes = Flux.fromIterable(attempts)
                .flatMap(InventoryRaceIntegrationTest::afterJitter, attempts.size())
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .block(Duration.ofMinutes(3));

        // Assert
        long granted = outcomes.getOrDefault(Outcome.COMPLIMENTARY_GRANTED, 0L);
        long reserved = outcomes.getOrDefault(Outcome.RESERVED, 0L);
        assertThat(granted + outcomes.getOrDefault(Outcome.COMPLIMENTARY_SOLD_OUT, 0L)).isEqualTo(COMPLIMENTARY_ATTEMPTS);
        assertThat(reserved + outcomes.getOrDefault(Outcome.RESERVATION_SOLD_OUT, 0L)).isEqualTo(RESERVATION_ATTEMPTS);
        assertThat(granted + reserved).isEqualTo(CAPACITY);
        Map<String, AttributeValue> event = LocalStackDynamoDb.event(client, eventId);
        assertInventoryIsConsistent(event);
        assertThat(number(event, EventItemMapper.AVAILABLE)).isZero();
        assertThat(number(event, EventItemMapper.COMPLIMENTARY)).isEqualTo(granted);
        assertThat(number(event, EventItemMapper.RESERVED)).isEqualTo(reserved);
        assertThat(queued.stream().filter(order -> OrderStatus.RESERVED.name()
                .equals(LocalStackDynamoDb.order(client, order.orderId()).get(OrderItemMapper.STATUS).s()))).hasSize((int) reserved);
        assertThat(queued.stream().mapToInt(order -> LocalStackDynamoDb.auditOf(client, order.orderId()).size()).sum())
                .isEqualTo(reserved);
        log("T8 vs T2 on capacity " + CAPACITY + ": " + outcomes + ", DynamoDB transaction conflicts retried: "
                + conflicts());
    }

    private static Mono<Outcome> issue(IssueComplimentaryCommand command) {
        return asClient(() -> complimentary.issue(command))
                .map(order -> Outcome.COMPLIMENTARY_GRANTED)
                .onErrorResume(InsufficientInventoryException.class, soldOut -> Mono.just(Outcome.COMPLIMENTARY_SOLD_OUT));
    }

    private static Mono<Outcome> reserve(Order order) {
        return otherService(order.orderId(), reservation(order, Instant.now().truncatedTo(ChronoUnit.MILLIS)))
                .thenReturn(Outcome.RESERVED)
                .onErrorResume(TransactionCanceledException.class, canceled -> conditionFailedAt(canceled, 0)
                        ? Mono.just(Outcome.RESERVATION_SOLD_OUT) : Mono.error(canceled));
    }

    /** T2 of dynamodb.md: events available to reserved [available >= :q] + order QUEUED to RESERVED + audit. */
    private static TransactWriteItemsRequest reservation(Order order, Instant now) {
        Update event = Update.builder()
                .tableName(LocalStackDynamoDb.TABLES.eventsTable())
                .key(EventItemMapper.key(order.eventId()))
                .updateExpression("SET available = available - :q, reserved = reserved + :q, updatedAt = :ts")
                .conditionExpression("available >= :q")
                .expressionAttributeValues(Map.of(":q", Attributes.n(order.quantity()), ":ts", Attributes.timestamp(now)))
                .build();
        Update reservedOrder = Update.builder()
                .tableName(LocalStackDynamoDb.TABLES.ordersTable())
                .key(OrderItemMapper.key(order.orderId()))
                .updateExpression("SET #status = :reserved, deadlineAt = :deadline, updatedAt = :ts")
                .conditionExpression("#status = :queued")
                .expressionAttributeNames(Map.of(STATUS, OrderItemMapper.STATUS))
                .expressionAttributeValues(Map.of(
                        ":reserved", Attributes.s(OrderStatus.RESERVED.name()),
                        ":queued", Attributes.s(OrderStatus.QUEUED.name()),
                        ":deadline", Attributes.n(now.getEpochSecond() + 600),
                        ":ts", Attributes.timestamp(now)))
                .build();
        return transaction(event, reservedOrder,
                audit(order, OrderStatus.QUEUED, OrderStatus.RESERVED, "order-processor", now));
    }

    // ---- T4 (confirm, API) vs T7 (expire, reservation-expirer) --------------------------------------------------

    @Test
    void confirmationAndExpirationOfTheSameOrderHaveExactlyOneWinner() {
        // Act: each iteration races T4 and T7 on a fresh RESERVED order whose deadline is about to pass
        List<RaceResult> results = Flux.range(0, CONFIRM_EXPIRE_ITERATIONS)
                .flatMap(InventoryRaceIntegrationTest::raceOnce, CONCURRENT_RACES)
                .collectList()
                .block(Duration.ofMinutes(3));

        // Assert
        assertThat(results).hasSize(CONFIRM_EXPIRE_ITERATIONS);
        results.forEach(InventoryRaceIntegrationTest::assertSingleWinner);
        long confirmed = results.stream().filter(RaceResult::isConfirmed).count();
        log("T4 vs T7 over " + CONFIRM_EXPIRE_ITERATIONS + " orders: confirmed=" + confirmed + ", expired="
                + (CONFIRM_EXPIRE_ITERATIONS - confirmed) + ", DynamoDB transaction conflicts retried: " + conflicts());
    }

    private static Mono<RaceResult> raceOnce(int iteration) {
        return Mono.fromCallable(() -> seedReservation(iteration))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(order -> Mono.zip(
                        afterJitter(confirmAsOwner(order, "PAY-" + iteration)),
                        afterJitter(expire(order)),
                        (isConfirmed, isExpired) -> new RaceResult(order, isConfirmed, isExpired)));
    }

    /** Event of capacity 1 whose only ticket is held by a RESERVED order with a deadline a few seconds away. */
    private static Order seedReservation(int iteration) {
        String eventId = uuid();
        LocalStackDynamoDb.putEvent(client, event(eventId, new Inventory(1, 0, 1, 0, 0, 0)));
        Order order = order(eventId, "buyer-" + iteration, OrderStatus.RESERVED, DEADLINE_MARGIN_SECONDS);
        LocalStackDynamoDb.putOrder(client, order);
        return order;
    }

    private static Mono<Boolean> confirmAsOwner(Order order, String paymentReference) {
        ConfirmOrderCommand command = new ConfirmOrderCommand(new Caller(order.userId(), false), order.orderId(),
                paymentReference);
        return asClient(() -> confirm.confirm(command))
                .map(confirmed -> confirmed.status() == OrderStatus.PENDING_CONFIRMATION)
                .onErrorResume(error -> error instanceof InvalidOrderStateException
                        || error instanceof ReservationExpiredException, lost -> Mono.just(false));
    }

    /** The expirer's clock is already past the deadline: both conditions can hold, only the status decides. */
    private static Mono<Boolean> expire(Order order) {
        Instant expirerNow = order.deadlineAt().plusSeconds(1);
        return otherService(order.orderId(), expiration(order, expirerNow))
                .thenReturn(true)
                .onErrorResume(TransactionCanceledException.class, canceled -> conditionFailedAt(canceled, 0)
                        ? Mono.just(false) : Mono.error(canceled));
    }

    /** T7 of dynamodb.md: order RESERVED to EXPIRED [deadline passed] + events reserved to available + audit. */
    private static TransactWriteItemsRequest expiration(Order order, Instant now) {
        Update expiredOrder = Update.builder()
                .tableName(LocalStackDynamoDb.TABLES.ordersTable())
                .key(OrderItemMapper.key(order.orderId()))
                .updateExpression("SET #status = :expired, updatedAt = :ts REMOVE deadlineAt")
                .conditionExpression("#status = :reserved AND deadlineAt < :now")
                .expressionAttributeNames(Map.of(STATUS, OrderItemMapper.STATUS))
                .expressionAttributeValues(Map.of(
                        ":expired", Attributes.s(OrderStatus.EXPIRED.name()),
                        ":reserved", Attributes.s(OrderStatus.RESERVED.name()),
                        ":now", Attributes.n(now.getEpochSecond()),
                        ":ts", Attributes.timestamp(now)))
                .build();
        Update event = Update.builder()
                .tableName(LocalStackDynamoDb.TABLES.eventsTable())
                .key(EventItemMapper.key(order.eventId()))
                .updateExpression("SET reserved = reserved - :q, available = available + :q, updatedAt = :ts")
                .conditionExpression("reserved >= :q")
                .expressionAttributeValues(Map.of(":q", Attributes.n(order.quantity()), ":ts", Attributes.timestamp(now)))
                .build();
        return transaction(expiredOrder, event,
                audit(order, OrderStatus.RESERVED, OrderStatus.EXPIRED, "reservation-expirer", now));
    }

    private static void assertSingleWinner(RaceResult result) {
        Order order = result.order();
        assertThat(result.isConfirmed()).as("exactly one of T4/T7 wins order %s", order.orderId())
                .isNotEqualTo(result.isExpired());
        OrderStatus winner = result.isConfirmed() ? OrderStatus.PENDING_CONFIRMATION : OrderStatus.EXPIRED;
        assertThat(LocalStackDynamoDb.order(client, order.orderId()).get(OrderItemMapper.STATUS).s())
                .isEqualTo(winner.name());
        Map<String, AttributeValue> event = LocalStackDynamoDb.event(client, order.eventId());
        assertInventoryIsConsistent(event);
        assertThat(number(event, EventItemMapper.RESERVED)).isZero();
        assertThat(number(event, EventItemMapper.PENDING_CONFIRMATION)).isEqualTo(result.isConfirmed() ? 1 : 0);
        assertThat(number(event, EventItemMapper.AVAILABLE)).isEqualTo(result.isExpired() ? 1 : 0);
        assertThat(LocalStackDynamoDb.auditOf(client, order.orderId())).singleElement().satisfies(row -> {
            assertThat(row.get(TransitionItemMapper.FROM_STATUS).s()).isEqualTo(OrderStatus.RESERVED.name());
            assertThat(row.get(TransitionItemMapper.TO_STATUS).s()).isEqualTo(winner.name());
        });
    }

    // ---- Helpers ------------------------------------------------------------------------------------------------

    private static void assertInventoryIsConsistent(Map<String, AttributeValue> event) {
        List<String> counters = List.of(EventItemMapper.AVAILABLE, EventItemMapper.RESERVED,
                EventItemMapper.PENDING_CONFIRMATION, EventItemMapper.SOLD, EventItemMapper.COMPLIMENTARY);
        counters.forEach(counter -> assertThat(number(event, counter)).as(counter).isNotNegative());
        assertThat(counters.stream().mapToInt(counter -> number(event, counter)).sum())
                .isEqualTo(number(event, EventItemMapper.CAPACITY));
    }

    private static <T> Mono<T> afterJitter(Mono<T> competitor) {
        Duration delay = Duration.ofMillis(ThreadLocalRandom.current().nextInt(MAX_START_JITTER_MILLIS));
        return Mono.delay(delay, Schedulers.parallel()).then(competitor);
    }

    private static <T> Mono<T> asClient(Supplier<Mono<T>> call) {
        return Mono.defer(call).retryWhen(Retry.max(CLIENT_RETRIES).filter(ServiceUnavailableException.class::isInstance));
    }

    /** Like the processor and the expirer, the other services read the order before writing. */
    private static Mono<Void> otherService(String orderId, TransactWriteItemsRequest request) {
        GetItemRequest read = GetItemRequest.builder().tableName(LocalStackDynamoDb.TABLES.ordersTable())
                .key(OrderItemMapper.key(orderId)).consistentRead(true).build();
        return Mono.defer(() -> Mono.fromFuture(client.getItem(read)))
                .then(Mono.defer(() -> Mono.fromFuture(client.transactWriteItems(request))))
                .retryWhen(OTHER_SERVICE_RETRY)
                .then();
    }

    private static boolean conditionFailedAt(TransactionCanceledException canceled, int index) {
        return canceled.hasCancellationReasons()
                && CONDITIONAL_CHECK_FAILED.equals(canceled.cancellationReasons().get(index).code());
    }

    private static TransactWriteItem audit(Order order, OrderStatus from, OrderStatus to, String actor, Instant now) {
        OrderTransition transition = new OrderTransition(order.orderId(), order.eventId(), from, to, order.quantity(),
                null, actor, now);
        return TransactWriteItem.builder().put(Put.builder()
                .tableName(LocalStackDynamoDb.TABLES.auditTable())
                .item(TransitionItemMapper.toItem(transition, Optional.empty()))
                .build()).build();
    }

    private static TransactWriteItemsRequest transaction(Update first, Update second, TransactWriteItem audit) {
        return TransactWriteItemsRequest.builder().transactItems(
                TransactWriteItem.builder().update(first).build(),
                TransactWriteItem.builder().update(second).build(),
                audit).build();
    }

    private static Event event(String eventId, Inventory inventory) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return new Event(eventId, new EventDetails("Race test", OffsetDateTime.parse("2026-12-20T20:00:00-05:00"),
                "Stadium"), inventory, 1, now, now);
    }

    private static Order order(String eventId, String userId, OrderStatus status, long secondsToDeadline) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return new Order(uuid(), eventId, userId, 1, status, null,
                Instant.ofEpochSecond(now.getEpochSecond() + secondsToDeadline), "hash", null, now, now);
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private static double conflicts() {
        var counter = meterRegistry.find("ticketing.dynamodb.transaction.conflicts").counter();
        return counter == null ? 0 : counter.count();
    }

    private static void log(String message) {
        System.getLogger(InventoryRaceIntegrationTest.class.getName()).log(System.Logger.Level.INFO, message);
    }
}
