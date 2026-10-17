package com.dbonilla.ticketing.processor.sqs.listener;

import com.dbonilla.ticketing.processor.metrics.BusinessMetrics;
import com.dbonilla.ticketing.processor.model.exceptions.InventoryConditionFailedException;
import com.dbonilla.ticketing.processor.model.exceptions.OrderNotFoundException;
import com.dbonilla.ticketing.processor.model.exceptions.PaymentGatewayUnavailableException;
import com.dbonilla.ticketing.processor.model.exceptions.TransientFailureException;
import com.dbonilla.ticketing.processor.model.processing.OrderCommand;
import com.dbonilla.ticketing.processor.model.processing.ProcessingOutcome;
import com.dbonilla.ticketing.processor.sqs.listener.message.MessageType;
import com.dbonilla.ticketing.processor.sqs.listener.message.OrderMessageValidator;
import com.dbonilla.ticketing.processor.sqs.listener.tracing.MessageTracing;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderMessageProcessorTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicReference<OrderCommand> received = new AtomicReference<>();

    private OrderMessageProcessor processor(MessageType type, Function<OrderCommand, Mono<ProcessingOutcome>> useCase) {
        return processor(type, useCase, Duration.ofSeconds(25));
    }

    private OrderMessageProcessor processor(MessageType type, Function<OrderCommand, Mono<ProcessingOutcome>> useCase,
                                            Duration deadline) {
        return new OrderMessageProcessor(type, command -> {
            received.set(command);
            return useCase.apply(command);
        }, new OrderMessageValidator(JsonMapper.builder().build()),
                new MessageTracing(ObservationRegistry.NOOP), new BusinessMetrics(registry), deadline);
    }

    private double processed(String outcome) {
        return registry.get("ticketing.order.processing.duration").tag("outcome", outcome).timer().count();
    }

    @ParameterizedTest
    @EnumSource(ProcessingOutcome.class)
    void deletesMessageForEveryFinalOutcome(ProcessingOutcome outcome) {
        // Arrange
        OrderMessageProcessor processor = processor(MessageType.ORDER_PLACED, command -> Mono.just(outcome));

        // Act & Assert
        StepVerifier.create(processor.apply(TestMessages.message(TestMessages.body("OrderPlaced"))))
                .expectNext(MessageDisposition.DELETE)
                .verifyComplete();
        assertThat(processed(outcome.name())).isEqualTo(1);
    }

    @Test
    void buildsCommandFromTheMessageAndTheIncomingTrace() {
        OrderMessageProcessor processor = processor(MessageType.ORDER_CONFIRMATION_REQUESTED,
                command -> Mono.just(ProcessingOutcome.SOLD));

        StepVerifier.create(processor.apply(TestMessages.message(TestMessages.body("OrderConfirmationRequested"))))
                .expectNext(MessageDisposition.DELETE)
                .verifyComplete();
        assertThat(received.get()).isEqualTo(new OrderCommand(TestMessages.MESSAGE_ID, TestMessages.ORDER_ID,
                TestMessages.EVENT_ID, TestMessages.TRACE_ID));
    }

    @Test
    void keepsInvalidMessagesSoTheyReachTheDlq() {
        OrderMessageProcessor processor = processor(MessageType.ORDER_PLACED, command -> Mono.just(ProcessingOutcome.RESERVED));

        StepVerifier.create(processor.apply(TestMessages.message("{broken")))
                .expectNext(MessageDisposition.RETAIN)
                .verifyComplete();
        assertThat(received.get()).isNull();
        assertThat(processed("INVALID_MESSAGE")).isEqualTo(1);
    }

    @Test
    void keepsMessagesOfTheWrongType() {
        OrderMessageProcessor processor = processor(MessageType.ORDER_PLACED, command -> Mono.just(ProcessingOutcome.RESERVED));

        StepVerifier.create(processor.apply(TestMessages.message(TestMessages.body("OrderConfirmationRequested"))))
                .expectNext(MessageDisposition.RETAIN)
                .verifyComplete();
    }

    @Test
    void keepsMessagesWhenFailuresPersist() {
        assertRetained(new TransientFailureException("throttled", null), "TRANSIENT_FAILURE");
        assertRetained(new PaymentGatewayUnavailableException("circuit open", null), "PAYMENT_GATEWAY_UNAVAILABLE");
        assertRetained(new OrderNotFoundException("o"), "ORDER_NOT_FOUND");
        assertRetained(new InventoryConditionFailedException("e"), "INVENTORY_INCONSISTENT");
        assertRetained(new IllegalStateException("bug"), "UNEXPECTED_ERROR");
    }

    @Test
    void keepsMessageWhenProcessingExceedsTheDeadline() {
        // Arrange: a use case that never completes
        AtomicBoolean cancelled = new AtomicBoolean();
        OrderMessageProcessor processor = processor(MessageType.ORDER_PLACED,
                command -> Mono.<ProcessingOutcome>never().doOnCancel(() -> cancelled.set(true)),
                Duration.ofMillis(50));

        // Act & Assert
        StepVerifier.create(processor.apply(TestMessages.message(TestMessages.body("OrderPlaced"))))
                .expectNext(MessageDisposition.RETAIN)
                .expectComplete()
                .verify(Duration.ofSeconds(5));
        assertThat(processed("PROCESSING_TIMEOUT")).isEqualTo(1);
        assertThat(cancelled).as("in-flight work is cancelled at the deadline").isTrue();
    }

    @Test
    void rejectsNonPositiveDeadlines() {
        assertThatThrownBy(() -> processor(MessageType.ORDER_PLACED, command -> Mono.empty(), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processor(MessageType.ORDER_PLACED, command -> Mono.empty(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertRetained(Throwable error, String outcome) {
        OrderMessageProcessor processor = processor(MessageType.ORDER_PLACED, command -> Mono.error(error));

        StepVerifier.create(processor.apply(TestMessages.message(TestMessages.body("OrderPlaced"))))
                .expectNext(MessageDisposition.RETAIN)
                .verifyComplete();
        assertThat(processed(outcome)).isEqualTo(1);
    }
}
