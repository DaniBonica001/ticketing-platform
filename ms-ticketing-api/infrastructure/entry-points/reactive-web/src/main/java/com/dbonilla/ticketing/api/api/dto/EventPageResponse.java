package com.dbonilla.ticketing.api.api.dto;

import com.dbonilla.ticketing.api.model.event.EventPage;

import java.util.List;

public record EventPageResponse(List<EventResponse> items, String nextCursor) {

    public static EventPageResponse from(EventPage page) {
        return new EventPageResponse(page.items().stream().map(EventResponse::from).toList(), page.nextCursor());
    }
}
