package com.dbonilla.ticketing.api.usecase.getorder;

import com.dbonilla.ticketing.api.model.exception.OrderNotFoundException;
import com.dbonilla.ticketing.api.model.order.Caller;
import com.dbonilla.ticketing.api.model.order.Order;
import com.dbonilla.ticketing.api.model.order.gateways.OrderRepository;
import reactor.core.publisher.Mono;

/**
 * Reads an order. Orders owned by someone else are reported as not found (unless the caller is admin).
 */
public class GetOrderUseCase {

    private final OrderRepository orderRepository;

    public GetOrderUseCase(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public Mono<Order> get(String orderId, Caller caller) {
        return orderRepository.findById(orderId)
                .filter(caller::canRead)
                .switchIfEmpty(Mono.error(() -> new OrderNotFoundException(orderId)));
    }
}
