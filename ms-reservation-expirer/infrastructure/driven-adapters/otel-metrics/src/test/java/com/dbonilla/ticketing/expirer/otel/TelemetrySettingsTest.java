package com.dbonilla.ticketing.expirer.otel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelemetrySettingsTest {

    @Test
    void isDisabledWithoutAnEndpoint() {
        TelemetrySettings settings = TelemetrySettings.fromEnvironment(Map.of("OTEL_EXPORTER_OTLP_ENDPOINT", "  "));

        assertThat(settings.isEnabled()).isFalse();
        assertThat(settings.serviceName()).isEqualTo("ms-reservation-expirer");
    }

    @Test
    void readsTheEndpointAndServiceName() {
        TelemetrySettings settings = TelemetrySettings.fromEnvironment(Map.of(
                "OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel-lgtm:4318",
                "OTEL_SERVICE_NAME", "expirer-test"));

        assertThat(settings.isEnabled()).isTrue();
        assertThat(settings.serviceName()).isEqualTo("expirer-test");
        assertThat(settings.metricsUrl()).isEqualTo("http://otel-lgtm:4318/v1/metrics");
    }

    @Test
    void appendsTheSignalPathOnceWithATrailingSlash() {
        TelemetrySettings settings = TelemetrySettings.fromEnvironment(
                Map.of("OTEL_EXPORTER_OTLP_ENDPOINT", "https://collector.example:4318/"));

        assertThat(settings.metricsUrl()).isEqualTo("https://collector.example:4318/v1/metrics");
    }

    @ParameterizedTest
    @ValueSource(strings = {"otel-lgtm:4318", "ftp://otel-lgtm", "http://", "http://bad host"})
    void rejectsInvalidEndpointsWithoutEchoingThem(String endpoint) {
        assertThatThrownBy(() -> TelemetrySettings.fromEnvironment(Map.of("OTEL_EXPORTER_OTLP_ENDPOINT", endpoint)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OTEL_EXPORTER_OTLP_ENDPOINT")
                .hasMessageNotContaining(endpoint);
    }
}
