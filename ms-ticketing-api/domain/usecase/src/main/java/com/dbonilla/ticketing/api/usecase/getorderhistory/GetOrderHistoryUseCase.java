package com.dbonilla.ticketing.api.usecase.getorderhistory;

import com.dbonilla.ticketing.api.model.exception.OrderNotFoundException;
import com.dbonilla.ticketing.api.model.order.Caller;
import com.dbonilla.ticketing.api.model.order.gateways.OrderRepository;
import com.dbonilla.ticketing.api.model.ordertransition.OrderTransition;
import com.dbonilla.ticketing.api.model.ordertransition.gateways.OrderTransitionRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Audit trail of an order, visible to its owner or an admin.
 */
public class GetOrderHistoryUseCase {

    private final OrderRepository orderRepository;
    private final OrderTransitionRepository transitionRepository;

    public GetOrderHistoryUseCase(OrderRepository orderRepository, OrderTransitionRepository transitionRepository) {
        this.orderRepository = orderRepository;
        this.transitionRepository = transitionRepository;
    }

    public Flux<OrderTransition> history(String orderId, Caller caller) {
        return orderRepository.findById(orderId)
                .filter(caller::canRead)
                .switchIfEmpty(Mono.error(() -> new OrderNotFoundException(orderId)))
                .flatMapMany(order -> transitionRepository.findByOrderId(order.orderId()));
    }
}
