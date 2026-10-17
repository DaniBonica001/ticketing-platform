package com.dbonilla.ticketing.api.sqs.sender.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

/**
 * @param endpoint optional endpoint override (LocalStack only)
 */
@ConfigurationProperties(prefix = "adapter.sqs")
public record SQSSenderProperties(String region, String endpoint, String ordersQueueUrl,
                                  String confirmationsQueueUrl) {

    public SQSSenderProperties {
        Objects.requireNonNull(region, "adapter.sqs.region");
        Objects.requireNonNull(ordersQueueUrl, "adapter.sqs.orders-queue-url");
        Objects.requireNonNull(confirmationsQueueUrl, "adapter.sqs.confirmations-queue-url");
    }
}
