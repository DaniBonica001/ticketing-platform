package com.dbonilla.ticketing.expirer.model.order;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One page request: orders in {@code status} whose deadline is strictly before {@code dueBefore}.
 *
 * @param startAfter cursor of the previous page, {@code null} for the first page
 */
public record OverdueOrdersQuery(OrderStatus status, Instant dueBefore, int limit, PageCursor startAfter) {

    public OverdueOrdersQuery {
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(dueBefore, "dueBefore is required");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    public static OverdueOrdersQuery firstPage(OrderStatus status, Instant dueBefore, int limit) {
        return new OverdueOrdersQuery(status, dueBefore, limit, null);
    }

    public OverdueOrdersQuery nextPage(PageCursor cursor) {
        return new OverdueOrdersQuery(status, dueBefore, limit, Objects.requireNonNull(cursor, "cursor is required"));
    }

    public Optional<PageCursor> exclusiveStart() {
        return Optional.ofNullable(startAfter);
    }
}
