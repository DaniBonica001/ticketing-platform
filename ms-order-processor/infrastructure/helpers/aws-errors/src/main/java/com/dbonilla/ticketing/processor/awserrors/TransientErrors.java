package com.dbonilla.ticketing.processor.awserrors;

import com.dbonilla.ticketing.processor.model.exceptions.TransientFailureException;
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
 * The single predicate that decides whether an error may succeed when retried
 * (docs/design/error-handling-and-retries.md, section 2).
 */
public final class TransientErrors {

    public static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";
    public static final String TRANSACTION_CONFLICT = "TransactionConflict";

    private static final Set<String> TRANSIENT_CANCELLATION_CODES = Set.of(
            TRANSACTION_CONFLICT, "ThrottlingError", "ProvisionedThroughputExceeded", "RequestLimitExceeded");

    private static final Set<String> TRANSIENT_ERROR_CODES = Set.of(
            "ProvisionedThroughputExceededException", "ThrottlingException", "Throttling",
            "RequestLimitExceeded", "TransactionInProgressException", "InternalServerError",
            "ServiceUnavailable", "LimitExceededException");

    private static final int SERVER_ERROR_STATUS = 500;

    private TransientErrors() {
    }

    public static boolean isTransient(Throwable error) {
        Throwable cause = unwrap(error);
        return switch (cause) {
            case null -> false;
            case TransientFailureException ignored -> true;
            case TransactionCanceledException canceled -> isTransientCancellation(canceled.cancellationReasons());
            case AwsServiceException service -> isTransientServiceError(service);
            case SdkClientException ignored -> true;
            case IOException ignored -> true;
            case TimeoutException ignored -> true;
            default -> false;
        };
    }

    /**
     * Transient only when no item failed its condition and at least one failed for contention or throttling.
     */
    public static boolean isTransientCancellation(List<CancellationReason> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return false;
        }
        boolean anyConditionFailed = reasons.stream().anyMatch(reason -> CONDITIONAL_CHECK_FAILED.equals(reason.code()));
        boolean anyTransient = reasons.stream().anyMatch(reason -> TRANSIENT_CANCELLATION_CODES.contains(reason.code()));
        return !anyConditionFailed && anyTransient;
    }

    public static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean isTransientServiceError(AwsServiceException service) {
        if (service.isThrottlingException() || service.statusCode() >= SERVER_ERROR_STATUS) {
            return true;
        }
        return service.awsErrorDetails() != null
                && TRANSIENT_ERROR_CODES.contains(service.awsErrorDetails().errorCode());
    }
}
