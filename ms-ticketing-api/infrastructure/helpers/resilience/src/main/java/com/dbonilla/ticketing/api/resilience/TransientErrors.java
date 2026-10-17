package com.dbonilla.ticketing.api.resilience;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * The single "is this error worth retrying?" predicate of the service (error-handling-and-retries.md).
 * Business and conditional failures are never transient.
 */
public final class TransientErrors {

    public static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";
    public static final String TRANSACTION_CONFLICT = "TransactionConflict";

    private static final Set<String> TRANSIENT_CANCELLATION_CODES = Set.of(
            TRANSACTION_CONFLICT, "ThrottlingError", "ProvisionedThroughputExceeded", "RequestLimitExceeded");
    private static final Set<String> TRANSIENT_ERROR_CODES = Set.of(
            "ProvisionedThroughputExceededException", "ThrottlingException", "RequestLimitExceeded",
            "Throttling", "RequestThrottled", "ServiceUnavailable", "InternalServerError");
    private static final int SERVER_ERROR = 500;

    private TransientErrors() {
    }

    public static boolean isTransient(Throwable error) {
        return switch (unwrap(error)) {
            case TransactionCanceledException canceled -> isTransientCancellation(canceled);
            case AwsServiceException service -> isTransientService(service);
            case SdkClientException ignored -> true;
            case IOException ignored -> true;
            case TimeoutException ignored -> true;
            case null, default -> false;
        };
    }

    /** True when a transaction was canceled because of contention on at least one item. */
    public static boolean isTransactionConflict(Throwable error) {
        return unwrap(error) instanceof TransactionCanceledException canceled
                && reasonCodes(canceled).contains(TRANSACTION_CONFLICT);
    }

    private static boolean isTransientCancellation(TransactionCanceledException canceled) {
        List<String> codes = reasonCodes(canceled);
        return !codes.contains(CONDITIONAL_CHECK_FAILED)
                && codes.stream().anyMatch(TRANSIENT_CANCELLATION_CODES::contains);
    }

    private static boolean isTransientService(AwsServiceException service) {
        String errorCode = service.awsErrorDetails() == null ? null : service.awsErrorDetails().errorCode();
        return service.isThrottlingException()
                || service.statusCode() >= SERVER_ERROR
                || (errorCode != null && TRANSIENT_ERROR_CODES.contains(errorCode));
    }

    private static List<String> reasonCodes(TransactionCanceledException canceled) {
        if (!canceled.hasCancellationReasons()) {
            return List.of();
        }
        return canceled.cancellationReasons().stream().map(CancellationReason::code).toList();
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
