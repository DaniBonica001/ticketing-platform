package com.dbonilla.ticketing.api.model.event;

import java.util.List;

/**
 * One page of events plus the opaque cursor of the next page ({@code null} on the last page).
 */
public record EventPage(List<Event> items, String nextCursor) {

    public EventPage {
        items = List.copyOf(items);
    }
}
