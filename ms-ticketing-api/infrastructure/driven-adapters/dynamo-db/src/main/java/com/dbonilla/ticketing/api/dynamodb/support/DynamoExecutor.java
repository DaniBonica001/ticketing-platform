package com.dbonilla.ticketing.api.dynamodb.support;

import com.dbonilla.ticketing.api.model.exception.ServiceUnavailableException;
import com.dbonilla.ticketing.api.resilience.TransientErrors;
import com.dbonilla.ticketing.api.resilience.TransientRetry;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Runs a non-blocking DynamoDB call with the transient-error retry policy. Transient errors that survive
 * the retries become {@link ServiceUnavailableException}; every other error is propagated unchanged.
 */
@Component
public class DynamoExecutor {

    static final String DEPENDENCY = "DynamoDB";

    private final TransientRetry retry;
    private final TransactionMetrics metrics;

    public DynamoExecutor(TransientRetry retry, TransactionMetrics metrics) {
        this.retry = retry;
        this.metrics = metrics;
    }

    public <T> Mono<T> execute(Supplier<CompletableFuture<T>> call) {
        return Mono.fromFuture(call)
                .doOnError(TransientErrors::isTransactionConflict, error -> metrics.transactionConflict())
                .retryWhen(retry.spec())
                .onErrorMap(TransientErrors::isTransient, error -> new ServiceUnavailableException(DEPENDENCY, error));
    }
}
