package com.dbonilla.ticketing.api.usecase.placeorder;

import com.dbonilla.ticketing.api.model.event.gateways.EventRepository;
import com.dbonilla.ticketing.api.model.exception.EventNotFoundException;
import com.dbonilla.ticketing.api.model.exception.ServiceUnavailableException;
import com.dbonilla.ticketing.api.model.order.Order;
import com.dbonilla.ticketing.api.model.order.OrderIdentity;
import com.dbonilla.ticketing.api.model.order.OrderPolicy;
import com.dbonilla.ticketing.api.model.order.OrderWriteResult;
import com.dbonilla.ticketing.api.model.order.gateways.OrderMessagePublisher;
import com.dbonilla.ticketing.api.model.order.gateways.OrderRepository;
import com.dbonilla.ticketing.api.model.ordertransition.OrderTransition;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.temporal.ChronoUnit;

/**
 * T1: accepts a purchase as QUEUED (idempotent per user and Idempotency-Key) and enqueues it.
 */
public class PlaceOrderUseCase {

    private final EventRepository eventRepository;
    private final OrderRepository orderRepository;
    private final OrderMessagePublisher publisher;
    private final Clock clock;
    private final OrderPolicy policy;

    public PlaceOrderUseCase(EventRepository eventRepository, OrderRepository orderRepository,
                             OrderMessagePublisher publisher, Clock clock, OrderPolicy policy) {
        this.eventRepository = eventRepository;
        this.orderRepository = orderRepository;
        this.publisher = publisher;
        this.clock = clock;
        this.policy = policy;
    }

    public Mono<Order> place(PlaceOrderCommand command) {
        String requestHash = OrderIdentity.requestHash(command.canonicalPayload());
        return eventRepository.findById(command.eventId())
                .switchIfEmpty(Mono.error(() -> new EventNotFoundException(command.eventId())))
                .map(event -> newOrder(command, requestHash))
                .flatMap(order -> orderRepository.place(order, OrderTransition.created(order)))
                .flatMap(result -> switch (result) {
                    case OrderWriteResult.Created(Order order) -> enqueue(order);
                    case OrderWriteResult.AlreadyExists existing -> Mono.fromCallable(() -> existing.resolve(requestHash));
                });
    }

    private Order newOrder(PlaceOrderCommand command, String requestHash) {
        String orderId = OrderIdentity.orderId(command.caller().userId(), command.idempotencyKey());
        return Order.place(orderId, command.eventId(), command.caller().userId(), command.quantity(),
                requestHash, clock.instant().truncatedTo(ChronoUnit.MILLIS), policy);
    }

    /**
     * The order is already durable as QUEUED: if the queue stays unavailable after the retries
     * ({@link ServiceUnavailableException}) the reservation expirer re-publishes it once its deadline passes
     * (ADR-0005, R1), so the client still gets 202. Any other failure is unexpected and propagates.
     */
    private Mono<Order> enqueue(Order order) {
        return publisher.publishOrderPlaced(order)
                .onErrorResume(ServiceUnavailableException.class, unavailable -> Mono.empty())
                .thenReturn(order);
    }
}
