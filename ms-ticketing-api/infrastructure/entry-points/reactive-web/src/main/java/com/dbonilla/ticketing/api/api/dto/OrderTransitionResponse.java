package com.dbonilla.ticketing.api.api.dto;

import com.dbonilla.ticketing.api.model.ordertransition.OrderTransition;

public record OrderTransitionResponse(String fromStatus, String toStatus, String reason, String actor,
                                      String occurredAt) {

    public static OrderTransitionResponse from(OrderTransition transition) {
        return new OrderTransitionResponse(
                transition.fromStatus() == null ? null : transition.fromStatus().name(),
                transition.toStatus().name(),
                transition.reason() == null ? null : transition.reason().name(),
                transition.actor(), Timestamps.format(transition.occurredAt()));
    }
}
