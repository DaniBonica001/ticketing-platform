package com.dbonilla.ticketing.api.model.ordertransition.gateways;

import com.dbonilla.ticketing.api.model.ordertransition.OrderTransition;
import reactor.core.publisher.Flux;

public interface OrderTransitionRepository {

    /** Audit trail of an order in chronological order. */
    Flux<OrderTransition> findByOrderId(String orderId);
}
