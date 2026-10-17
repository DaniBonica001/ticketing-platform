package com.dbonilla.ticketing.expirer.usecase.reconcilestuckorders;

import com.dbonilla.ticketing.expirer.model.execution.ExecutionContext;
import com.dbonilla.ticketing.expirer.model.execution.OrderFailure;
import com.dbonilla.ticketing.expirer.model.message.OrderMessage;
import com.dbonilla.ticketing.expirer.model.message.gateways.OrderMessagePublisher;
import com.dbonilla.ticketing.expirer.model.order.Order;
import com.dbonilla.ticketing.expirer.model.order.gateways.OrderRepository;
import com.dbonilla.ticketing.expirer.usecase.shared.OverdueOrderScanner;
import com.dbonilla.ticketing.expirer.usecase.shared.ScanSettings;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;

/**
 * R1/R2: re-publishes QUEUED and PENDING_CONFIRMATION orders whose deadline passed (their message was
 * lost or not processed) and moves their deadline forward. Safe because consumers are idempotent.
 * The message is published before moving the deadline, so a crash in between only causes a duplicate.
 */
public class ReconcileStuckOrdersUseCase {

    static final String QUERY_OPERATION = "query";
    static final String REPUBLISH_OPERATION = "republish";

    private final OrderRepository repository;
    private final OrderMessagePublisher publisher;
    private final OverdueOrderScanner scanner;
    private final int concurrency;
    private final Duration reconciliationDeadline;

    public ReconcileStuckOrdersUseCase(OrderRepository repository, OrderMessagePublisher publisher,
                                       ScanSettings settings, Duration reconciliationDeadline) {
        this.repository = repository;
        this.publisher = publisher;
        this.scanner = new OverdueOrderScanner(repository, settings.pageSize());
        this.concurrency = settings.concurrency();
        this.reconciliationDeadline = Objects.requireNonNull(reconciliationDeadline, "deadline is required");
    }

    public Mono<ReconciliationResult> execute(ExecutionContext context) {
        return Flux.fromArray(ReconciliationTarget.values())
                .concatMap(target -> reconcile(target, context))
                .reduce(ReconciliationResult.EMPTY, ReconciliationResult::plus);
    }

    private Flux<ReconciliationResult> reconcile(ReconciliationTarget target, ExecutionContext context) {
        return scanner.scan(target.status(), context)
                .filter(order -> order.isOverdue(target.status(), context.now()))
                .flatMap(order -> republish(order, target, context), concurrency)
                .onErrorResume(error -> Flux.just(ReconciliationResult.failed(
                        OrderFailure.of(QUERY_OPERATION, target.status(), null, error))));
    }

    private Mono<ReconciliationResult> republish(Order order, ReconciliationTarget target, ExecutionContext context) {
        OrderMessage message = OrderMessage.republish(order, target.messageType(), context);
        return publisher.publish(message)
                .then(Mono.defer(() -> repository.postponeDeadline(
                        order, context.now().plus(reconciliationDeadline), context)))
                .thenReturn(ReconciliationResult.republished(target))
                .onErrorResume(error -> Mono.just(ReconciliationResult.failed(
                        OrderFailure.of(REPUBLISH_OPERATION, target.status(), order.orderId(), error))));
    }
}
