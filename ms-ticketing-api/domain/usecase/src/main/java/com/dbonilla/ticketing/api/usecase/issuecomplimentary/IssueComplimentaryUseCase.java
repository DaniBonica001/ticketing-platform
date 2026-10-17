package com.dbonilla.ticketing.api.usecase.issuecomplimentary;

import com.dbonilla.ticketing.api.model.event.gateways.EventRepository;
import com.dbonilla.ticketing.api.model.exception.EventNotFoundException;
import com.dbonilla.ticketing.api.model.order.Order;
import com.dbonilla.ticketing.api.model.order.OrderIdentity;
import com.dbonilla.ticketing.api.model.order.gateways.OrderRepository;
import com.dbonilla.ticketing.api.model.ordertransition.OrderTransition;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.temporal.ChronoUnit;

/**
 * T8: an admin moves tickets AVAILABLE to COMPLIMENTARY synchronously, idempotent per Idempotency-Key.
 */
public class IssueComplimentaryUseCase {

    private final EventRepository eventRepository;
    private final OrderRepository orderRepository;
    private final Clock clock;

    public IssueComplimentaryUseCase(EventRepository eventRepository, OrderRepository orderRepository, Clock clock) {
        this.eventRepository = eventRepository;
        this.orderRepository = orderRepository;
        this.clock = clock;
    }

    public Mono<Order> issue(IssueComplimentaryCommand command) {
        String requestHash = OrderIdentity.requestHash(command.canonicalPayload());
        return eventRepository.findById(command.eventId())
                .switchIfEmpty(Mono.error(() -> new EventNotFoundException(command.eventId())))
                .map(event -> newOrder(command, requestHash))
                .flatMap(order -> orderRepository.issueComplimentary(order, OrderTransition.created(order)))
                .map(result -> result.resolve(requestHash));
    }

    private Order newOrder(IssueComplimentaryCommand command, String requestHash) {
        String orderId = OrderIdentity.orderId(command.caller().userId(), command.idempotencyKey());
        return Order.complimentary(orderId, command.eventId(), command.recipientUserId(), command.quantity(),
                requestHash, clock.instant().truncatedTo(ChronoUnit.MILLIS));
    }
}
