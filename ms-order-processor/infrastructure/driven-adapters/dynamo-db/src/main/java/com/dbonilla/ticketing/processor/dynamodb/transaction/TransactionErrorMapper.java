package com.dbonilla.ticketing.processor.dynamodb.transaction;

import com.dbonilla.ticketing.processor.awserrors.TransientErrors;
import com.dbonilla.ticketing.processor.model.exceptions.InventoryConditionFailedException;
import com.dbonilla.ticketing.processor.model.exceptions.OrderStateConflictException;
import com.dbonilla.ticketing.processor.model.exceptions.TransientFailureException;
import com.dbonilla.ticketing.processor.model.order.OrderTransition;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Interprets the {@code CancellationReasons} of a cancelled transaction item by item
 * (docs/design/dynamodb.md, T2 table). A failed order condition wins over a failed inventory condition:
 * an order that is no longer in the source state was already processed.
 */
public final class TransactionErrorMapper {

    private TransactionErrorMapper() {
    }

    public static Throwable map(Throwable error, TransactionPlan plan, OrderTransition transition) {
        Throwable cause = TransientErrors.unwrap(error);
        if (cause instanceof TransactionCanceledException canceled && canceled.hasCancellationReasons()) {
            return mapCancellation(canceled, plan, transition);
        }
        return TransientErrors.isTransient(cause)
                ? new TransientFailureException("Transient DynamoDB failure", cause)
                : cause;
    }

    public static boolean hasTransactionConflict(Throwable error) {
        return TransientErrors.unwrap(error) instanceof TransactionCanceledException canceled
                && canceled.hasCancellationReasons()
                && canceled.cancellationReasons().stream()
                .anyMatch(reason -> TransientErrors.TRANSACTION_CONFLICT.equals(reason.code()));
    }

    private static Throwable mapCancellation(TransactionCanceledException canceled, TransactionPlan plan,
                                             OrderTransition transition) {
        List<CancellationReason> reasons = canceled.cancellationReasons();
        if (conditionFailed(reasons, plan, ItemRole.ORDER)) {
            return new OrderStateConflictException(transition.orderId(), transition.from());
        }
        if (conditionFailed(reasons, plan, ItemRole.EVENT)) {
            return new InventoryConditionFailedException(transition.eventId());
        }
        return TransientErrors.isTransientCancellation(reasons)
                ? new TransientFailureException("Transaction cancelled: " + codes(reasons), canceled)
                : canceled;
    }

    private static boolean conditionFailed(List<CancellationReason> reasons, TransactionPlan plan, ItemRole role) {
        return IntStream.range(0, Math.min(reasons.size(), plan.items().size()))
                .anyMatch(index -> plan.roleAt(index) == role
                        && TransientErrors.CONDITIONAL_CHECK_FAILED.equals(reasons.get(index).code()));
    }

    private static List<String> codes(List<CancellationReason> reasons) {
        return reasons.stream().map(CancellationReason::code).toList();
    }
}
