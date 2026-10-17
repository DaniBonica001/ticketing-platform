package com.dbonilla.ticketing.expirer.sqs.sender;

import com.dbonilla.ticketing.expirer.helpers.retry.RetrySettings;
import com.dbonilla.ticketing.expirer.helpers.retry.TransientRetry;
import com.dbonilla.ticketing.expirer.model.message.OrderMessage;
import com.dbonilla.ticketing.expirer.model.message.OrderMessageType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqsOrderMessagePublisherTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final UUID MESSAGE_ID = UUID.fromString("7d1c0b8e-3f0a-4a8e-9a55-2b8f1c4e9d10");
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-16T10:00:00.123Z");
    private static final QueueUrls QUEUES = new QueueUrls("http://sqs/orders", "http://sqs/confirmations");
    private static final RetrySettings FAST = new RetrySettings(3, Duration.ofMillis(1), Duration.ofMillis(2), 0);

    private final SqsAsyncClient client = mock(SqsAsyncClient.class);
    private final SqsOrderMessagePublisher publisher =
            new SqsOrderMessagePublisher(client, QUEUES, TransientRetry.backoff(FAST));

    private static OrderMessage message(OrderMessageType type, String orderId) {
        return new OrderMessage(MESSAGE_ID, type, orderId, "8b0e4f1a-6c2d-4e9b-b1a3-5d7c9e0f2a64",
                OCCURRED_AT, TRACE_ID);
    }

    @Test
    void sendsTheContractEnvelopeToTheOrdersQueue() {
        when(client.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(completedFuture(SendMessageResponse.builder().messageId("m-1").build()));

        StepVerifier.create(publisher.publish(message(OrderMessageType.ORDER_PLACED,
                "3f2c9a54-1b7e-5d2a-8c4f-0e6b9d1a7c33"))).verifyComplete();

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(client).sendMessage(captor.capture());
        SendMessageRequest request = captor.getValue();
        assertThat(request.queueUrl()).isEqualTo("http://sqs/orders");
        assertThat(request.messageBody()).isEqualTo("{\"messageId\":\"7d1c0b8e-3f0a-4a8e-9a55-2b8f1c4e9d10\","
                + "\"type\":\"OrderPlaced\",\"version\":1,\"occurredAt\":\"2026-10-16T10:00:00.123Z\","
                + "\"producer\":\"reservation-expirer\",\"payload\":{\"orderId\":\"3f2c9a54-1b7e-5d2a-8c4f-0e6b9d1a7c33\","
                + "\"eventId\":\"8b0e4f1a-6c2d-4e9b-b1a3-5d7c9e0f2a64\"}}");
        assertThat(request.messageAttributes().get("messageType").stringValue()).isEqualTo("OrderPlaced");
        assertThat(request.messageAttributes().get("messageType").dataType()).isEqualTo("String");
        assertThat(request.messageAttributes().get("traceparent").stringValue())
                .matches("00-" + TRACE_ID + "-[0-9a-f]{16}-01");
    }

    @Test
    void sendsConfirmationRequestsToTheConfirmationsQueue() {
        SendMessageRequest request = publisher.buildRequest(message(OrderMessageType.ORDER_CONFIRMATION_REQUESTED, "o"));

        assertThat(request.queueUrl()).isEqualTo("http://sqs/confirmations");
        assertThat(request.messageAttributes().get("messageType").stringValue())
                .isEqualTo("OrderConfirmationRequested");
        assertThat(request.messageBody()).contains("\"type\":\"OrderConfirmationRequested\"");
    }

    @Test
    void escapesUntrustedIdentifiers() {
        SendMessageRequest request = publisher.buildRequest(message(OrderMessageType.ORDER_PLACED, "a\"b\\c"));

        assertThat(request.messageBody()).contains("\"orderId\":\"a\\\"b\\\\c\"");
    }

    @Test
    void retriesTransientSendErrors() {
        when(client.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(failedFuture(SdkClientException.create("reset")))
                .thenReturn(completedFuture(SendMessageResponse.builder().build()));

        StepVerifier.create(publisher.publish(message(OrderMessageType.ORDER_PLACED, "o"))).verifyComplete();

        verify(client, times(2)).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void rejectsAnInvalidTraceIdBeforeSending() {
        OrderMessage invalid = new OrderMessage(MESSAGE_ID, OrderMessageType.ORDER_PLACED, "o", "e", OCCURRED_AT,
                "0".repeat(32));

        StepVerifier.create(publisher.publish(invalid)).verifyError(IllegalArgumentException.class);

        verify(client, never()).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void spanIdsAreRandomNonZeroHex() {
        assertThat(TraceParent.newSpanId()).matches("[0-9a-f]{16}").isNotEqualTo("0".repeat(16));
        assertThat(TraceParent.of(TRACE_ID)).startsWith("00-" + TRACE_ID + "-").endsWith("-01");
        assertThatThrownBy(() -> TraceParent.of("XYZ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void queueUrlsAreRequired() {
        assertThatThrownBy(() -> new QueueUrls(null, "c")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueueUrls("o", " ")).isInstanceOf(IllegalArgumentException.class);
    }
}
