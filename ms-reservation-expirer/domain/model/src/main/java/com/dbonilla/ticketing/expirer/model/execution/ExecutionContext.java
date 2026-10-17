package com.dbonilla.ticketing.expirer.model.execution;

import java.time.Instant;
import java.util.Objects;

/**
 * Data shared by every operation of one expirer run.
 *
 * @param now     reference instant of the run (milliseconds precision)
 * @param traceId correlation id written to audit rows and propagated in messages
 * @param budget  remaining execution time
 */
public record ExecutionContext(Instant now, String traceId, TimeBudget budget) {

    public ExecutionContext {
        Objects.requireNonNull(now, "now is required");
        Objects.requireNonNull(traceId, "traceId is required");
        Objects.requireNonNull(budget, "budget is required");
    }

    public boolean isOutOfTime() {
        return budget.isExhausted();
    }
}
