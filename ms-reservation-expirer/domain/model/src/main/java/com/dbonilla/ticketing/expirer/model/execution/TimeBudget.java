package com.dbonilla.ticketing.expirer.model.execution;

/**
 * Tells long scans when to stop so the invocation finishes before its time limit.
 */
@FunctionalInterface
public interface TimeBudget {

    boolean isExhausted();

    static TimeBudget unlimited() {
        return () -> false;
    }
}
