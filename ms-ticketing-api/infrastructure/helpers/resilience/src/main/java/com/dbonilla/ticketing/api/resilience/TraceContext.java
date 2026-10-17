package com.dbonilla.ticketing.api.resilience;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;

import java.util.Optional;

/**
 * Access to the current OpenTelemetry span (W3C trace context).
 */
public final class TraceContext {

    private static final String TRACEPARENT_VERSION = "00";

    private TraceContext() {
    }

    public static Optional<String> currentTraceId() {
        return current().map(SpanContext::getTraceId);
    }

    /** W3C {@code traceparent} header value of the current span. */
    public static Optional<String> currentTraceparent() {
        return current().map(context -> String.join("-", TRACEPARENT_VERSION, context.getTraceId(),
                context.getSpanId(), context.getTraceFlags().asHex()));
    }

    private static Optional<SpanContext> current() {
        SpanContext context = Span.current().getSpanContext();
        return context.isValid() ? Optional.of(context) : Optional.empty();
    }
}
