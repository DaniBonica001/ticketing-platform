package com.dbonilla.ticketing.expirer.model.message.gateways;

import com.dbonilla.ticketing.expirer.model.message.OrderMessage;
import reactor.core.publisher.Mono;

public interface OrderMessagePublisher {

    /** Publishes the message to the queue that corresponds to its type. */
    Mono<Void> publish(OrderMessage message);
}
