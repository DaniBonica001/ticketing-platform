package com.dbonilla.ticketing.expirer.otel;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

/**
 * Telemetry configuration from the standard OpenTelemetry variables (docs/design/conventions.md, section 5).
 *
 * @param endpoint    base OTLP/HTTP endpoint ({@code OTEL_EXPORTER_OTLP_ENDPOINT}); {@code null} disables telemetry
 * @param serviceName {@code OTEL_SERVICE_NAME}, {@value #DEFAULT_SERVICE_NAME} by default
 */
public record TelemetrySettings(URI endpoint, String serviceName) {

    static final String OTLP_ENDPOINT = "OTEL_EXPORTER_OTLP_ENDPOINT";
    static final String SERVICE_NAME = "OTEL_SERVICE_NAME";
    static final String DEFAULT_SERVICE_NAME = "ms-reservation-expirer";
    private static final String METRICS_PATH = "/v1/metrics";

    public static TelemetrySettings fromEnvironment(Map<String, String> env) {
        URI endpoint = value(env, OTLP_ENDPOINT).map(TelemetrySettings::parseEndpoint).orElse(null);
        String serviceName = value(env, SERVICE_NAME).orElse(DEFAULT_SERVICE_NAME);
        return new TelemetrySettings(endpoint, serviceName);
    }

    public boolean isEnabled() {
        return endpoint != null;
    }

    /** OTLP/HTTP metrics URL: the base endpoint plus the signal path, as the OTel specification defines. */
    String metricsUrl() {
        String base = endpoint.toString();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + METRICS_PATH;
    }

    private static Optional<String> value(Map<String, String> env, String name) {
        return Optional.ofNullable(env.get(name)).map(String::trim).filter(value -> !value.isEmpty());
    }

    private static URI parseEndpoint(String raw) {
        try {
            URI uri = URI.create(raw);
            if (("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null) {
                return uri;
            }
        } catch (IllegalArgumentException ignored) {
            // reported below
        }
        throw new IllegalStateException("Invalid configuration: " + OTLP_ENDPOINT + " must be an absolute http(s) URL");
    }
}
