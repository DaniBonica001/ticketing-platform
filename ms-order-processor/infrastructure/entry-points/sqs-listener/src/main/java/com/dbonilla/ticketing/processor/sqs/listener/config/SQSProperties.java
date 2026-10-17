package com.dbonilla.ticketing.processor.sqs.listener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param numberOfThreads  concurrent pollers per queue
 * @param concurrency      messages of one batch processed in parallel by each poller
 * @param visibilityTimeoutSeconds 0 keeps the visibility timeout configured on the queue
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
        int concurrency) {
}
