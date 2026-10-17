package com.dbonilla.ticketing.processor.dynamodb.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.metrics.MetricPublisher;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClientBuilder;

import java.net.URI;
import java.time.Duration;

@Configuration
public class DynamoDBConfig {

    private static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(2);

    /**
     * SDK retries are disabled: transient failures are retried once, by the use cases, with the project policy.
     */
    @Bean
    public TableNames tableNames(@Value("${adapters.dynamodb.tables.events}") String events,
                                 @Value("${adapters.dynamodb.tables.orders}") String orders,
                                 @Value("${adapters.dynamodb.tables.audit}") String audit) {
        return new TableNames(events, orders, audit);
    }

    @Bean
    public DynamoDbAsyncClient dynamoDbAsyncClient(@Value("${aws.region}") String region,
                                                   @Value("${aws.endpoint:}") String endpoint,
                                                   MetricPublisher publisher) {
        DynamoDbAsyncClientBuilder builder = DynamoDbAsyncClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .overrideConfiguration(o -> o.addMetricPublisher(publisher)
                        .retryStrategy(AwsRetryStrategy.doNotRetry())
                        .apiCallTimeout(API_CALL_TIMEOUT)
                        .apiCallAttemptTimeout(API_CALL_ATTEMPT_TIMEOUT));
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }
}
