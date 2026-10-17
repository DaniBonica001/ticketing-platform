package com.dbonilla.ticketing.api.model.order.gateways;

import com.dbonilla.ticketing.api.model.order.Order;
import reactor.core.publisher.Mono;

/**
 * Publishes thin order messages to the processing queues.
 * <p>
 * Contract: a transient failure that survives the publisher's retries is signalled as
 * {@link com.dbonilla.ticketing.api.model.exception.ServiceUnavailableException}; callers may defer those to
 * reconciliation. Any other error is non-transient and must not be swallowed.
 */
public interface OrderMessagePublisher {

    /** OrderPlaced to the orders queue. */
    Mono<Void> publishOrderPlaced(Order order);

    /** OrderConfirmationRequested to the confirmations queue. */
    Mono<Void> publishConfirmationRequested(Order order);
}
