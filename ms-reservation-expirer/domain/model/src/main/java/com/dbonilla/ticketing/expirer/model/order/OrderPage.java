package com.dbonilla.ticketing.expirer.model.order;

import java.util.List;
import java.util.Optional;

/**
 * A page of overdue orders.
 *
 * @param nextCursor cursor for the following page, {@code null} when this is the last page
 */
public record OrderPage(List<Order> orders, PageCursor nextCursor) {

    public OrderPage {
        orders = List.copyOf(orders);
    }

    public static OrderPage last(List<Order> orders) {
        return new OrderPage(orders, null);
    }

    public Optional<PageCursor> next() {
        return Optional.ofNullable(nextCursor);
    }
}
