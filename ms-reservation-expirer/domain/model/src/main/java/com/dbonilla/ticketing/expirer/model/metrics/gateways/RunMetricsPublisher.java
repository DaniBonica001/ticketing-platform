package com.dbonilla.ticketing.expirer.model.metrics.gateways;

import com.dbonilla.ticketing.expirer.model.metrics.RunMetrics;

/**
 * Publishes the metrics of a run. Implementations must export before returning (the Lambda execution
 * environment is frozen right after the invocation), bound that wait, and never throw: telemetry must not
 * fail a run. It is synchronous on purpose: it is called once per invocation, after the reactive flow.
 */
public interface RunMetricsPublisher {

    /** Publisher used when telemetry is disabled. */
    RunMetricsPublisher NOOP = metrics -> {
    };

    void publish(RunMetrics metrics);
}
