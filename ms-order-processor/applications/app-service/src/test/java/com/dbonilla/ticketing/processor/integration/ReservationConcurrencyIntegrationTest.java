package com.dbonilla.ticketing.processor.integration;

import com.dbonilla.ticketing.processor.dynamodb.DynamoOrderRepository;
import com.dbonilla.ticketing.processor.metrics.BusinessMetrics;
import com.dbonilla.ticketing.processor.model.exceptions.TransientFailureException;
import com.dbonilla.ticketing.processor.model.payment.PaymentStatus;
import com.dbonilla.ticketing.processor.model.processing.OrderCommand;
import com.dbonilla.ticketing.processor.model.processing.ProcessingOutcome;
import com.dbonilla.ticketing.processor.model.processing.ReservationSettings;
import com.dbonilla.ticketing.processor.model.processing.RetryPolicy;
import com.dbonilla.ticketing.processor.usecase.processpayment.ProcessPaymentUseCase;
import com.dbonilla.ticketing.processor.usecase.reserveorder.ReserveOrderUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.dbonilla.ticketing.processor.integration.LocalStackDynamoDb.number;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves there is no overselling under concurrency: 100 buyers of 1 ticket race for an event of capacity 10,
 * against a real DynamoDB API (LocalStack) with the exact production transactions.
 */
@Testcontainers
class ReservationConcurrencyIntegrationTest {

    private static final int CAPACITY = 10;
    private static final int BUYERS = 100;
    private static final int SQS_REDELIVERIES = 5;

    @Container
    private static final LocalStackContainer LOCALSTACK = LocalStackDynamoDb.container();

    private static DynamoDbAsyncClient client;
    private static SimpleMeterRegistry meterRegistry;
    private static DynamoOrderRepository repository;

    @BeforeAll
    static void setUp() {
        client = LocalStackDynamoDb.client(LOCALSTACK);
        LocalStackDynamoDb.createTables(client);
        meterRegistry = new SimpleMeterRegistry();
        repository = new DynamoOrderRepository(client, LocalStackDynamoDb.TABLES, new BusinessMetrics(meterRegistry));
    }

    @AfterAll
    static void tearDown() {
        client.close();
    }

    private static ReserveOrderUseCase reserveUseCase() {
        return new ReserveOrderUseCase(repository, new ReservationSettings(Duration.ofMinutes(10)),
                new RetryPolicy(3, Duration.ofMillis(100), Duration.ofSeconds(2), 0.5), Clock.systemUTC());
    }

    private static OrderCommand command(String orderId, String eventId) {
        return new OrderCommand(UUID.randomUUID().toString(), orderId, eventId, "trace-" + orderId);
    }

    /** Emulates SQS at-least-once delivery: a message that failed transiently is delivered again. */
    private static Mono<ProcessingOutcome> deliver(Function<OrderCommand, Mono<ProcessingOutcome>> useCase,
                                                   OrderCommand command) {
        return Mono.defer(() -> useCase.apply(command))
                .retryWhen(Retry.max(SQS_REDELIVERIES).filter(TransientFailureException.class::isInstance));
    }

    @Test
    void hundredConcurrentBuyersNeverOversellTenTickets() {
        // Arrange
        String eventId = UUID.randomUUID().toString();
        LocalStackDynamoDb.putEvent(client, eventId, CAPACITY, CAPACITY, 0);
        List<String> orderIds = IntStream.range(0, BUYERS).mapToObj(i -> UUID.randomUUID().toString()).toList();
        orderIds.forEach(orderId -> LocalStackDynamoDb.putOrder(client, orderId, eventId, 1, "QUEUED", null));
        ReserveOrderUseCase useCase = reserveUseCase();

        // Act: all orders processed at the same time
        Map<ProcessingOutcome, Long> outcomes = Flux.fromIterable(orderIds)
                .flatMap(orderId -> deliver(useCase::execute, command(orderId, eventId)), BUYERS)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .block(Duration.ofMinutes(2));

        // Assert
        assertThat(outcomes).containsEntry(ProcessingOutcome.RESERVED, (long) CAPACITY)
                .containsEntry(ProcessingOutcome.REJECTED_SOLD_OUT, (long) (BUYERS - CAPACITY))
                .hasSize(2);
        Map<String, Long> statuses = orderIds.stream()
                .map(orderId -> LocalStackDynamoDb.order(client, orderId))
                .collect(Collectors.groupingBy(item -> item.get("status").s()
                        + (item.containsKey("reason") ? "/" + item.get("reason").s() : ""), Collectors.counting()));
        assertThat(statuses).containsEntry("RESERVED", (long) CAPACITY)
                .containsEntry("REJECTED/SOLD_OUT", (long) (BUYERS - CAPACITY))
                .hasSize(2);
        Map<String, AttributeValue> event = LocalStackDynamoDb.event(client, eventId);
        assertThat(number(event, "available")).isZero();
        assertThat(number(event, "reserved")).isEqualTo(CAPACITY);
        assertThat(number(event, "available") + number(event, "reserved") + number(event, "pendingConfirmation")
                + number(event, "sold") + number(event, "complimentary")).isEqualTo(number(event, "capacity"));
        assertThat(orderIds.stream().mapToInt(orderId -> LocalStackDynamoDb.auditOf(client, orderId).size()).sum())
                .isEqualTo(BUYERS);
        double conflicts = meterRegistry.find("ticketing.dynamodb.transaction.conflicts").counter().count();
        System.getLogger(getClass().getName()).log(System.Logger.Level.INFO,
                "Concurrency result: {0}, transaction conflicts retried: {1}", outcomes, conflicts);
    }

