package com.dbonilla.ticketing.api.resilience;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class TransientErrorsTest {

    static TransactionCanceledException canceled(String... codes) {
        CancellationReason[] reasons = new CancellationReason[codes.length];
        for (int i = 0; i < codes.length; i++) {
            reasons[i] = CancellationReason.builder().code(codes[i]).build();
        }
        return TransactionCanceledException.builder().cancellationReasons(reasons).message("canceled").build();
    }

    private static AwsServiceException service(int status, String code) {
        return AwsServiceException.builder()
                .statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build())
                .build();
    }

    @Test
    void transactionConflictIsTransient() {
        assertThat(TransientErrors.isTransient(canceled("None", "TransactionConflict", "None"))).isTrue();
        assertThat(TransientErrors.isTransient(canceled("ThrottlingError"))).isTrue();
        assertThat(TransientErrors.isTransactionConflict(canceled("None", "TransactionConflict"))).isTrue();
    }

    @Test
    void conditionalFailuresAreNeverTransient() {
        assertThat(TransientErrors.isTransient(canceled("ConditionalCheckFailed", "None"))).isFalse();
        assertThat(TransientErrors.isTransient(canceled("ConditionalCheckFailed", "TransactionConflict"))).isFalse();
        assertThat(TransientErrors.isTransient(canceled("ValidationError"))).isFalse();
        assertThat(TransientErrors.isTransient(TransactionCanceledException.builder().message("x").build())).isFalse();
        assertThat(TransientErrors.isTransient(ConditionalCheckFailedException.builder().statusCode(400).build()))
                .isFalse();
        assertThat(TransientErrors.isTransactionConflict(canceled("ConditionalCheckFailed"))).isFalse();
        assertThat(TransientErrors.isTransactionConflict(new IllegalStateException())).isFalse();
    }

    @Test
    void throttlingAndServerErrorsAreTransient() {
        assertThat(TransientErrors.isTransient(service(500, "InternalServerError"))).isTrue();
        assertThat(TransientErrors.isTransient(service(503, null))).isTrue();
        assertThat(TransientErrors.isTransient(service(400, "ThrottlingException"))).isTrue();
        assertThat(TransientErrors.isTransient(service(400, "RequestLimitExceeded"))).isTrue();
        assertThat(TransientErrors.isTransient(ProvisionedThroughputExceededException.builder()
                .statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("ProvisionedThroughputExceededException").build())
                .build())).isTrue();
        assertThat(TransientErrors.isTransient(AwsServiceException.builder().statusCode(400).build())).isFalse();
        assertThat(TransientErrors.isTransient(service(400, "ValidationException"))).isFalse();
    }

    @Test
    void networkAndTimeoutErrorsAreTransient() {
        assertThat(TransientErrors.isTransient(SdkClientException.create("connection reset"))).isTrue();
        assertThat(TransientErrors.isTransient(ApiCallTimeoutException.create(1000))).isTrue();
        assertThat(TransientErrors.isTransient(new IOException("io"))).isTrue();
        assertThat(TransientErrors.isTransient(new TimeoutException())).isTrue();
    }

    @Test
    void wrappedErrorsAreUnwrapped() {
        assertThat(TransientErrors.isTransient(new CompletionException(new IOException("io")))).isTrue();
        assertThat(TransientErrors.isTransient(new ExecutionException(canceled("TransactionConflict")))).isTrue();
        assertThat(TransientErrors.isTransient(new CompletionException(null))).isFalse();
    }

    @Test
    void businessAndUnknownErrorsAreNotTransient() {
        assertThat(TransientErrors.isTransient(new IllegalArgumentException())).isFalse();
        assertThat(TransientErrors.isTransient(null)).isFalse();
    }
}
