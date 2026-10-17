package com.dbonilla.ticketing.processor.paymentgateway;

import co.com.bancolombia.secretsmanager.api.GenericManagerAsync;
import co.com.bancolombia.secretsmanager.api.exceptions.SecretException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentGatewayCredentialsTest {

    private static final String SECRET_ID = "ticketing/payment-gateway";
    private final GenericManagerAsync secrets = mock(GenericManagerAsync.class);

    private PaymentGatewayCredentials credentials() {
        return new PaymentGatewayCredentials(secrets, SECRET_ID, JsonMapper.builder().build(), Duration.ofHours(1));
    }

    @Test
    void readsApiKeyOnceAndCachesIt() throws SecretException {
        // Arrange
        when(secrets.getSecret(SECRET_ID)).thenReturn(Mono.just("{\"apiKey\":\"super-secret\"}"));
        PaymentGatewayCredentials credentials = credentials();

        // Act & Assert
        StepVerifier.create(credentials.apiKey()).expectNext(new ApiKey("super-secret")).verifyComplete();
        StepVerifier.create(credentials.apiKey()).expectNextCount(1).verifyComplete();
        verify(secrets, times(1)).getSecret(SECRET_ID);
    }

    @Test
    void failuresAreNotCached() throws SecretException {
        AtomicInteger reads = new AtomicInteger();
        when(secrets.getSecret(SECRET_ID)).thenReturn(Mono.defer(() -> reads.incrementAndGet() == 1
                ? Mono.error(new IllegalStateException("not yet created"))
                : Mono.just("{\"apiKey\":\"k\"}")));
        PaymentGatewayCredentials credentials = credentials();

        StepVerifier.create(credentials.apiKey()).expectError(IllegalStateException.class).verify();
        StepVerifier.create(credentials.apiKey()).expectNext(new ApiKey("k")).verifyComplete();
    }

    @Test
    void rejectsSecretsWithoutApiKey() throws SecretException {
        when(secrets.getSecret(SECRET_ID)).thenReturn(Mono.just("{\"other\":\"x\"}"));

        StepVerifier.create(credentials().apiKey())
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("apiKey"))
                .verify();
    }

    @Test
    void rejectsBlankOrNonStringApiKey() throws SecretException {
        when(secrets.getSecret(SECRET_ID)).thenReturn(Mono.just("{\"apiKey\":\" \"}"), Mono.just("{\"apiKey\":42}"));

        StepVerifier.create(credentials().apiKey()).expectError(IllegalStateException.class).verify();
        StepVerifier.create(credentials().apiKey()).expectError(IllegalStateException.class).verify();
    }

    @Test
    void invalidJsonErrorNeverQuotesTheSecret() throws SecretException {
        when(secrets.getSecret(SECRET_ID)).thenReturn(Mono.just("not-json-super-secret"));

        StepVerifier.create(credentials().apiKey())
                .expectErrorSatisfies(error -> assertThat(error)
                        .hasMessageNotContaining("super-secret")
                        .hasNoCause())
                .verify();
    }

    @Test
    void secretManagerExceptionsAreSignalled() throws SecretException {
        when(secrets.getSecret(SECRET_ID)).thenThrow(new SecretException("denied"));

        StepVerifier.create(credentials().apiKey()).expectError(SecretException.class).verify();
    }

    @Test
    void warmUpAtStartupDoesNotFail() throws SecretException {
        when(secrets.getSecret(SECRET_ID)).thenReturn(Mono.just("{\"apiKey\":\"k\"}"), Mono.error(new IllegalStateException()));

        credentials().afterSingletonsInstantiated();
        credentials().afterSingletonsInstantiated();

        verify(secrets, times(2)).getSecret(SECRET_ID);
    }

    @Test
    void apiKeyNeverShowsItsValue() {
        assertThat(new ApiKey("super-secret")).hasToString("ApiKey[****]");
        assertThatThrownBy(() -> new ApiKey(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApiKey(null)).isInstanceOf(NullPointerException.class);
    }
}
