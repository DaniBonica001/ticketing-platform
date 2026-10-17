package com.dbonilla.ticketing.api.sqs.sender.config;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.metrics.MetricPublisher;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SQSSenderConfigTest {

    private final SQSSenderConfig config = new SQSSenderConfig();
    private final MetricPublisher publisher = mock(MetricPublisher.class);

    private static SQSSenderProperties properties(String endpoint) {
        return new SQSSenderProperties("us-east-1", endpoint, "o", "c", null, null);
    }

    @Test
    void buildsClientWithOptionalEndpoint() {
        try (SqsAsyncClient local = config.sqsAsyncClient(properties("http://localhost:4566"), publisher);
             SqsAsyncClient blank = config.sqsAsyncClient(properties(" "), publisher);
             SqsAsyncClient aws = config.sqsAsyncClient(properties(null), publisher)) {
            assertThat(local.serviceClientConfiguration().endpointOverride()).isPresent();
            assertThat(blank.serviceClientConfiguration().endpointOverride()).isEmpty();
            assertThat(aws.serviceClientConfiguration().endpointOverride()).isEmpty();
        }
    }

    @Test
    void clientIsBoundedByTimeoutsAndLeavesRetriesToTheReactivePolicy() {
        // Arrange
        SQSSenderProperties properties = new SQSSenderProperties("us-east-1", null, "o", "c",
                Duration.ofSeconds(7), Duration.ofSeconds(3));

        // Act
        try (SqsAsyncClient client = config.sqsAsyncClient(properties, publisher)) {
            ClientOverrideConfiguration override = client.serviceClientConfiguration().overrideConfiguration();

            // Assert
            assertThat(override.apiCallTimeout()).contains(Duration.ofSeconds(7));
            assertThat(override.apiCallAttemptTimeout()).contains(Duration.ofSeconds(3));
            assertThat(override.retryStrategy()).hasValueSatisfying(strategy ->
                    assertThat(strategy.maxAttempts()).isEqualTo(1));
            assertThat(override.metricPublishers()).containsExactly(publisher);
        }
    }

    @Test
    void timeoutsHaveSensibleDefaults() {
        SQSSenderProperties defaults = properties(null);

        assertThat(defaults.apiCallTimeout()).isEqualTo(SQSSenderProperties.DEFAULT_API_CALL_TIMEOUT)
                .isEqualTo(Duration.ofSeconds(5));
        assertThat(defaults.apiCallAttemptTimeout()).isEqualTo(SQSSenderProperties.DEFAULT_API_CALL_ATTEMPT_TIMEOUT)
                .isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void queueUrlsAndRegionAreMandatory() {
        assertThatThrownBy(() -> new SQSSenderProperties(null, null, "o", "c", null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SQSSenderProperties("r", null, null, "c", null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SQSSenderProperties("r", null, "o", null, null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
