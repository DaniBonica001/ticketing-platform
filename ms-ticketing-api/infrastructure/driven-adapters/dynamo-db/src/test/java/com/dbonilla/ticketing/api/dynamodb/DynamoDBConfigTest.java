package com.dbonilla.ticketing.api.dynamodb;

import com.dbonilla.ticketing.api.dynamodb.config.DynamoDBConfig;
import com.dbonilla.ticketing.api.dynamodb.config.DynamoTablesProperties;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.metrics.MetricPublisher;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class DynamoDBConfigTest {

    @Test
    void buildsClientWithAndWithoutEndpointOverride() {
        DynamoDBConfig config = new DynamoDBConfig();
        MetricPublisher publisher = mock(MetricPublisher.class);

        try (DynamoDbAsyncClient local = config.dynamoDbAsyncClient("us-east-1", "http://localhost:4566", publisher);
             DynamoDbAsyncClient aws = config.dynamoDbAsyncClient("us-east-1", "", publisher)) {
            assertThat(local.serviceClientConfiguration().endpointOverride()).isPresent();
            assertThat(aws.serviceClientConfiguration().endpointOverride()).isEmpty();
        }
    }

    @Test
    void tableNamesAreMandatory() {
        assertThatThrownBy(() -> new DynamoTablesProperties(null, "o", "a")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DynamoTablesProperties("e", null, "a")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DynamoTablesProperties("e", "o", null)).isInstanceOf(NullPointerException.class);
    }
}
