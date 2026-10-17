package com.dbonilla.ticketing.api.dynamodb.support;

import com.dbonilla.ticketing.api.resilience.TransientErrors;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.util.Map;
import java.util.Optional;

/**
 * Reads {@link TransactionCanceledException#cancellationReasons()}, whose positions match the items of
 * the {@code TransactWriteItems} request.
 */
public final class Cancellations {

    private Cancellations() {
    }

    public static boolean conditionFailedAt(TransactionCanceledException error, int index) {
        return reasonAt(error, index)
                .map(reason -> TransientErrors.CONDITIONAL_CHECK_FAILED.equals(reason.code()))
                .orElse(false);
    }

    /** Item returned by {@code ReturnValuesOnConditionCheckFailure=ALL_OLD}, when present. */
    public static Optional<Map<String, AttributeValue>> oldItemAt(TransactionCanceledException error, int index) {
        return reasonAt(error, index)
                .filter(CancellationReason::hasItem)
                .map(CancellationReason::item)
                .filter(Attributes::isPresent);
    }

    private static Optional<CancellationReason> reasonAt(TransactionCanceledException error, int index) {
        if (!error.hasCancellationReasons() || error.cancellationReasons().size() <= index) {
            return Optional.empty();
        }
        return Optional.of(error.cancellationReasons().get(index));
    }
}
