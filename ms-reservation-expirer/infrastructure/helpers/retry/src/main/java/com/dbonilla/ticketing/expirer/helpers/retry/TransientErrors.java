package com.dbonilla.ticketing.expirer.helpers.retry;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * The single predicate that decides whether a DynamoDB/SQS error may succeed when retried
 * (docs/design/error-handling-and-retries.md). Conditional check failures are business outcomes and are
 * never transient.
 */
public final class TransientErrors {

    static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";
    private static final Set<String> TRANSIENT_CANCELLATION_CODES = Set.of(
            "TransactionConflict", "ThrottlingError", "ProvisionedThroughputExceeded", "RequestLimitExceeded");
    private static final int SERVER_ERROR_STATUS = 500;

    private TransientErrors() {
    }

    public static boolean isTransient(Throwable error) {
        return switch (unwrap(error)) {
            case TransactionCanceledException cancelled -> isTransientCancellation(cancelled.cancellationReasons());
            case ConditionalCheckFailedException ignored -> false;
            case ProvisionedThroughputExceededException ignored -> true;
            case RequestLimitExceededException ignored -> true;
            case AwsServiceException service -> service.isThrottlingException()
                    || service.statusCode() >= SERVER_ERROR_STATUS;
            case SdkClientException ignored -> true;
            case IOException ignored -> true;
            case TimeoutException ignored -> true;
            default -> false;
        };
    }

    private static boolean isTransientCancellation(List<CancellationReason> reasons) {
        boolean hasConditionFailure = reasons.stream()
                .anyMatch(reason -> CONDITIONAL_CHECK_FAILED.equals(reason.code()));
        return !hasConditionFailure && reasons.stream()
                .anyMatch(reason -> TRANSIENT_CANCELLATION_CODES.contains(reason.code()));
    }

    static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
