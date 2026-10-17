package com.dbonilla.ticketing.api.sqs.sender;

import com.dbonilla.ticketing.api.model.order.Order;
import com.dbonilla.ticketing.api.model.order.OrderStatus;
import com.dbonilla.ticketing.api.resilience.RetryProperties;
import com.dbonilla.ticketing.api.resilience.TransientRetry;
import com.dbonilla.ticketing.api.sqs.sender.config.SQSSenderProperties;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SQSSenderTest {

    private static final Instant NOW = Instant.parse("2026-10-16T10:00:00.123Z");
    private static final String ORDERS_URL = "http://localstack:4566/000000000000/ticketing-orders-queue";
    private static final String CONFIRMATIONS_URL = "http://localstack:4566/000000000000/ticketing-confirmations-queue";
    private static final Order ORDER = new Order("3f2c9a54-1b7e-5d2a-8c4f-0e6b9d1a7c33",
            "8b0e4f1a-6c2d-4e9b-b1a3-5d7c9e0f2a64", "user-1", 2, OrderStatus.QUEUED, null, NOW, "h", null, NOW, NOW);

    @Mock
    private SqsAsyncClient client;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private SQSSender sender;

    @BeforeEach
    void setUp() {
        SQSSenderProperties properties = new SQSSenderProperties("us-east-1", null, ORDERS_URL, CONFIRMATIONS_URL);
        TransientRetry retry = new TransientRetry(new RetryProperties(3, Duration.ofMillis(1), Duration.ofMillis(2), 0.5));
        sender = new SQSSender(client, properties, retry, jsonMapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private SendMessageRequest sentRequest() {
        ArgumentCaptor<SendMessageRequest> request = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(client).sendMessage(request.capture());
        return request.getValue();
    }

    private void sendSucceeds() {
        when(client.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(SendMessageResponse.builder().messageId("m1").build()));
    }

    @Test
    void publishesOrderPlacedEnvelopeToOrdersQueue() {
        sendSucceeds();

        StepVerifier.create(sender.publishOrderPlaced(ORDER)).verifyComplete();

        SendMessageRequest request = sentRequest();
        assertThat(request.queueUrl()).isEqualTo(ORDERS_URL);
        JsonNode body = jsonMapper.readTree(request.messageBody());
        assertThat(body.get("type").asString()).isEqualTo("OrderPlaced");
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("producer").asString()).isEqualTo("ticketing-api");
        assertThat(body.get("occurredAt").asString()).isEqualTo("2026-10-16T10:00:00.123Z");
        assertThat(body.get("messageId").asString()).hasSize(36);
        assertThat(body.get("payload").get("orderId").asString()).isEqualTo(ORDER.orderId());
        assertThat(body.get("payload").get("eventId").asString()).isEqualTo(ORDER.eventId());
        assertThat(body.properties()).hasSize(6);
        assertThat(request.messageAttributes().get("messageType").stringValue()).isEqualTo("OrderPlaced");
        assertThat(request.messageAttributes()).doesNotContainKey("traceparent");
    }

    @Test
    void publishesConfirmationWithTraceparent() {
        sendSucceeds();
        SpanContext context = SpanContext.create("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7",
                TraceFlags.getSampled(), TraceState.getDefault());

        try (Scope ignored = Span.wrap(context).makeCurrent()) {
            StepVerifier.create(sender.publishConfirmationRequested(ORDER)).verifyComplete();
        }

        SendMessageRequest request = sentRequest();
        assertThat(request.queueUrl()).isEqualTo(CONFIRMATIONS_URL);
        assertThat(request.messageAttributes().get("messageType").stringValue()).isEqualTo("OrderConfirmationRequested");
        assertThat(request.messageAttributes().get("traceparent").stringValue())
                .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    }

    @Test
    void retriesTransientFailuresThreeTimesThenFails() {
        when(client.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(SdkClientException.create("connection refused")));

        StepVerifier.create(sender.publishOrderPlaced(ORDER)).verifyError(SdkClientException.class);
        verify(client, times(4)).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void recoversWhenARetrySucceeds() {
        when(client.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(SdkClientException.create("reset")))
                .thenReturn(CompletableFuture.completedFuture(SendMessageResponse.builder().messageId("m1").build()));

        StepVerifier.create(sender.publishOrderPlaced(ORDER)).verifyComplete();
        verify(client, times(2)).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void doesNotRetryNonTransientFailures() {
        when(client.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(AwsServiceException.builder().statusCode(403).build()));

        StepVerifier.create(sender.publishOrderPlaced(ORDER)).verifyError(AwsServiceException.class);
        verify(client, times(1)).sendMessage(any(SendMessageRequest.class));
    }
}
