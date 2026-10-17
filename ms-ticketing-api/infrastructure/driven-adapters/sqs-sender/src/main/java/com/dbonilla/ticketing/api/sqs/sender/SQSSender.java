package com.dbonilla.ticketing.api.sqs.sender;

import com.dbonilla.ticketing.api.model.order.Order;
import com.dbonilla.ticketing.api.model.order.gateways.OrderMessagePublisher;
import com.dbonilla.ticketing.api.resilience.TraceContext;
import com.dbonilla.ticketing.api.resilience.TransientRetry;
import com.dbonilla.ticketing.api.sqs.sender.config.SQSSenderProperties;
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
    private static final String STRING_TYPE = "String";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final Logger log = LoggerFactory.getLogger(SQSSender.class);

    private final SqsAsyncClient client;
    private final SQSSenderProperties properties;
    private final TransientRetry retry;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public SQSSender(SqsAsyncClient client, SQSSenderProperties properties, TransientRetry retry,
                     JsonMapper jsonMapper, Clock clock) {
        this.client = client;
        this.properties = properties;
        this.retry = retry;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Override
    public Mono<Void> publishOrderPlaced(Order order) {
        return send(properties.ordersQueueUrl(), OrderMessage.ORDER_PLACED, order);
    }

    @Override
    public Mono<Void> publishConfirmationRequested(Order order) {
        return send(properties.confirmationsQueueUrl(), OrderMessage.CONFIRMATION_REQUESTED, order);
    }

    private Mono<Void> send(String queueUrl, String type, Order order) {
        return Mono.fromCallable(() -> buildRequest(queueUrl, type, order))
                .flatMap(request -> Mono.fromFuture(() -> client.sendMessage(request)).retryWhen(retry.spec()))
                .doOnNext(response -> log.info("Published {} for order {} as message {}",
                        type, order.orderId(), response.messageId()))
                .doOnError(error -> log.error("Could not publish {} for order {}; reconciliation will re-publish it",
                        type, order.orderId(), error))
                .then();
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
