package com.dbonilla.ticketing.processor.sqs.listener;

import com.dbonilla.ticketing.processor.metrics.BusinessMetrics;
import com.dbonilla.ticketing.processor.model.exceptions.InventoryConditionFailedException;
import com.dbonilla.ticketing.processor.model.exceptions.OrderNotFoundException;
import com.dbonilla.ticketing.processor.model.exceptions.PaymentGatewayUnavailableException;
import com.dbonilla.ticketing.processor.model.exceptions.TransientFailureException;
import com.dbonilla.ticketing.processor.model.processing.OrderCommand;
import com.dbonilla.ticketing.processor.model.processing.ProcessingOutcome;
import com.dbonilla.ticketing.processor.sqs.listener.message.InvalidMessageException;
import com.dbonilla.ticketing.processor.sqs.listener.message.MessageType;
import com.dbonilla.ticketing.processor.sqs.listener.message.OrderMessage;
import com.dbonilla.ticketing.processor.sqs.listener.message.OrderMessageValidator;
import com.dbonilla.ticketing.processor.sqs.listener.tracing.MessageTracing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Validates one message, runs the use case of its queue and decides whether the message is deleted.
 * Delete ONLY on success, duplicate or business rejection; every failure keeps the message (redelivery, then DLQ).
 * The use case runs under a per-message deadline shorter than the queue's visibility timeout, so a slow message is
 * abandoned (and kept) before SQS makes it visible again while it is still in flight.
 */
public class OrderMessageProcessor implements Function<Message, Mono<MessageDisposition>> {

    private static final Logger log = LoggerFactory.getLogger(OrderMessageProcessor.class);

    private final MessageType expectedType;
    private final Function<OrderCommand, Mono<ProcessingOutcome>> useCase;
    private final OrderMessageValidator validator;
    private final MessageTracing tracing;
    private final BusinessMetrics metrics;
    private final Duration deadline;

    public OrderMessageProcessor(MessageType expectedType, Function<OrderCommand, Mono<ProcessingOutcome>> useCase,
                                 OrderMessageValidator validator, MessageTracing tracing, BusinessMetrics metrics,
                                 Duration deadline) {
        if (deadline == null || deadline.isNegative() || deadline.isZero()) {
            throw new IllegalArgumentException("deadline must be positive");
        }
        this.deadline = deadline;
        this.expectedType = expectedType;
        this.useCase = useCase;
        this.validator = validator;
        this.tracing = tracing;
        this.metrics = metrics;
    }

    @Override
    public Mono<MessageDisposition> apply(Message message) {
        long startedAt = System.nanoTime();
        return tracing.observe(message, expectedType.wireName(), traceId -> process(message, traceId))
                .map(result -> {
                    metrics.processingCompleted(expectedType.wireName(), result.outcome(),
                            Duration.ofNanos(System.nanoTime() - startedAt));
                    return result.disposition();
                });
    }

    private Mono<Result> process(Message message, String traceId) {
        return Mono.fromCallable(() -> validator.validate(message.body(), expectedType))
                .flatMap(orderMessage -> execute(orderMessage, traceId, message))
                .onErrorResume(error -> Mono.just(failure(message, error)));
    }

    private Mono<Result> execute(OrderMessage orderMessage, String traceId, Message message) {
        OrderCommand command = new OrderCommand(orderMessage.messageId(), orderMessage.orderId(),
                orderMessage.eventId(), traceId);
        return Mono.defer(() -> useCase.apply(command))
                .timeout(deadline)
                .map(outcome -> {
                    log.info("{} message {} for order {} processed: {} (receiveCount={})", expectedType.wireName(),
                            message.messageId(), command.orderId(), outcome, receiveCount(message));
                    return new Result(MessageDisposition.DELETE, outcome.name());
                })
                .onErrorResume(error -> Mono.just(failure(message, command.orderId(), error)));
    }

    private Result failure(Message message, Throwable error) {
        return failure(message, null, error);
    }

    private Result failure(Message message, String orderId, Throwable error) {
        FailureOutcome outcome = switch (error) {
            case InvalidMessageException invalid -> FailureOutcome.INVALID_MESSAGE;
            case TransientFailureException transientFailure -> FailureOutcome.TRANSIENT_FAILURE;
            case PaymentGatewayUnavailableException unavailable -> FailureOutcome.PAYMENT_GATEWAY_UNAVAILABLE;
            case OrderNotFoundException notFound -> FailureOutcome.ORDER_NOT_FOUND;
            case InventoryConditionFailedException inconsistent -> FailureOutcome.INVENTORY_INCONSISTENT;
            case TimeoutException timeout -> FailureOutcome.PROCESSING_TIMEOUT;
            default -> FailureOutcome.UNEXPECTED_ERROR;
        };
        if (outcome.isUnexpected()) {
            log.error("{} message {} for order {} failed ({}); kept for redelivery (receiveCount={})",
                    expectedType.wireName(), message.messageId(), orderId, outcome, receiveCount(message), error);
        } else {
            log.warn("{} message {} for order {} not processed ({}: {}); kept for redelivery, then DLQ (receiveCount={})",
                    expectedType.wireName(), message.messageId(), orderId, outcome, error.getMessage(),
                    receiveCount(message));
        }
        return new Result(MessageDisposition.RETAIN, outcome.name());
    }

    private static String receiveCount(Message message) {
        return message.attributes().getOrDefault(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "?");
    }

    private record Result(MessageDisposition disposition, String outcome) {
    }
}
