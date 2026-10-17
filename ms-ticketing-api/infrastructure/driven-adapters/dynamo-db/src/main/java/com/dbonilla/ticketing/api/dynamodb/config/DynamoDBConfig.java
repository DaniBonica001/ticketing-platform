package com.dbonilla.ticketing.api.dynamodb.config;

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

    /**
     * Non-blocking client. {@code aws.endpoint-url} is set only locally (LocalStack); in AWS the default
     * endpoint and the task IAM role are used.
     * <p>
     * SDK retries are disabled: transient failures are retried by the reactive policy of the adapters
     * ({@code ticketing.retry.*}), so a failure is retried 3 times instead of SDK attempts x Reactor attempts.
     * The timeouts bound a stalled connection: each attempt and the whole call fail fast as transient errors.
     */
    @Bean(destroyMethod = "close")
    public DynamoDbAsyncClient dynamoDbAsyncClient(
            @Value("${aws.region}") String region,
            @Value("${aws.endpoint-url:}") String endpointUrl,
            @Value("${adapter.dynamodb.api-call-timeout:5s}") Duration apiCallTimeout,
            @Value("${adapter.dynamodb.api-call-attempt-timeout:2s}") Duration apiCallAttemptTimeout,
            MetricPublisher publisher) {
        DynamoDbAsyncClientBuilder builder = DynamoDbAsyncClient.builder()
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .region(Region.of(region))
                .overrideConfiguration(o -> o.addMetricPublisher(publisher)
                        .retryStrategy(AwsRetryStrategy.doNotRetry())
                        .apiCallTimeout(apiCallTimeout)
                        .apiCallAttemptTimeout(apiCallAttemptTimeout));
        if (!endpointUrl.isBlank()) {
            builder.endpointOverride(URI.create(endpointUrl));
        }
        return builder.build();
    }
}
