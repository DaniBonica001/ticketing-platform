package com.dbonilla.ticketing.expirer.bootstrap;

import com.dbonilla.ticketing.expirer.helpers.retry.RetrySettings;
import com.dbonilla.ticketing.expirer.lambda.ExpirerComponents;
import com.dbonilla.ticketing.expirer.lambda.ExpirerComponentsProvider;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LambdaComponentsProviderTest {

    @Test
    void wiresBothUseCasesFromTheSettings() {
        ExpirerSettings settings = ExpirerSettings.fromEnvironment(ExpirerSettingsTest.requiredEnvironment());
        Clock clock = Clock.systemUTC();

        ExpirerComponents components = LambdaComponentsProvider.wire(settings, mock(DynamoDbAsyncClient.class),
                mock(SqsAsyncClient.class), clock, RetrySettings.DEFAULT);

        assertThat(components.expireReservations()).isNotNull();
        assertThat(components.reconcileStuckOrders()).isNotNull();
        assertThat(components.clock()).isSameAs(clock);
    }

    @Test
    void buildsRealClientsFromTheEnvironment() {
        Map<String, String> env = new HashMap<>(ExpirerSettingsTest.requiredEnvironment());
        env.put("AWS_ENDPOINT_URL", "http://localhost:4566");

        ExpirerComponents components = new LambdaComponentsProvider().create(env);

        assertThat(components.expireReservations()).isNotNull();
    }

    @Test
    void isRegisteredForTheHandler() {
        assertThat(ServiceLoader.load(ExpirerComponentsProvider.class).stream())
                .anyMatch(provider -> provider.type() == LambdaComponentsProvider.class);
    }
}
