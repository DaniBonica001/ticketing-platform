package com.dbonilla.ticketing.api.api.dto;

import com.dbonilla.ticketing.api.model.order.Order;

public record OrderResponse(String orderId, String eventId, int quantity, String status, String reason,
                            String expiresAt, String createdAt, String updatedAt) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(order.orderId(), order.eventId(), order.quantity(), order.status().name(),
                order.reason() == null ? null : order.reason().name(), Timestamps.format(order.expiresAt()),
                Timestamps.format(order.createdAt()), Timestamps.format(order.updatedAt()));
    }
}
