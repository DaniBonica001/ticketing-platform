package com.dbonilla.ticketing.processor.integration;

import com.dbonilla.ticketing.processor.dynamodb.config.TableNames;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryMode;
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
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Test fixture: one LocalStack container (random host port) with the three tables of docs/design/dynamodb.md.
 * Blocking calls are acceptable here: this is test code.
 */
final class LocalStackDynamoDb {

    static final String IMAGE = "localstack/localstack:4.14.0";
    static final TableNames TABLES = new TableNames("ticketing-events", "ticketing-orders", "ticketing-order-audit");

    private LocalStackDynamoDb() {
    }

    static LocalStackContainer container() {
        return new LocalStackContainer(DockerImageName.parse(IMAGE))
                .withEnv("SERVICES", "dynamodb");
    }

    static DynamoDbAsyncClient client(LocalStackContainer localStack) {
        return DynamoDbAsyncClient.builder()
                .endpointOverride(localStack.getEndpoint())
                .region(Region.of(localStack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localStack.getAccessKey(), localStack.getSecretKey())))
                .overrideConfiguration(o -> o.retryStrategy(RetryMode.STANDARD))
                .build();
    }

    static void createTables(DynamoDbAsyncClient client) {
        createTable(client, TABLES.events(), "eventId", null);
        createTable(client, TABLES.orders(), "orderId", null);
        createTable(client, TABLES.audit(), "orderId", "transitionId");
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

    static void putEvent(DynamoDbAsyncClient client, String eventId, int capacity, int available, int pendingConfirmation) {
        client.putItem(PutItemRequest.builder().tableName(TABLES.events()).item(Map.of(
                "eventId", s(eventId),
                "name", s("Concurrency test"),
                "capacity", n(capacity),
                "available", n(available),
                "reserved", n(0),
                "pendingConfirmation", n(pendingConfirmation),
                "sold", n(0),
                "complimentary", n(0),
                "version", n(1))).build()).join();
    }

    static void putOrder(DynamoDbAsyncClient client, String orderId, String eventId, int quantity, String status,
                         String paymentReference) {
        Map<String, AttributeValue> item = new java.util.HashMap<>(Map.of(
                "orderId", s(orderId),
                "eventId", s(eventId),
                "userId", s("user-" + orderId),
                "quantity", n(quantity),
                "status", s(status),
                "deadlineAt", n(Instant.now().plusSeconds(300).getEpochSecond())));
        if (paymentReference != null) {
            item.put("paymentReference", s(paymentReference));
        }
        client.putItem(PutItemRequest.builder().tableName(TABLES.orders()).item(item).build()).join();
    }

    static Map<String, AttributeValue> event(DynamoDbAsyncClient client, String eventId) {
        return client.getItem(GetItemRequest.builder().tableName(TABLES.events()).consistentRead(true)
                .key(Map.of("eventId", s(eventId))).build()).join().item();
    }

    static Map<String, AttributeValue> order(DynamoDbAsyncClient client, String orderId) {
        return client.getItem(GetItemRequest.builder().tableName(TABLES.orders()).consistentRead(true)
                .key(Map.of("orderId", s(orderId))).build()).join().item();
    }

    static List<Map<String, AttributeValue>> auditOf(DynamoDbAsyncClient client, String orderId) {
        return client.query(QueryRequest.builder().tableName(TABLES.audit()).consistentRead(true)
                .keyConditionExpression("orderId = :o")
                .expressionAttributeValues(Map.of(":o", s(orderId))).build()).join().items();
    }

    static int countAuditRows(DynamoDbAsyncClient client) {
        int count = 0;
        Map<String, AttributeValue> startKey = null;
        do {
            var response = client.scan(ScanRequest.builder().tableName(TABLES.audit()).consistentRead(true)
                    .exclusiveStartKey(startKey).build()).join();
            count += response.count();
            startKey = response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty()
                    ? response.lastEvaluatedKey() : null;
        } while (startKey != null);
        return count;
    }

    static int number(Map<String, AttributeValue> item, String attribute) {
        return Integer.parseInt(item.get(attribute).n());
    }

    static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    static AttributeValue n(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }
}
