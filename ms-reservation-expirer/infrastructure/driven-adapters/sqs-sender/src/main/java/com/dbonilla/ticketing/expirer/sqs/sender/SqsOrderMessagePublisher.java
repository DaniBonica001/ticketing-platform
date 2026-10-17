package com.dbonilla.ticketing.expirer.sqs.sender;

import com.dbonilla.ticketing.expirer.model.message.OrderMessage;
import com.dbonilla.ticketing.expirer.model.message.gateways.OrderMessagePublisher;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.Map;

/**
 * Publishes re-published order messages with the {@code messageType} and {@code traceparent} attributes.
 */
public class SqsOrderMessagePublisher implements OrderMessagePublisher {

    static final String MESSAGE_TYPE_ATTRIBUTE = "messageType";
    static final String TRACEPARENT_ATTRIBUTE = "traceparent";
    private static final String STRING_TYPE = "String";

    private final SqsAsyncClient client;
    private final QueueUrls queueUrls;
    private final Retry retry;
    private final OrderMessageSerializer serializer = new OrderMessageSerializer();

    public SqsOrderMessagePublisher(SqsAsyncClient client, QueueUrls queueUrls, Retry retry) {
        this.client = client;
        this.queueUrls = queueUrls;
        this.retry = retry;
    }

    @Override
    public Mono<Void> publish(OrderMessage message) {
        return Mono.fromCallable(() -> buildRequest(message))
                .flatMap(request -> Mono.fromFuture(() -> client.sendMessage(request)).retryWhen(retry))
                .then();
    }

    SendMessageRequest buildRequest(OrderMessage message) {
        return SendMessageRequest.builder()
                .queueUrl(queueUrls.urlFor(message.type()))
                .messageBody(serializer.toJson(message))
                .messageAttributes(Map.of(
                        MESSAGE_TYPE_ATTRIBUTE, stringAttribute(message.type().contractName()),
                        TRACEPARENT_ATTRIBUTE, stringAttribute(TraceParent.of(message.traceId()))))
                .build();
    }

    private static MessageAttributeValue stringAttribute(String value) {
        return MessageAttributeValue.builder().dataType(STRING_TYPE).stringValue(value).build();
    }
}
