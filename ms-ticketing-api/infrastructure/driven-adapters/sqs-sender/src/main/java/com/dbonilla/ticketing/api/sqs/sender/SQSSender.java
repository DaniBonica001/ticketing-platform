package com.dbonilla.ticketing.api.sqs.sender;

import com.dbonilla.ticketing.api.model.order.Order;
import com.dbonilla.ticketing.api.model.order.gateways.OrderMessagePublisher;
import com.dbonilla.ticketing.api.resilience.TraceContext;
import com.dbonilla.ticketing.api.resilience.TransientRetry;
import com.dbonilla.ticketing.api.sqs.sender.config.SQSSenderProperties;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes thin order messages (only ids). Transient failures are retried; when the retries are
 * exhausted the failure is logged and propagated so that the caller applies its dual-write policy.
 */
@Service
public class SQSSender implements OrderMessagePublisher {

    static final String MESSAGE_TYPE_ATTRIBUTE = "messageType";
    static final String TRACEPARENT_ATTRIBUTE = "traceparent";
    static final String INSTRUMENTATION_SCOPE = "com.dbonilla.ticketing.api.sqs.sender";
    static final String MESSAGING_SYSTEM = "messaging.system";
    static final String MESSAGING_DESTINATION = "messaging.destination.name";
    static final String MESSAGING_OPERATION = "messaging.operation";
    static final String MESSAGING_MESSAGE_ID = "messaging.message.id";
    static final String SYSTEM_AWS_SQS = "aws_sqs";
    static final String OPERATION_PUBLISH = "publish";
    private static final String STRING_TYPE = "String";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final Logger log = LoggerFactory.getLogger(SQSSender.class);

    private final SqsAsyncClient client;
    private final SQSSenderProperties properties;
    private final TransientRetry retry;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final Tracer tracer;

    public SQSSender(SqsAsyncClient client, SQSSenderProperties properties, TransientRetry retry,
                     JsonMapper jsonMapper, Clock clock, OpenTelemetry openTelemetry) {
        this.client = client;
        this.properties = properties;
        this.retry = retry;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
    }

    @Override
    public Mono<Void> publishOrderPlaced(Order order) {
        return send(properties.ordersQueueUrl(), OrderMessage.ORDER_PLACED, order);
    }

    @Override
    public Mono<Void> publishConfirmationRequested(Order order) {
        return send(properties.confirmationsQueueUrl(), OrderMessage.CONFIRMATION_REQUESTED, order);
    }

    /**
     * One PRODUCER span covers the whole publish (retries included). The request is built while that span
     * is current, so the injected {@code traceparent} makes the consumer span a child of this one.
     */
    private Mono<Void> send(String queueUrl, String type, Order order) {
        return Mono.defer(() -> {
            Span span = startProducerSpan(queueName(queueUrl));
            SendMessageRequest request;
            try (Scope ignored = span.makeCurrent()) {
                request = buildRequest(queueUrl, type, order);
            } catch (RuntimeException error) {
                return Mono.<Void>error(error).doFinally(signal -> endWithError(span, error));
            }
            return Mono.fromFuture(() -> client.sendMessage(request)).retryWhen(retry.spec())
                    .doOnNext(response -> {
                        span.setAttribute(MESSAGING_MESSAGE_ID, response.messageId());
                        log.info("Published {} for order {} as message {}", type, order.orderId(),
                                response.messageId());
                    })
                    .doOnError(error -> {
                        span.recordException(error);
                        span.setStatus(StatusCode.ERROR);
                        log.error("Could not publish {} for order {}; reconciliation will re-publish it",
                                type, order.orderId(), error);
                    })
                    .doFinally(signal -> span.end())
                    .then();
        });
    }

    private Span startProducerSpan(String queueName) {
        return tracer.spanBuilder(queueName + " " + OPERATION_PUBLISH)
                .setSpanKind(SpanKind.PRODUCER)
                .setAttribute(MESSAGING_SYSTEM, SYSTEM_AWS_SQS)
                .setAttribute(MESSAGING_DESTINATION, queueName)
                .setAttribute(MESSAGING_OPERATION, OPERATION_PUBLISH)
                .startSpan();
    }

    private static void endWithError(Span span, Throwable error) {
        span.recordException(error);
        span.setStatus(StatusCode.ERROR);
        span.end();
    }

    /** Last path segment of the queue URL ({@code .../000000000000/ticketing-orders-queue}). */
    static String queueName(String queueUrl) {
        String trimmed = queueUrl.endsWith("/") ? queueUrl.substring(0, queueUrl.length() - 1) : queueUrl;
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }

    private SendMessageRequest buildRequest(String queueUrl, String type, Order order) {
        OrderMessage message = new OrderMessage(UUID.randomUUID().toString(), type, OrderMessage.SCHEMA_VERSION,
                TIMESTAMP.format(clock.instant()), OrderMessage.PRODUCER,
                new OrderMessage.Payload(order.orderId(), order.eventId()));
        return SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(jsonMapper.writeValueAsString(message))
                .messageAttributes(attributes(type))
                .build();
    }

    private static Map<String, MessageAttributeValue> attributes(String type) {
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        attributes.put(MESSAGE_TYPE_ATTRIBUTE, stringAttribute(type));
        TraceContext.currentTraceparent()
                .ifPresent(traceparent -> attributes.put(TRACEPARENT_ATTRIBUTE, stringAttribute(traceparent)));
        return Map.copyOf(attributes);
    }

    private static MessageAttributeValue stringAttribute(String value) {
        return MessageAttributeValue.builder().dataType(STRING_TYPE).stringValue(value).build();
    }
}
