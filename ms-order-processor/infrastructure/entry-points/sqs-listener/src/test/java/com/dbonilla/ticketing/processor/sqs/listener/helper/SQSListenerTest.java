package com.dbonilla.ticketing.processor.sqs.listener.helper;

import com.dbonilla.ticketing.processor.sqs.listener.MessageDisposition;
import com.dbonilla.ticketing.processor.sqs.listener.TestMessages;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SQSListenerTest {

    private static final String QUEUE = "http://localhost:4566/000000000000/ticketing-orders-queue";

    private final SqsAsyncClient client = mock(SqsAsyncClient.class);

    private static SQSListener.ListenerSettings settings(int visibilityTimeout) {
        return new SQSListener.ListenerSettings(QUEUE, 20, 10, visibilityTimeout, 1, 10,
                Duration.ofMillis(10), Duration.ofMillis(50));
    }

    private SQSListener listener(Function<Message, Mono<MessageDisposition>> processor) {
        return new SQSListener(client, settings(0), processor);
    }

    private void receive(Message... messages) {
        when(client.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(
                CompletableFuture.completedFuture(ReceiveMessageResponse.builder().messages(messages).build()));
    }

    private static Message message(String id) {
        return TestMessages.message("{}").toBuilder().messageId(id).receiptHandle("receipt-" + id).build();
    }

    @Test
    void deletesOnlyMessagesMarkedForDeletion() {
        // Arrange
        receive(message("ok"), message("keep"));
        when(client.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(DeleteMessageResponse.builder().build()));
        SQSListener listener = listener(message -> Mono.just(
                "ok".equals(message.messageId()) ? MessageDisposition.DELETE : MessageDisposition.RETAIN));

        // Act
        StepVerifier.create(listener.receiveAndProcess()).verifyComplete();

        // Assert
        ArgumentCaptor<DeleteMessageRequest> captor = ArgumentCaptor.forClass(DeleteMessageRequest.class);
        verify(client, times(1)).deleteMessage(captor.capture());
        assertThat(captor.getValue().receiptHandle()).isEqualTo("receipt-ok");
        assertThat(captor.getValue().queueUrl()).isEqualTo(QUEUE);
        assertThat(captor.getValue().overrideConfiguration())
                .as("delete keeps the tight client-level timeouts").isEmpty();
    }

    @Test
    void usesLongPollingBatchesAndRequestsTraceAttributes() {
        receive();

        StepVerifier.create(listener(message -> Mono.just(MessageDisposition.DELETE)).receiveAndProcess()).verifyComplete();

        ArgumentCaptor<ReceiveMessageRequest> captor = ArgumentCaptor.forClass(ReceiveMessageRequest.class);
        verify(client).receiveMessage(captor.capture());
        ReceiveMessageRequest request = captor.getValue();
        assertThat(request.waitTimeSeconds()).isEqualTo(20);
        assertThat(request.maxNumberOfMessages()).isEqualTo(10);
        assertThat(request.visibilityTimeout()).isNull();
        assertThat(request.messageAttributeNames()).contains("All");
        assertThat(request.messageSystemAttributeNames()).contains(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT);
    }

    @Test
    void receiveTimeoutOutlivesTheLongPoll() {
        // Arrange
        receive();

        // Act
        StepVerifier.create(listener(message -> Mono.just(MessageDisposition.DELETE)).receiveAndProcess()).verifyComplete();

        // Assert: the 20 s long poll plus the margin, so an empty poll is never cut by the client timeout
        ArgumentCaptor<ReceiveMessageRequest> captor = ArgumentCaptor.forClass(ReceiveMessageRequest.class);
        verify(client).receiveMessage(captor.capture());
        Duration expected = Duration.ofSeconds(20).plus(SQSListener.RECEIVE_TIMEOUT_MARGIN);
        assertThat(expected).isEqualTo(Duration.ofSeconds(25));
        assertThat(captor.getValue().overrideConfiguration()).hasValueSatisfying(override -> {
            assertThat(override.apiCallTimeout()).contains(expected);
            assertThat(override.apiCallAttemptTimeout()).contains(expected);
        });
    }

    @Test
    void overridesVisibilityTimeoutOnlyWhenConfigured() {
        receive();

        StepVerifier.create(new SQSListener(client, settings(45), message -> Mono.empty()).receiveAndProcess())
                .verifyComplete();

        ArgumentCaptor<ReceiveMessageRequest> captor = ArgumentCaptor.forClass(ReceiveMessageRequest.class);
        verify(client).receiveMessage(captor.capture());
        assertThat(captor.getValue().visibilityTimeout()).isEqualTo(45);
    }

    @Test
    void keepsMessageWhenTheProcessorFails() {
        receive(message("boom"));
        SQSListener listener = listener(message -> Mono.error(new IllegalStateException("bug")));

        StepVerifier.create(listener.receiveAndProcess()).verifyComplete();

        verify(client, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void keepsMessageWhenTheProcessorThrows() {
        receive(message("boom"));
        SQSListener listener = listener(message -> {
            throw new IllegalStateException("bug");
        });

        StepVerifier.create(listener.receiveAndProcess()).verifyComplete();

        verify(client, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void deleteFailuresDoNotStopTheBatch() {
        receive(message("a"), message("b"));
        when(client.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(SdkClientException.create("network")));

        StepVerifier.create(listener(message -> Mono.just(MessageDisposition.DELETE)).receiveAndProcess())
                .verifyComplete();

        verify(client, times(2)).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void pollerKeepsPollingAfterReceiveErrorsUntilStopped() {
        // Arrange
        AtomicInteger receives = new AtomicInteger();
        when(client.receiveMessage(any(ReceiveMessageRequest.class))).thenAnswer(invocation ->
                receives.incrementAndGet() == 1
                        ? CompletableFuture.failedFuture(SdkClientException.create("queue not ready"))
                        : CompletableFuture.completedFuture(ReceiveMessageResponse.builder().build()));
        SQSListener listener = listener(message -> Mono.just(MessageDisposition.DELETE));

        // Act
        listener.start();

        // Assert
        verify(client, timeout(2000).atLeast(3)).receiveMessage(any(ReceiveMessageRequest.class));
        assertThat(listener.isRunning()).isTrue();
        listener.stop();
        assertThat(listener.isRunning()).isFalse();
        int afterStop = receives.get();
        verify(client, after(100).atMost(afterStop + 1)).receiveMessage(any(ReceiveMessageRequest.class));
    }

    @Test
    void backoffResetsAfterASuccessfulReceive() {
        // Arrange: 4 failures grow the backoff, one success, then an isolated failure
        SQSListener.ListenerSettings slowBackoff = new SQSListener.ListenerSettings(QUEUE, 20, 10, 0, 1, 10,
                Duration.ofSeconds(1), Duration.ofSeconds(30));
        List<Long> receivedAt = new CopyOnWriteArrayList<>();
        when(client.receiveMessage(any(ReceiveMessageRequest.class))).thenAnswer(invocation -> {
            receivedAt.add(VirtualTimeScheduler.get().now(TimeUnit.MILLISECONDS));
            int call = receivedAt.size();
            if (call == 5) {
                return CompletableFuture.completedFuture(ReceiveMessageResponse.builder().build());
            }
            return call <= 6
                    ? CompletableFuture.failedFuture(SdkClientException.create("blip"))
                    : new CompletableFuture<ReceiveMessageResponse>();
        });

        // Act
        StepVerifier.withVirtualTime(() -> new SQSListener(client, slowBackoff, message -> Mono.empty()).poll())
                .thenAwait(Duration.ofMinutes(2))
                .thenCancel()
                .verify();

        // Assert: the failure after the success waits the minimum backoff again (1 s + up to 50 % jitter)
        assertThat(receivedAt).hasSize(7);
        assertThat(receivedAt.get(4) - receivedAt.get(3)).as("backoff grew before the success")
                .isGreaterThanOrEqualTo(4_000);
        assertThat(receivedAt.get(6) - receivedAt.get(5)).as("backoff after the success")
                .isBetween(1_000L, 1_500L);
    }

    @Test
    void settingsAreValidated() {
        assertThatThrownBy(() -> new SQSListener.ListenerSettings(" ", 20, 10, 0, 1, 1, Duration.ZERO, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SQSListener.ListenerSettings(null, 20, 10, 0, 1, 1, Duration.ZERO, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SQSListener.ListenerSettings(QUEUE, 20, 10, 0, 0, 1, Duration.ZERO, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SQSListener.ListenerSettings(QUEUE, 20, 10, 0, 1, 0, Duration.ZERO, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SQSListener.ListenerSettings(QUEUE, 20, 0, 0, 1, 1, Duration.ZERO, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
