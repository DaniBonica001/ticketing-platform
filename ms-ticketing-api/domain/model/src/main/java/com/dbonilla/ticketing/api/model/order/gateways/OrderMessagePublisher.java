package com.dbonilla.ticketing.api.model.order.gateways;

import com.dbonilla.ticketing.api.model.order.Order;
import reactor.core.publisher.Mono;

/**
 * Publishes thin order messages to the processing queues.
 */
public interface OrderMessagePublisher {

    /** OrderPlaced to the orders queue. */
    Mono<Void> publishOrderPlaced(Order order);

    /** OrderConfirmationRequested to the confirmations queue. */
    Mono<Void> publishConfirmationRequested(Order order);
}
