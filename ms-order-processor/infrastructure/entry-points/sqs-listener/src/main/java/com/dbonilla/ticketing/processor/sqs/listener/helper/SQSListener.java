package com.dbonilla.ticketing.processor.sqs.listener.helper;

import com.dbonilla.ticketing.processor.sqs.listener.MessageDisposition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.time.Duration;
import java.util.function.Function;

/**
 * Reactive long-polling consumer of one queue. Each poller receives a batch, processes it with bounded concurrency
 * and deletes only the messages whose disposition is {@link MessageDisposition#DELETE}.
 */
public class SQSListener {

    private static final Logger log = LoggerFactory.getLogger(SQSListener.class);
    private static final String ALL_ATTRIBUTES = "All";
    /** Time on top of the long poll for SQS to answer an empty receive before the client gives up. */
    static final Duration RECEIVE_TIMEOUT_MARGIN = Duration.ofSeconds(5);

    private final SqsAsyncClient client;
    private final ListenerSettings settings;
    private final Function<Message, Mono<MessageDisposition>> processor;
    private final Disposable.Composite pollers = Disposables.composite();

    public SQSListener(SqsAsyncClient client, ListenerSettings settings,
                       Function<Message, Mono<MessageDisposition>> processor) {
        this.client = client;
        this.settings = settings;
        this.processor = processor;
    }

    public SQSListener start() {
        log.info("Starting {} poller(s) for queue {}", settings.pollers(), settings.queueUrl());
        for (int i = 0; i < settings.pollers(); i++) {
            pollers.add(poll().subscribe());
        }
        return this;
    }

    public void stop() {
        log.info("Stopping pollers of queue {}", settings.queueUrl());
        pollers.dispose();
    }

    public boolean isRunning() {
        return !pollers.isDisposed() && pollers.size() > 0;
    }

    /**
     * Polls forever. The backoff counts consecutive failures only ({@code transientErrors}): a successful receive
     * resets it, so an isolated blip late in the pod's life waits the minimum backoff, not the maximum. The reset
     * is driven by {@code onNext}, so every completed receive emits one signal.
     */
    Mono<Void> poll() {
        return Mono.defer(this::receiveAndProcess)
                .thenReturn(Boolean.TRUE)
                .repeat()
                .retryWhen(Retry.backoff(Long.MAX_VALUE, settings.errorBackoff())
                        .maxBackoff(settings.maxErrorBackoff())
                        .transientErrors(true)
                        .doBeforeRetry(signal -> log.warn("Error receiving from queue {}: {}", settings.queueUrl(),
                                signal.failure().toString())))
                .then();
    }

    Mono<Void> receiveAndProcess() {
        return Mono.fromFuture(() -> client.receiveMessage(receiveRequest()))
                .flatMapMany(response -> Flux.fromIterable(response.messages()))
                .flatMap(this::handle, settings.concurrency())
                .then();
    }

    Mono<Void> handle(Message message) {
        return Mono.defer(() -> processor.apply(message))
                .onErrorResume(error -> {
                    log.error("Unhandled error processing message {}; it is kept for redelivery", message.messageId(), error);
                    return Mono.just(MessageDisposition.RETAIN);
                })
                .flatMap(disposition -> disposition == MessageDisposition.DELETE ? delete(message) : Mono.empty());
    }

    private Mono<Void> delete(Message message) {
        DeleteMessageRequest request = DeleteMessageRequest.builder()
                .queueUrl(settings.queueUrl())
                .receiptHandle(message.receiptHandle())
                .build();
        return Mono.fromFuture(() -> client.deleteMessage(request))
                .then()
                .onErrorResume(error -> {
                    log.warn("Message {} could not be deleted; it will be re-delivered and handled as a duplicate: {}",
                            message.messageId(), error.toString());
                    return Mono.empty();
                });
    }

    private ReceiveMessageRequest receiveRequest() {
        Duration receiveTimeout = Duration.ofSeconds(settings.waitTimeSeconds()).plus(RECEIVE_TIMEOUT_MARGIN);
        ReceiveMessageRequest.Builder builder = ReceiveMessageRequest.builder()
                .overrideConfiguration(o -> o.apiCallTimeout(receiveTimeout).apiCallAttemptTimeout(receiveTimeout))
                .queueUrl(settings.queueUrl())
                .maxNumberOfMessages(settings.maxNumberOfMessages())
                .waitTimeSeconds(settings.waitTimeSeconds())
                .messageAttributeNames(ALL_ATTRIBUTES)
                .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT);
        if (settings.visibilityTimeoutSeconds() > 0) {
            builder.visibilityTimeout(settings.visibilityTimeoutSeconds());
        }
        return builder.build();
    }

    /**
     * @param visibilityTimeoutSeconds 0 keeps the visibility timeout configured on the queue
     */
    public record ListenerSettings(String queueUrl, int waitTimeSeconds, int maxNumberOfMessages,
                                   int visibilityTimeoutSeconds, int pollers, int concurrency,
                                   Duration errorBackoff, Duration maxErrorBackoff) {

        public ListenerSettings {
            if (queueUrl == null || queueUrl.isBlank()) {
                throw new IllegalArgumentException("queueUrl is required");
            }
            if (pollers < 1 || concurrency < 1 || maxNumberOfMessages < 1) {
                throw new IllegalArgumentException("pollers, concurrency and maxNumberOfMessages must be positive");
            }
        }
    }
}
