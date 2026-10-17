package com.dbonilla.ticketing.processor.dynamodb.config;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.metrics.MetricPublisher;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class DynamoDBConfigTest {

    private final DynamoDBConfig config = new DynamoDBConfig();

    @Test
    void buildsClientWithAndWithoutEndpointOverride() {
        MetricPublisher publisher = mock(MetricPublisher.class);

        try (DynamoDbAsyncClient local = config.dynamoDbAsyncClient("us-east-1", "http://localhost:4566", publisher);
             DynamoDbAsyncClient aws = config.dynamoDbAsyncClient("us-east-1", "", publisher)) {
            assertThat(local).isNotNull();
            assertThat(aws).isNotNull();
        }
    }

    @Test
    void tableNamesAreRequired() {
        assertThat(config.tableNames("events", "orders", "audit").orders()).isEqualTo("orders");
        assertThatThrownBy(() -> new TableNames(null, "o", "a")).isInstanceOf(NullPointerException.class);
    }
}
