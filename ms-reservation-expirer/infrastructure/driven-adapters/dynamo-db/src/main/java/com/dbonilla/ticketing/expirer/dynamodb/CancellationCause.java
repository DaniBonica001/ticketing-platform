package com.dbonilla.ticketing.expirer.dynamodb;

import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.util.List;

/**
 * Meaning of a cancelled T7 transaction, read from {@code CancellationReasons} by item position.
 */
enum CancellationCause {
    /** The order is no longer RESERVED and overdue: the user confirmed concurrently. */
    ORDER_CONDITION_FAILED,
    /** The event has fewer reserved tickets than the order: inventory invariant would break. */
    EVENT_CONDITION_FAILED,
    /** Conflicts, throttling or anything else. */
    OTHER;

    private static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";

    static CancellationCause of(TransactionCanceledException exception) {
        List<CancellationReason> reasons = exception.hasCancellationReasons()
                ? exception.cancellationReasons()
                : List.of();
        if (failedCondition(reasons, ExpireOrderTransactionFactory.ORDER_ITEM)) {
            return ORDER_CONDITION_FAILED;
        }
        if (failedCondition(reasons, ExpireOrderTransactionFactory.EVENT_ITEM)) {
            return EVENT_CONDITION_FAILED;
        }
        return OTHER;
    }

    private static boolean failedCondition(List<CancellationReason> reasons, int index) {
        return reasons.size() > index && CONDITIONAL_CHECK_FAILED.equals(reasons.get(index).code());
    }
}
