package com.dbonilla.ticketing.api.model.order;

import java.util.Objects;

/**
 * Authenticated principal performing a request.
 *
 * @param userId the {@code sub} claim of the token
 * @param admin  whether the caller holds the admin scope
 */
public record Caller(String userId, boolean admin) {

    public Caller {
        Objects.requireNonNull(userId, "userId");
    }

    public boolean owns(Order order) {
        return userId.equals(order.userId());
    }

    public boolean canRead(Order order) {
        return admin || owns(order);
    }
}
