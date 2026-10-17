package com.dbonilla.ticketing.api.sqs.sender.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * @param endpoint              optional endpoint override (LocalStack only)
 * @param apiCallTimeout        upper bound of one SDK call, all attempts included (default 5 s)
 * @param apiCallAttemptTimeout upper bound of one HTTP attempt (default 2 s)
 */
@ConfigurationProperties(prefix = "adapter.sqs")
public record SQSSenderProperties(String region, String endpoint, String ordersQueueUrl,
                                  String confirmationsQueueUrl, Duration apiCallTimeout,
                                  Duration apiCallAttemptTimeout) {

    public static final Duration DEFAULT_API_CALL_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration DEFAULT_API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(2);

    public SQSSenderProperties {
        Objects.requireNonNull(region, "adapter.sqs.region");
        Objects.requireNonNull(ordersQueueUrl, "adapter.sqs.orders-queue-url");
        Objects.requireNonNull(confirmationsQueueUrl, "adapter.sqs.confirmations-queue-url");
        apiCallTimeout = apiCallTimeout == null ? DEFAULT_API_CALL_TIMEOUT : apiCallTimeout;
        apiCallAttemptTimeout = apiCallAttemptTimeout == null ? DEFAULT_API_CALL_ATTEMPT_TIMEOUT : apiCallAttemptTimeout;
    }
}
