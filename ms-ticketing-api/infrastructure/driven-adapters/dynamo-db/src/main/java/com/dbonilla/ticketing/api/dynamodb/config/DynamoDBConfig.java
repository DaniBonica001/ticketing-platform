package com.dbonilla.ticketing.api.dynamodb.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.metrics.MetricPublisher;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClientBuilder;

import java.net.URI;

@Configuration
public class DynamoDBConfig {

    /**
     * Non-blocking client. {@code aws.endpoint-url} is set only locally (LocalStack); in AWS the default
     * endpoint and the task IAM role are used.
     */
    @Bean(destroyMethod = "close")
    public DynamoDbAsyncClient dynamoDbAsyncClient(@Value("${aws.region}") String region,
                                                   @Value("${aws.endpoint-url:}") String endpointUrl,
                                                   MetricPublisher publisher) {
        DynamoDbAsyncClientBuilder builder = DynamoDbAsyncClient.builder()
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .region(Region.of(region))
                .overrideConfiguration(o -> o.addMetricPublisher(publisher));
        if (!endpointUrl.isBlank()) {
            builder.endpointOverride(URI.create(endpointUrl));
        }
        return builder.build();
    }
}
