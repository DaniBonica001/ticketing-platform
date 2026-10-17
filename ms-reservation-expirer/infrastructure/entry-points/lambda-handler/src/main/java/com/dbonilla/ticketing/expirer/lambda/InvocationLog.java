package com.dbonilla.ticketing.expirer.lambda;

import com.dbonilla.ticketing.expirer.model.execution.OrderFailure;
import org.apache.logging.log4j.CloseableThreadContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured log lines of an invocation; fields travel in the thread context and the JSON layout writes
 * them as top-level keys. Only identifiers and counts are logged (no payloads, no credentials).
 */
final class InvocationLog {

    private static final Logger LOG = LogManager.getLogger(ExpirerHandler.class);

    private InvocationLog() {
    }

    static void failure(String requestId, String traceId, OrderFailure failure) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("awsRequestId", requestId);
        fields.put("traceId", traceId);
        fields.put("operation", failure.operation());
        fields.put("orderStatus", failure.status().name());
        putIfPresent(fields, "orderId", failure.orderId());
        fields.put("errorType", failure.errorType());
        putIfPresent(fields, "errorMessage", failure.errorMessage());
        try (var ignored = CloseableThreadContext.putAll(fields)) {
            LOG.warn("Order operation failed; it will be retried by the next run");
        }
    }

    static void summary(String requestId, String traceId, ExpirationSummary summary, long durationMillis) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("awsRequestId", requestId);
        fields.put("traceId", traceId);
        fields.put("expired", Integer.toString(summary.expired()));
        fields.put("skipped", Integer.toString(summary.skipped()));
        fields.put("republishedQueued", Integer.toString(summary.republishedQueued()));
        fields.put("republishedPendingConfirmation", Integer.toString(summary.republishedPendingConfirmation()));
        fields.put("failures", Integer.toString(summary.failures()));
        fields.put("durationMs", Long.toString(durationMillis));
        try (var ignored = CloseableThreadContext.putAll(fields)) {
            LOG.info("Reservation expirer run finished");
        }
    }

    static void metricsFailure(String requestId, RuntimeException error) {
        try (var ignored = CloseableThreadContext.put("awsRequestId", requestId)
                .put("errorType", error.getClass().getName())) {
            LOG.warn("Could not publish the run metrics");
        }
    }

    private static void putIfPresent(Map<String, String> fields, String key, String value) {
        if (value != null) {
            fields.put(key, value);
        }
    }
}
