package com.dbonilla.ticketing.api.dynamodb;

import com.dbonilla.ticketing.api.dynamodb.config.DynamoDBConfig;
import com.dbonilla.ticketing.api.dynamodb.config.DynamoTablesProperties;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.metrics.MetricPublisher;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class DynamoDBConfigTest {

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(2);

    private final DynamoDBConfig config = new DynamoDBConfig();
    private final MetricPublisher publisher = mock(MetricPublisher.class);

    @Test
    void buildsClientWithAndWithoutEndpointOverride() {
        try (DynamoDbAsyncClient local = config.dynamoDbAsyncClient("us-east-1", "http://localhost:4566",
                CALL_TIMEOUT, ATTEMPT_TIMEOUT, publisher);
             DynamoDbAsyncClient aws = config.dynamoDbAsyncClient("us-east-1", "", CALL_TIMEOUT, ATTEMPT_TIMEOUT,
                     publisher)) {
            assertThat(local.serviceClientConfiguration().endpointOverride()).isPresent();
            assertThat(aws.serviceClientConfiguration().endpointOverride()).isEmpty();
        }
    }

    @Test
    void clientIsBoundedByTimeoutsAndLeavesRetriesToTheReactivePolicy() {
        // Act
        try (DynamoDbAsyncClient client = config.dynamoDbAsyncClient("us-east-1", "", CALL_TIMEOUT, ATTEMPT_TIMEOUT,
                publisher)) {
            ClientOverrideConfiguration override = client.serviceClientConfiguration().overrideConfiguration();

            // Assert
            assertThat(override.apiCallTimeout()).contains(CALL_TIMEOUT);
            assertThat(override.apiCallAttemptTimeout()).contains(ATTEMPT_TIMEOUT);
            assertThat(override.retryStrategy()).hasValueSatisfying(strategy ->
                    assertThat(strategy.maxAttempts()).isEqualTo(1));
            assertThat(override.metricPublishers()).containsExactly(publisher);
        }
    }

    @Test
    void tableNamesAreMandatory() {
        assertThatThrownBy(() -> new DynamoTablesProperties(null, "o", "a")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DynamoTablesProperties("e", null, "a")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DynamoTablesProperties("e", "o", null)).isInstanceOf(NullPointerException.class);
    }
}
