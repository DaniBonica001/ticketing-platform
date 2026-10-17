package com.dbonilla.ticketing.api.integration;

import com.dbonilla.ticketing.api.dynamodb.config.DynamoTablesProperties;
import com.dbonilla.ticketing.api.dynamodb.mapper.EventItemMapper;
import com.dbonilla.ticketing.api.dynamodb.mapper.OrderItemMapper;
import com.dbonilla.ticketing.api.model.event.Event;
import com.dbonilla.ticketing.api.model.order.Order;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Test fixture: one LocalStack container (random host port) with the three tables of docs/design/dynamodb.md.
 * Blocking calls are acceptable here: this is test code.
 */
final class LocalStackDynamoDb {

    static final String IMAGE = "localstack/localstack:4.14.0";
    static final DynamoTablesProperties TABLES =
            new DynamoTablesProperties("ticketing-events", "ticketing-orders", "ticketing-order-audit");

    private LocalStackDynamoDb() {
    }

    static LocalStackContainer container() {
        return new LocalStackContainer(DockerImageName.parse(IMAGE)).withEnv("SERVICES", "dynamodb");
    }

    static DynamoDbAsyncClient client(LocalStackContainer localStack) {
        return DynamoDbAsyncClient.builder()
                .endpointOverride(localStack.getEndpoint())
                .region(Region.of(localStack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localStack.getAccessKey(), localStack.getSecretKey())))
                // Same as production: SDK retries off, the reactive policy (TransientRetry) retries.
                .overrideConfiguration(o -> o.retryStrategy(AwsRetryStrategy.doNotRetry()))
                .build();
    }

    static void createTables(DynamoDbAsyncClient client) {
        createTable(client, TABLES.eventsTable(), "eventId", null);
        createTable(client, TABLES.ordersTable(), "orderId", null);
        createTable(client, TABLES.auditTable(), "orderId", "transitionId");
    }

    private static void createTable(DynamoDbAsyncClient client, String name, String partitionKey, String sortKey) {
        List<AttributeDefinition> attributes = new ArrayList<>();
        List<KeySchemaElement> keys = new ArrayList<>();
        attributes.add(AttributeDefinition.builder().attributeName(partitionKey).attributeType(ScalarAttributeType.S).build());
        keys.add(KeySchemaElement.builder().attributeName(partitionKey).keyType(KeyType.HASH).build());
        if (sortKey != null) {
            attributes.add(AttributeDefinition.builder().attributeName(sortKey).attributeType(ScalarAttributeType.S).build());
            keys.add(KeySchemaElement.builder().attributeName(sortKey).keyType(KeyType.RANGE).build());
        }
        client.createTable(CreateTableRequest.builder()
                .tableName(name)
                .attributeDefinitions(attributes)
                .keySchema(keys)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build()).join();
    }

    static void putEvent(DynamoDbAsyncClient client, Event event) {
        client.putItem(PutItemRequest.builder().tableName(TABLES.eventsTable())
                .item(EventItemMapper.toItem(event)).build()).join();
    }

    static void putOrder(DynamoDbAsyncClient client, Order order) {
        client.putItem(PutItemRequest.builder().tableName(TABLES.ordersTable())
                .item(OrderItemMapper.toItem(order)).build()).join();
    }

    static Map<String, AttributeValue> event(DynamoDbAsyncClient client, String eventId) {
        return client.getItem(GetItemRequest.builder().tableName(TABLES.eventsTable()).consistentRead(true)
                .key(EventItemMapper.key(eventId)).build()).join().item();
    }

    static Map<String, AttributeValue> order(DynamoDbAsyncClient client, String orderId) {
        return client.getItem(GetItemRequest.builder().tableName(TABLES.ordersTable()).consistentRead(true)
                .key(OrderItemMapper.key(orderId)).build()).join().item();
    }

    static List<Map<String, AttributeValue>> auditOf(DynamoDbAsyncClient client, String orderId) {
        return client.query(QueryRequest.builder().tableName(TABLES.auditTable()).consistentRead(true)
                .keyConditionExpression("orderId = :o")
                .expressionAttributeValues(Map.of(":o", AttributeValue.fromS(orderId))).build()).join().items();
    }

    static int number(Map<String, AttributeValue> item, String attribute) {
        return Integer.parseInt(item.get(attribute).n());
    }
}
