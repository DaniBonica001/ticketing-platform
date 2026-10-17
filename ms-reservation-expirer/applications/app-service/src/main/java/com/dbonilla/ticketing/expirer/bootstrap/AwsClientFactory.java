package com.dbonilla.ticketing.expirer.bootstrap;

import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.time.Duration;

/**
 * Builds the async AWS SDK v2 clients once per execution environment.
 * <ul>
 *   <li>One shared Netty HTTP client (fewer threads and less init time).</li>
 *   <li>Credentials straight from the Lambda environment variables (no provider-chain probing).</li>
 *   <li>SDK retries disabled: the documented Reactor policy (TransientRetry) is the only retry layer.</li>
 * </ul>
 */
final class AwsClientFactory {

    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_CONNECTIONS = 50;

    private final ExpirerSettings settings;
    private final SdkAsyncHttpClient httpClient;

    AwsClientFactory(ExpirerSettings settings) {
        this.settings = settings;
        this.httpClient = NettyNioAsyncHttpClient.builder()
                .connectionTimeout(CONNECTION_TIMEOUT)
                .maxConcurrency(MAX_CONNECTIONS)
                .build();
    }

    DynamoDbAsyncClient dynamoDb() {
        return configure(DynamoDbAsyncClient.builder().httpClient(httpClient)).build();
    }

    SqsAsyncClient sqs() {
        return configure(SqsAsyncClient.builder().httpClient(httpClient)).build();
    }

    private <B extends AwsClientBuilder<B, ?>> B configure(B builder) {
        builder.region(Region.of(settings.region()))
                .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .retryStrategy(AwsRetryStrategy.doNotRetry())
                        .apiCallAttemptTimeout(ATTEMPT_TIMEOUT)
                        .apiCallTimeout(CALL_TIMEOUT)
                        .build());
        settings.endpointOverride().ifPresent(builder::endpointOverride);
        return builder;
    }
}
