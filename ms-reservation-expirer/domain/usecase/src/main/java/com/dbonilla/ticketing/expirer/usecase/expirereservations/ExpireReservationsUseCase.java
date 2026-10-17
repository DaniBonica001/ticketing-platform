package com.dbonilla.ticketing.expirer.usecase.expirereservations;

import com.dbonilla.ticketing.expirer.model.execution.ExecutionContext;
import com.dbonilla.ticketing.expirer.model.execution.OrderFailure;
import com.dbonilla.ticketing.expirer.model.order.ExpirationOutcome;
import com.dbonilla.ticketing.expirer.model.order.Order;
import com.dbonilla.ticketing.expirer.model.order.OrderStatus;
import com.dbonilla.ticketing.expirer.model.order.gateways.OrderRepository;
import com.dbonilla.ticketing.expirer.usecase.shared.OverdueOrderScanner;
import com.dbonilla.ticketing.expirer.usecase.shared.ScanSettings;
import reactor.core.publisher.Mono;

/**
 * T7: expires every RESERVED order whose deadline passed and returns its tickets to available.
 * A failure on one order is recorded and the run continues with the others.
 */
public class ExpireReservationsUseCase {

    static final String EXPIRE_OPERATION = "expire";
    static final String QUERY_OPERATION = "query";

    private final OrderRepository repository;
    private final OverdueOrderScanner scanner;
    private final int concurrency;

    public ExpireReservationsUseCase(OrderRepository repository, ScanSettings settings) {
        this.repository = repository;
        this.scanner = new OverdueOrderScanner(repository, settings.pageSize());
        this.concurrency = settings.concurrency();
    }

    public Mono<ExpirationResult> execute(ExecutionContext context) {
        return scanner.scan(OrderStatus.RESERVED, context)
                .flatMap(order -> expire(order, context), concurrency)
                .onErrorResume(error -> Mono.just(ExpirationResult.failed(
                        OrderFailure.of(QUERY_OPERATION, OrderStatus.RESERVED, null, error))))
                .reduce(ExpirationResult.EMPTY, ExpirationResult::plus);
    }

    private Mono<ExpirationResult> expire(Order order, ExecutionContext context) {
        if (!order.isOverdue(OrderStatus.RESERVED, context.now())) {
            return Mono.just(ExpirationResult.oneSkipped());
        }
        return repository.expire(order, context)
                .map(outcome -> outcome == ExpirationOutcome.EXPIRED
                        ? ExpirationResult.oneExpired()
                        : ExpirationResult.oneSkipped())
                .onErrorResume(error -> Mono.just(ExpirationResult.failed(
                        OrderFailure.of(EXPIRE_OPERATION, OrderStatus.RESERVED, order.orderId(), error))));
    }
}
