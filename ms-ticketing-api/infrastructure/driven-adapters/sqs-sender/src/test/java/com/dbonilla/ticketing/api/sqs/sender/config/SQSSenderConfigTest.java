package com.dbonilla.ticketing.api.sqs.sender.config;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.metrics.MetricPublisher;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SQSSenderConfigTest {

    @Test
    void buildsClientWithOptionalEndpoint() {
        SQSSenderConfig config = new SQSSenderConfig();
        MetricPublisher publisher = mock(MetricPublisher.class);

        try (SqsAsyncClient local = config.sqsAsyncClient(new SQSSenderProperties("us-east-1", "http://localhost:4566", "o", "c"), publisher);
             SqsAsyncClient blank = config.sqsAsyncClient(new SQSSenderProperties("us-east-1", " ", "o", "c"), publisher);
             SqsAsyncClient aws = config.sqsAsyncClient(new SQSSenderProperties("us-east-1", null, "o", "c"), publisher)) {
            assertThat(local.serviceClientConfiguration().endpointOverride()).isPresent();
            assertThat(blank.serviceClientConfiguration().endpointOverride()).isEmpty();
            assertThat(aws.serviceClientConfiguration().endpointOverride()).isEmpty();
        }
    }

    @Test
    void queueUrlsAndRegionAreMandatory() {
        assertThatThrownBy(() -> new SQSSenderProperties(null, null, "o", "c")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SQSSenderProperties("r", null, null, "c")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SQSSenderProperties("r", null, "o", null)).isInstanceOf(NullPointerException.class);
    }
}
