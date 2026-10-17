package com.dbonilla.ticketing.processor.paymentgateway.config;

import co.com.bancolombia.secretsmanager.api.GenericManagerAsync;
import com.dbonilla.ticketing.processor.awserrors.TransientErrors;
import com.dbonilla.ticketing.processor.paymentgateway.PaymentGatewayCredentials;
import com.dbonilla.ticketing.processor.paymentgateway.SimulatedPaymentGateway;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

@Configuration
public class PaymentGatewayConfig {

    private static final String NAME = "payment-gateway";

    @Bean
    public PaymentGatewayCredentials paymentGatewayCredentials(
            GenericManagerAsync secretsManager,
            @Value("${adapters.payment-gateway.secret-id}") String secretId,
            @Value("${adapters.payment-gateway.secret-cache-ttl:PT1H}") Duration cacheTtl) {
        return new PaymentGatewayCredentials(secretsManager, secretId, JsonMapper.builder().build(), cacheTtl);
    }

    @Bean
    public TimeLimiter paymentGatewayTimeLimiter(@Value("${adapters.payment-gateway.timeout:PT2S}") Duration timeout) {
        return TimeLimiter.of(NAME, TimeLimiterConfig.custom().timeoutDuration(timeout).cancelRunningFuture(true).build());
    }

    @Bean
    public CircuitBreaker paymentGatewayCircuitBreaker(
            @Value("${adapters.payment-gateway.circuit-breaker.failure-rate-threshold:50}") float failureRate,
            @Value("${adapters.payment-gateway.circuit-breaker.sliding-window-size:10}") int windowSize,
            @Value("${adapters.payment-gateway.circuit-breaker.minimum-number-of-calls:5}") int minimumCalls,
            @Value("${adapters.payment-gateway.circuit-breaker.wait-duration-in-open-state:PT30S}") Duration openWait) {
        return CircuitBreaker.of(NAME, CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRate)
                .slidingWindowSize(windowSize)
                .minimumNumberOfCalls(minimumCalls)
                .waitDurationInOpenState(openWait)
                .permittedNumberOfCallsInHalfOpenState(1)
                .build());
    }

    @Bean
    public Retry paymentGatewayRetry(@Value("${adapters.payment-gateway.retry.max-attempts:3}") int maxAttempts,
                                     @Value("${adapters.payment-gateway.retry.wait-duration:PT0.2S}") Duration wait) {
        return Retry.of(NAME, RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(wait)
                .retryOnException(TransientErrors::isTransient)
                .build());
    }

    @Bean
    public SimulatedPaymentGateway paymentGateway(PaymentGatewayCredentials credentials, TimeLimiter timeLimiter,
                                                  CircuitBreaker circuitBreaker, Retry retry) {
        return new SimulatedPaymentGateway(credentials, timeLimiter, circuitBreaker, retry);
    }
}
