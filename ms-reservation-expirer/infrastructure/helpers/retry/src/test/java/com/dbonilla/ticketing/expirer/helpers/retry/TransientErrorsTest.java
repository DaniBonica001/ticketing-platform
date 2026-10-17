package com.dbonilla.ticketing.expirer.helpers.retry;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class TransientErrorsTest {

    static TransactionCanceledException cancelled(String... codes) {
        CancellationReason[] reasons = java.util.Arrays.stream(codes)
                .map(code -> CancellationReason.builder().code(code).build())
                .toArray(CancellationReason[]::new);
        return TransactionCanceledException.builder().message("cancelled").cancellationReasons(reasons).build();
    }

    private static AwsServiceException service(int status, String code) {
        return AwsServiceException.builder()
                .statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build())
                .build();
    }

    @Test
    void transactionConflictsAndThrottlingAreTransient() {
        assertThat(TransientErrors.isTransient(cancelled("None", "TransactionConflict", "None"))).isTrue();
        assertThat(TransientErrors.isTransient(cancelled("ThrottlingError"))).isTrue();
    }

    @Test
    void conditionalFailuresAreNeverTransient() {
        assertThat(TransientErrors.isTransient(cancelled("ConditionalCheckFailed", "None", "None"))).isFalse();
        assertThat(TransientErrors.isTransient(cancelled("ConditionalCheckFailed", "TransactionConflict"))).isFalse();
        assertThat(TransientErrors.isTransient(ConditionalCheckFailedException.builder().message("x").build())).isFalse();
    }

    @Test
    void cancellationWithoutTransientReasonIsNotTransient() {
        assertThat(TransientErrors.isTransient(cancelled("ValidationError"))).isFalse();
        assertThat(TransientErrors.isTransient(cancelled())).isFalse();
    }

    @Test
    void capacityAndServerErrorsAreTransient() {
        assertThat(TransientErrors.isTransient(ProvisionedThroughputExceededException.builder().message("x").build()))
                .isTrue();
        assertThat(TransientErrors.isTransient(RequestLimitExceededException.builder().message("x").build())).isTrue();
        assertThat(TransientErrors.isTransient(service(500, "InternalServerError"))).isTrue();
        assertThat(TransientErrors.isTransient(service(400, "ThrottlingException"))).isTrue();
        assertThat(TransientErrors.isTransient(service(400, "ValidationException"))).isFalse();
    }

    @Test
    void networkErrorsAreTransient() {
        assertThat(TransientErrors.isTransient(SdkClientException.create("connection reset"))).isTrue();
        assertThat(TransientErrors.isTransient(ApiCallTimeoutException.create(1000))).isTrue();
        assertThat(TransientErrors.isTransient(new IOException("io"))).isTrue();
        assertThat(TransientErrors.isTransient(new TimeoutException("timeout"))).isTrue();
    }

    @Test
    void unwrapsAsyncWrappers() {
        assertThat(TransientErrors.isTransient(new CompletionException(new IOException("io")))).isTrue();
        assertThat(TransientErrors.isTransient(new ExecutionException(new IllegalStateException("bug")))).isFalse();
        CompletionException withoutCause = new CompletionException("no cause", null);
        assertThat(TransientErrors.unwrap(withoutCause)).isSameAs(withoutCause);
    }

    @Test
    void programmingErrorsAreNotTransient() {
        assertThat(TransientErrors.isTransient(new IllegalArgumentException("bad"))).isFalse();
    }
}
