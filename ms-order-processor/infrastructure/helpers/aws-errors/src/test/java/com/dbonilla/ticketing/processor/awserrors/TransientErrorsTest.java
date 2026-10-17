package com.dbonilla.ticketing.processor.awserrors;

import com.dbonilla.ticketing.processor.model.exceptions.TransientFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class TransientErrorsTest {

    private static CancellationReason reason(String code) {
        return CancellationReason.builder().code(code).build();
    }

    private static TransactionCanceledException canceled(String... codes) {
        return TransactionCanceledException.builder()
                .cancellationReasons(java.util.Arrays.stream(codes).map(TransientErrorsTest::reason).toList())
                .build();
    }

    private static AwsServiceException serviceError(int status, String code) {
        return AwsServiceException.builder()
                .statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build())
                .build();
    }

    @Test
    void transactionConflictIsTransient() {
        assertThat(TransientErrors.isTransient(canceled("None", "TransactionConflict", "None"))).isTrue();
    }

    @Test
    void throttledTransactionIsTransient() {
        assertThat(TransientErrors.isTransient(canceled("ThrottlingError", "None"))).isTrue();
    }

    @Test
    void conditionalCheckFailureIsNeverTransient() {
        assertThat(TransientErrors.isTransient(canceled("ConditionalCheckFailed", "TransactionConflict"))).isFalse();
        assertThat(TransientErrors.isTransient(canceled("None", "ConditionalCheckFailed"))).isFalse();
    }

    @Test
    void cancellationWithoutReasonsOrWithValidationErrorIsNotTransient() {
        assertThat(TransientErrors.isTransientCancellation(null)).isFalse();
        assertThat(TransientErrors.isTransientCancellation(List.of())).isFalse();
        assertThat(TransientErrors.isTransient(canceled("ValidationError"))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ThrottlingException", "RequestLimitExceeded", "TransactionInProgressException"})
    void throttlingErrorCodesAreTransient(String code) {
        assertThat(TransientErrors.isTransient(serviceError(400, code))).isTrue();
    }

    @Test
    void serverErrorsAreTransientAndClientErrorsAreNot() {
        assertThat(TransientErrors.isTransient(serviceError(503, "Whatever"))).isTrue();
        assertThat(TransientErrors.isTransient(serviceError(400, "ValidationException"))).isFalse();
        assertThat(TransientErrors.isTransient(AwsServiceException.builder().statusCode(400).build())).isFalse();
        assertThat(TransientErrors.isTransient(ConditionalCheckFailedException.builder().statusCode(400).build()))
                .isFalse();
    }

    @Test
    void provisionedThroughputExceededIsTransient() {
        assertThat(TransientErrors.isTransient(ProvisionedThroughputExceededException.builder()
                .statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("ProvisionedThroughputExceededException").build())
                .build())).isTrue();
    }

    @Test
    void networkAndTimeoutErrorsAreTransient() {
        assertThat(TransientErrors.isTransient(SdkClientException.create("connection reset"))).isTrue();
        assertThat(TransientErrors.isTransient(ApiCallTimeoutException.create(1000))).isTrue();
        assertThat(TransientErrors.isTransient(new IOException("broken pipe"))).isTrue();
        assertThat(TransientErrors.isTransient(new TimeoutException("slow"))).isTrue();
        assertThat(TransientErrors.isTransient(new TransientFailureException("retry", null))).isTrue();
    }

    @Test
    void wrappedErrorsAreUnwrapped() {
        assertThat(TransientErrors.isTransient(new CompletionException(new IOException("x")))).isTrue();
        assertThat(TransientErrors.isTransient(new ExecutionException(new IllegalStateException("x")))).isFalse();
        assertThat(TransientErrors.unwrap(new CompletionException(null))).isInstanceOf(CompletionException.class);
    }

    @Test
    void otherErrorsAreNotTransient() {
        assertThat(TransientErrors.isTransient(new IllegalArgumentException("bug"))).isFalse();
        assertThat(TransientErrors.isTransient(null)).isFalse();
    }
}
