package com.dbonilla.ticketing.expirer.otel;

import com.dbonilla.ticketing.expirer.model.metrics.gateways.RunMetricsPublisher;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;

import java.time.Duration;
import java.util.UUID;

/**
 * Builds the metrics publisher once per execution environment. Without {@code OTEL_EXPORTER_OTLP_ENDPOINT}
 * it is a no-op, so nothing changes where no collector is configured.
 */
public final class OtelTelemetry {

    /** Bounded wait for the end-of-invocation flush. */
    static final Duration FLUSH_TIMEOUT = Duration.ofSeconds(2);
    /** Export is driven by the per-invocation flush; the periodic reader is only a fallback. */
    static final Duration EXPORT_INTERVAL = Duration.ofMinutes(1);
    static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");
    static final AttributeKey<String> SERVICE_INSTANCE_ID = AttributeKey.stringKey("service.instance.id");

    private OtelTelemetry() {
    }

    public static RunMetricsPublisher metricsPublisher(TelemetrySettings settings) {
        if (!settings.isEnabled()) {
            return RunMetricsPublisher.NOOP;
        }
        MetricExporter exporter = OtlpHttpMetricExporter.builder()
                .setEndpoint(settings.metricsUrl())
                .setTimeout(FLUSH_TIMEOUT)
                .build();
        return new OtelRunMetricsPublisher(meterProvider(settings, exporter), FLUSH_TIMEOUT);
    }

    static SdkMeterProvider meterProvider(TelemetrySettings settings, MetricExporter exporter) {
        // Cumulative counters per execution environment: a unique instance id keeps concurrent containers
        // from overwriting each other's series.
        Resource resource = Resource.getDefault().merge(Resource.create(Attributes.of(
                SERVICE_NAME, settings.serviceName(),
                SERVICE_INSTANCE_ID, UUID.randomUUID().toString())));
        return SdkMeterProvider.builder()
                .setResource(resource)
                .registerMetricReader(PeriodicMetricReader.builder(exporter).setInterval(EXPORT_INTERVAL).build())
                .build();
    }
}
