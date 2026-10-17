package com.dbonilla.ticketing.expirer.usecase.shared;

import com.dbonilla.ticketing.expirer.model.execution.ExecutionContext;
import com.dbonilla.ticketing.expirer.model.order.Order;
import com.dbonilla.ticketing.expirer.model.order.OrderPage;
import com.dbonilla.ticketing.expirer.model.order.OrderStatus;
import com.dbonilla.ticketing.expirer.model.order.OverdueOrdersQuery;
import com.dbonilla.ticketing.expirer.model.order.gateways.OrderRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Streams every overdue order of a status, page by page, and stops early when the time budget runs out.
 */
public final class OverdueOrderScanner {

    private final OrderRepository repository;
    private final int pageSize;

    public OverdueOrderScanner(OrderRepository repository, int pageSize) {
        this.repository = repository;
        this.pageSize = pageSize;
    }

    public Flux<Order> scan(OrderStatus status, ExecutionContext context) {
        OverdueOrdersQuery firstPage = OverdueOrdersQuery.firstPage(status, context.now(), pageSize);
        return fetch(firstPage, context)
                .expand(page -> page.next()
                        .map(cursor -> fetch(firstPage.nextPage(cursor), context))
                        .orElseGet(Mono::empty))
                .concatMapIterable(OrderPage::orders)
                .takeWhile(order -> !context.isOutOfTime());
    }

    private Mono<OrderPage> fetch(OverdueOrdersQuery query, ExecutionContext context) {
        return Mono.defer(() -> context.isOutOfTime() ? Mono.empty() : repository.findOverdue(query));
    }
}
