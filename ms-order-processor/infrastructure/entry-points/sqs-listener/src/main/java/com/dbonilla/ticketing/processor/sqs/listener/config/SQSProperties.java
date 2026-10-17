package com.dbonilla.ticketing.processor.sqs.listener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param numberOfThreads  concurrent pollers per queue
 * @param concurrency      messages of one batch processed in parallel by each poller
 * @param visibilityTimeoutSeconds 0 keeps the visibility timeout configured on the queue
 * @param apiCallTimeout        client-level bound of one SDK call (default 5 s). {@code ReceiveMessage} overrides it
 *                              per request so the long poll is never cut
 * @param apiCallAttemptTimeout client-level bound of one HTTP attempt (default 2 s)
 * @param processingTimeout     deadline of one message (default 25 s). Kept below the queue's VisibilityTimeout
 *                              (30 s, ADR-0003) so a message is not redelivered while still in flight
 */
@ConfigurationProperties(prefix = "entrypoint.sqs")
public record SQSProperties(
        String region,
        String endpoint,
        String ordersQueueUrl,
        String confirmationsQueueUrl,
        int waitTimeSeconds,
        int visibilityTimeoutSeconds,
        int maxNumberOfMessages,
        int numberOfThreads,
        int concurrency,
        Duration apiCallTimeout,
        Duration apiCallAttemptTimeout,
        Duration processingTimeout) {

    public static final Duration DEFAULT_API_CALL_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration DEFAULT_API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(2);
    public static final Duration DEFAULT_PROCESSING_TIMEOUT = Duration.ofSeconds(25);

    public SQSProperties {
        apiCallTimeout = apiCallTimeout == null ? DEFAULT_API_CALL_TIMEOUT : apiCallTimeout;
        apiCallAttemptTimeout = apiCallAttemptTimeout == null ? DEFAULT_API_CALL_ATTEMPT_TIMEOUT : apiCallAttemptTimeout;
        processingTimeout = processingTimeout == null ? DEFAULT_PROCESSING_TIMEOUT : processingTimeout;
    }
}
