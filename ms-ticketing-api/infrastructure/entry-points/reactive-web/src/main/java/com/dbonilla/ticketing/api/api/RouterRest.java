package com.dbonilla.ticketing.api.api;

import com.dbonilla.ticketing.api.api.handler.AvailabilityHandler;
import com.dbonilla.ticketing.api.api.handler.EventHandler;
import com.dbonilla.ticketing.api.api.handler.OrderHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RequestPredicates.PUT;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

/**
 * Routes of contracts/openapi/ticketing-api.yaml.
 */
@Configuration
public class RouterRest {

    static final String EVENTS = "/api/v1/events";
    static final String EVENT = EVENTS + "/{eventId}";
    static final String ORDERS = "/api/v1/orders";
    static final String ORDER = ORDERS + "/{orderId}";

    @Bean
    public RouterFunction<ServerResponse> routerFunction(EventHandler events, AvailabilityHandler availability,
                                                         OrderHandler orders) {
        return route(GET(EVENTS), events::list)
                .andRoute(POST(EVENTS), events::create)
                .andRoute(GET(EVENT), events::get)
                .andRoute(PUT(EVENT), events::update)
                .andRoute(GET(EVENT + "/availability"), availability::get)
                .andRoute(GET(EVENT + "/availability/stream"), availability::stream)
                .andRoute(POST(EVENT + "/complimentary"), orders::complimentary)
                .andRoute(POST(ORDERS), orders::place)
                .andRoute(GET(ORDER), orders::get)
                .andRoute(POST(ORDER + "/confirm"), orders::confirm)
                .andRoute(GET(ORDER + "/history"), orders::history);
    }
}
