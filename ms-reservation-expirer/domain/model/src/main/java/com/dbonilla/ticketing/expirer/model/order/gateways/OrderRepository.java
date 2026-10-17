package com.dbonilla.ticketing.expirer.model.order.gateways;

import com.dbonilla.ticketing.expirer.model.execution.ExecutionContext;
import com.dbonilla.ticketing.expirer.model.order.DeadlineUpdate;
import com.dbonilla.ticketing.expirer.model.order.ExpirationOutcome;
import com.dbonilla.ticketing.expirer.model.order.Order;
import com.dbonilla.ticketing.expirer.model.order.OrderPage;
import com.dbonilla.ticketing.expirer.model.order.OverdueOrdersQuery;
import reactor.core.publisher.Mono;

import java.time.Instant;

public interface OrderRepository {

    /** Reads one page of orders in a status whose deadline already passed. */
    Mono<OrderPage> findOverdue(OverdueOrdersQuery query);

    /** Applies T7 atomically: order RESERVED to EXPIRED, reserved to available, audit row. */
    Mono<ExpirationOutcome> expire(Order order, ExecutionContext context);

    /** Moves the deadline of a stuck order, only if it is still in the same status. */
    Mono<DeadlineUpdate> postponeDeadline(Order order, Instant newDeadline, ExecutionContext context);
}