    @Test
    void processingTheSameMessageTwiceChangesInventoryOnlyOnce() {
        // Arrange
        String eventId = UUID.randomUUID().toString();
        String orderId = UUID.randomUUID().toString();
        LocalStackDynamoDb.putEvent(client, eventId, 5, 5, 0);
        LocalStackDynamoDb.putOrder(client, orderId, eventId, 2, "QUEUED", null);
        OrderCommand command = command(orderId, eventId);
        ReserveOrderUseCase useCase = reserveUseCase();

        // Act: sequential re-delivery and concurrent duplicates of the same message
        ProcessingOutcome first = useCase.execute(command).block();
        ProcessingOutcome second = useCase.execute(command).block();
        List<ProcessingOutcome> concurrent = Flux.range(0, 5)
                .flatMap(i -> useCase.execute(command))
                .collectList().block();

        // Assert
        assertThat(first).isEqualTo(ProcessingOutcome.RESERVED);
        assertThat(second).isEqualTo(ProcessingOutcome.ALREADY_PROCESSED);
        assertThat(concurrent).containsOnly(ProcessingOutcome.ALREADY_PROCESSED);
        Map<String, AttributeValue> event = LocalStackDynamoDb.event(client, eventId);
        assertThat(number(event, "available")).isEqualTo(3);
        assertThat(number(event, "reserved")).isEqualTo(2);
        assertThat(LocalStackDynamoDb.auditOf(client, orderId)).hasSize(1);
    }

    @Test
    void concurrentDuplicatesOfOneMessageReserveOnlyOnce() {
        String eventId = UUID.randomUUID().toString();
        String orderId = UUID.randomUUID().toString();
        LocalStackDynamoDb.putEvent(client, eventId, 5, 5, 0);
        LocalStackDynamoDb.putOrder(client, orderId, eventId, 1, "QUEUED", null);
        OrderCommand command = command(orderId, eventId);
        ReserveOrderUseCase useCase = reserveUseCase();

        List<ProcessingOutcome> outcomes = Flux.range(0, 10)
                .flatMap(i -> deliver(useCase::execute, command), 10)
                .collectList().block(Duration.ofMinutes(1));

        assertThat(outcomes).filteredOn(ProcessingOutcome.RESERVED::equals).hasSize(1);
        assertThat(outcomes).filteredOn(ProcessingOutcome.ALREADY_PROCESSED::equals).hasSize(9);
        assertThat(number(LocalStackDynamoDb.event(client, eventId), "available")).isEqualTo(4);
        assertThat(LocalStackDynamoDb.auditOf(client, orderId)).hasSize(1);
    }

    @Test
    void paymentResultsMoveInventoryOutOfPendingConfirmation() {
        // Arrange
        String eventId = UUID.randomUUID().toString();
        String approved = UUID.randomUUID().toString();
        String declined = UUID.randomUUID().toString();
        LocalStackDynamoDb.putEvent(client, eventId, 10, 6, 4);
        LocalStackDynamoDb.putOrder(client, approved, eventId, 2, "PENDING_CONFIRMATION", "PAY-1");
        LocalStackDynamoDb.putOrder(client, declined, eventId, 2, "PENDING_CONFIRMATION", "DECLINED-1");
        ProcessPaymentUseCase useCase = new ProcessPaymentUseCase(repository,
                reference -> Mono.just(reference.startsWith("DECLINED-") ? PaymentStatus.DECLINED : PaymentStatus.APPROVED),
                new RetryPolicy(3, Duration.ofMillis(100), Duration.ofSeconds(2), 0.5), Clock.systemUTC());

        // Act
        ProcessingOutcome sold = useCase.execute(command(approved, eventId)).block();
        ProcessingOutcome rejected = useCase.execute(command(declined, eventId)).block();
        ProcessingOutcome duplicate = useCase.execute(command(approved, eventId)).block();

        // Assert
        assertThat(sold).isEqualTo(ProcessingOutcome.SOLD);
        assertThat(rejected).isEqualTo(ProcessingOutcome.REJECTED_PAYMENT_DECLINED);
        assertThat(duplicate).isEqualTo(ProcessingOutcome.ALREADY_PROCESSED);
        Map<String, AttributeValue> event = LocalStackDynamoDb.event(client, eventId);
        assertThat(number(event, "pendingConfirmation")).isZero();
        assertThat(number(event, "sold")).isEqualTo(2);
        assertThat(number(event, "available")).isEqualTo(8);
        Map<String, AttributeValue> soldOrder = LocalStackDynamoDb.order(client, approved);
        assertThat(soldOrder.get("status").s()).isEqualTo("SOLD");
        assertThat(soldOrder).doesNotContainKey("deadlineAt");
        Map<String, AttributeValue> rejectedOrder = LocalStackDynamoDb.order(client, declined);
        assertThat(rejectedOrder.get("reason").s()).isEqualTo("PAYMENT_DECLINED");
        assertThat(LocalStackDynamoDb.auditOf(client, declined)).singleElement()
                .satisfies(row -> assertThat(row.get("actor").s()).isEqualTo("order-processor"));
    }
}
