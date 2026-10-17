package com.dbonilla.ticketing.expirer.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.dbonilla.ticketing.expirer.model.execution.ExecutionContext;
import com.dbonilla.ticketing.expirer.usecase.expirereservations.ExpirationResult;
import com.dbonilla.ticketing.expirer.usecase.reconcilestuckorders.ReconciliationResult;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Entry point of {@code ticketing-reservation-expirer}, invoked by the EventBridge rule every minute.
 * Runs T7 first (returning inventory matters most) and then the R1/R2 reconciliation; stops paging when
 * less than {@value #MIN_REMAINING_TIME_MILLIS} ms remain.
 */
public class ExpirerHandler implements RequestHandler<Map<String, Object>, ExpirationSummary> {

    static final long MIN_REMAINING_TIME_MILLIS = 10_000;

    private final ExpirerComponents components;

    /** Used by the Lambda runtime; components are created once per execution environment (init phase). */
    public ExpirerHandler() {
        this(SharedComponents.INSTANCE);
    }

    ExpirerHandler(ExpirerComponents components) {
        this.components = Objects.requireNonNull(components, "components are required");
    }

    @Override
    public ExpirationSummary handleRequest(Map<String, Object> scheduledEvent, Context context) {
        Objects.requireNonNull(context, "Lambda context is required");
        Instant startedAt = components.clock().instant();
        ExecutionContext execution = new ExecutionContext(startedAt.truncatedTo(ChronoUnit.MILLIS), TraceIds.current(),
                () -> context.getRemainingTimeInMillis() < MIN_REMAINING_TIME_MILLIS);
        // The Lambda programming model is synchronous: this is the only place where the reactive flow is
        // blocked. Every I/O below it uses the async AWS SDK clients.
        Tuple2<ExpirationResult, ReconciliationResult> results = run(execution).block();
        Objects.requireNonNull(results, "run produced no result");
        return report(context.getAwsRequestId(), execution, results, startedAt);
    }

    private Mono<Tuple2<ExpirationResult, ReconciliationResult>> run(ExecutionContext execution) {
        return components.expireReservations().execute(execution)
                .zipWhen(expired -> components.reconcileStuckOrders().execute(execution));
    }

    private ExpirationSummary report(String requestId, ExecutionContext execution,
                                     Tuple2<ExpirationResult, ReconciliationResult> results, Instant startedAt) {
        Stream.concat(results.getT1().failures().stream(), results.getT2().failures().stream())
                .forEach(failure -> InvocationLog.failure(requestId, execution.traceId(), failure));
        ExpirationSummary summary = ExpirationSummary.of(results.getT1(), results.getT2());
        long durationMillis = Duration.between(startedAt, components.clock().instant()).toMillis();
        InvocationLog.summary(requestId, execution.traceId(), summary, durationMillis);
        return summary;
    }

    /** Lazy holder: initialised on first use, reused by every warm invocation. */
    private static final class SharedComponents {
        private static final ExpirerComponents INSTANCE = ExpirerComponentsLoader.load();
    }
}
