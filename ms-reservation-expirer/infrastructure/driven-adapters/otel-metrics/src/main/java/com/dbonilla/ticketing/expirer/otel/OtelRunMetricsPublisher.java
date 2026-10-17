package com.dbonilla.ticketing.expirer.otel;

import com.dbonilla.ticketing.expirer.model.metrics.RunMetrics;
import com.dbonilla.ticketing.expirer.model.metrics.gateways.RunMetricsPublisher;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Records the run counters and force-flushes them before the invocation returns. Every value is added even
 * when it is 0, so each series exists (and reads 0) from the first run on.
 */
public final class OtelRunMetricsPublisher implements RunMetricsPublisher {

    static final String INSTRUMENTATION_SCOPE = "com.dbonilla.ticketing.expirer";
    static final String EXPIRED = "ticketing.reservations.expired";
    static final String REPUBLISHED = "ticketing.orders.republished";
    static final String FAILURES = "ticketing.expirer.failures";
    static final AttributeKey<String> STATUS = AttributeKey.stringKey("status");
    private static final Attributes QUEUED = Attributes.of(STATUS, "QUEUED");
    private static final Attributes PENDING_CONFIRMATION = Attributes.of(STATUS, "PENDING_CONFIRMATION");
    private static final Logger LOG = LogManager.getLogger(OtelRunMetricsPublisher.class);

    private final SdkMeterProvider meterProvider;
    private final Duration flushTimeout;
    private final LongCounter expired;
    private final LongCounter republished;
    private final LongCounter failures;

    public OtelRunMetricsPublisher(SdkMeterProvider meterProvider, Duration flushTimeout) {
        this.meterProvider = Objects.requireNonNull(meterProvider, "meterProvider is required");
        this.flushTimeout = Objects.requireNonNull(flushTimeout, "flushTimeout is required");
        Meter meter = meterProvider.get(INSTRUMENTATION_SCOPE);
        this.expired = meter.counterBuilder(EXPIRED)
                .setDescription("Reservations expired and returned to the inventory").build();
        this.republished = meter.counterBuilder(REPUBLISHED)
                .setDescription("Stuck orders re-published by the reconciliation, by status").build();
        this.failures = meter.counterBuilder(FAILURES)
                .setDescription("Order operations that failed and will be retried by the next run").build();
    }

    @Override
    public void publish(RunMetrics metrics) {
        try {
            expired.add(metrics.expired());
            republished.add(metrics.republishedQueued(), QUEUED);
            republished.add(metrics.republishedPendingConfirmation(), PENDING_CONFIRMATION);
            failures.add(metrics.failures());
            flush();
        } catch (RuntimeException error) {
            LOG.warn("Could not publish the run metrics: {}", error.toString());
        }
    }

    private void flush() {
        CompletableResultCode result = meterProvider.forceFlush().join(flushTimeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!result.isSuccess()) {
            LOG.warn("Metrics flush did not complete within {} ms", flushTimeout.toMillis());
        }
    }
}
