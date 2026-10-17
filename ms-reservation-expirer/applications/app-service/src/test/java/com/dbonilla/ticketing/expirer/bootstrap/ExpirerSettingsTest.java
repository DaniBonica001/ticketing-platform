package com.dbonilla.ticketing.expirer.bootstrap;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpirerSettingsTest {

    static Map<String, String> requiredEnvironment() {
        return Map.of(
                "AWS_REGION", "us-east-1",
                "EVENTS_TABLE", "ticketing-events",
                "ORDERS_TABLE", "ticketing-orders",
                "AUDIT_TABLE", "ticketing-order-audit",
                "ORDERS_QUEUE_URL", "http://localstack:4566/000000000000/ticketing-orders-queue",
                "CONFIRMATIONS_QUEUE_URL", "http://localstack:4566/000000000000/ticketing-confirmations-queue");
    }

    private static Map<String, String> with(String name, String value) {
        Map<String, String> env = new HashMap<>(requiredEnvironment());
        env.put(name, value);
        return env;
    }

    @Test
    void readsRequiredVariablesAndAppliesDefaults() {
        ExpirerSettings settings = ExpirerSettings.fromEnvironment(requiredEnvironment());

        assertThat(settings.region()).isEqualTo("us-east-1");
        assertThat(settings.endpointOverride()).isEmpty();
        assertThat(settings.tables().orders()).isEqualTo("ticketing-orders");
        assertThat(settings.tables().events()).isEqualTo("ticketing-events");
        assertThat(settings.tables().audit()).isEqualTo("ticketing-order-audit");
        assertThat(settings.queues().orders()).endsWith("ticketing-orders-queue");
        assertThat(settings.queues().confirmations()).endsWith("ticketing-confirmations-queue");
        assertThat(settings.reservationTtl()).isEqualTo(Duration.ofSeconds(600));
        assertThat(settings.reconciliationDeadline()).isEqualTo(Duration.ofSeconds(300));
        assertThat(settings.scan().pageSize()).isEqualTo(100);
        assertThat(settings.scan().concurrency()).isEqualTo(4);
    }

    @Test
    void readsOptionalOverrides() {
        Map<String, String> env = new HashMap<>(requiredEnvironment());
        env.put("AWS_ENDPOINT_URL", "http://localstack:4566");
        env.put("RESERVATION_TTL_SECONDS", "900");
        env.put("RECONCILIATION_DEADLINE_SECONDS", " 120 ");
        env.put("EXPIRER_PAGE_SIZE", "25");
        env.put("EXPIRER_CONCURRENCY", "1");

        ExpirerSettings settings = ExpirerSettings.fromEnvironment(env);

        assertThat(settings.endpointOverride()).contains(URI.create("http://localstack:4566"));
        assertThat(settings.reservationTtl()).isEqualTo(Duration.ofSeconds(900));
        assertThat(settings.reconciliationDeadline()).isEqualTo(Duration.ofSeconds(120));
        assertThat(settings.scan().pageSize()).isEqualTo(25);
        assertThat(settings.scan().concurrency()).isEqualTo(1);
    }

    @Test
    void reportsEveryMissingVariableAtOnce() {
        assertThatThrownBy(() -> ExpirerSettings.fromEnvironment(Map.of("AWS_REGION", " ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContainingAll("AWS_REGION", "EVENTS_TABLE", "ORDERS_TABLE", "AUDIT_TABLE",
                        "ORDERS_QUEUE_URL", "CONFIRMATIONS_QUEUE_URL");
    }

    @Test
    void rejectsInvalidNumbers() {
        assertThatThrownBy(() -> ExpirerSettings.fromEnvironment(with("RESERVATION_TTL_SECONDS", "ten")))
                .hasMessageContaining("RESERVATION_TTL_SECONDS");
        assertThatThrownBy(() -> ExpirerSettings.fromEnvironment(with("RECONCILIATION_DEADLINE_SECONDS", "0")))
                .hasMessageContaining("RECONCILIATION_DEADLINE_SECONDS");
        assertThatThrownBy(() -> ExpirerSettings.fromEnvironment(with("EXPIRER_PAGE_SIZE", "5000")))
                .hasMessageContaining("EXPIRER_PAGE_SIZE");
    }

    @Test
    void rejectsInvalidEndpointsWithoutEchoingTheValue() {
        assertThatThrownBy(() -> ExpirerSettings.fromEnvironment(with("AWS_ENDPOINT_URL", "ftp://secret-host")))
                .hasMessageContaining("AWS_ENDPOINT_URL")
                .hasMessageNotContaining("secret-host");
        assertThatThrownBy(() -> ExpirerSettings.fromEnvironment(with("AWS_ENDPOINT_URL", "http://bad host")))
                .hasMessageContaining("AWS_ENDPOINT_URL");
        assertThatThrownBy(() -> ExpirerSettings.fromEnvironment(with("AWS_ENDPOINT_URL", "localstack")))
                .hasMessageContaining("AWS_ENDPOINT_URL");
    }
}
