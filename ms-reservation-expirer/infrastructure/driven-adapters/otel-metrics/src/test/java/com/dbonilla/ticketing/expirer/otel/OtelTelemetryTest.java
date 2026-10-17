package com.dbonilla.ticketing.expirer.otel;

import com.dbonilla.ticketing.expirer.model.metrics.RunMetrics;
import com.dbonilla.ticketing.expirer.model.metrics.gateways.RunMetricsPublisher;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class OtelTelemetryTest {

    @Test
    void isANoopWhenDisabled() {
        assertThat(OtelTelemetry.metricsPublisher(new TelemetrySettings(null, "svc")))
                .isSameAs(RunMetricsPublisher.NOOP);
    }

    @Test
    void buildsAnOtlpPublisherThatNeverFailsWhenTheCollectorIsUnreachable() {
        RunMetricsPublisher publisher = OtelTelemetry.metricsPublisher(
                new TelemetrySettings(URI.create("http://127.0.0.1:1"), "svc"));

        assertThat(publisher).isInstanceOf(OtelRunMetricsPublisher.class);
        assertThatCode(() -> publisher.publish(new RunMetrics(1, 0, 0, 0))).doesNotThrowAnyException();
    }

    @Test
    void resourceCarriesTheServiceNameAndAUniqueInstanceId() {
        InMemoryMetricExporter exporter = InMemoryMetricExporter.create();
        var provider = OtelTelemetry.meterProvider(new TelemetrySettings(null, "expirer-test"), exporter);

        new OtelRunMetricsPublisher(provider, Duration.ofSeconds(1)).publish(new RunMetrics(0, 0, 0, 0));

        MetricData metric = exporter.getFinishedMetricItems().getFirst();
        assertThat(metric.getResource().getAttribute(OtelTelemetry.SERVICE_NAME)).isEqualTo("expirer-test");
        assertThat(metric.getResource().getAttribute(OtelTelemetry.SERVICE_INSTANCE_ID)).hasSize(36);
    }
}
