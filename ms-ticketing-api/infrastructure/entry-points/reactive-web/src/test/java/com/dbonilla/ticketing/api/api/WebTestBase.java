package com.dbonilla.ticketing.api.api;

import com.dbonilla.ticketing.api.api.config.BodySizeLimitFilter;
import com.dbonilla.ticketing.api.api.config.JacksonConfig;
import com.dbonilla.ticketing.api.api.config.SecurityHeadersConfig;
import com.dbonilla.ticketing.api.api.config.WebFluxCodecConfig;
import com.dbonilla.ticketing.api.api.error.GlobalErrorHandler;
import com.dbonilla.ticketing.api.api.error.ProblemWriter;
import com.dbonilla.ticketing.api.api.handler.AvailabilityHandler;
import com.dbonilla.ticketing.api.api.handler.EventHandler;
import com.dbonilla.ticketing.api.api.handler.OrderHandler;
import com.dbonilla.ticketing.api.api.ratelimit.RateLimitFilter;
import com.dbonilla.ticketing.api.api.security.SecurityConfig;
import com.dbonilla.ticketing.api.api.support.RequestValidator;
import com.dbonilla.ticketing.api.model.event.Event;
import com.dbonilla.ticketing.api.model.event.EventDetails;
import com.dbonilla.ticketing.api.model.event.Inventory;
import com.dbonilla.ticketing.api.model.order.Order;
import com.dbonilla.ticketing.api.model.order.OrderStatus;
import com.dbonilla.ticketing.api.usecase.confirmorder.ConfirmOrderUseCase;
import com.dbonilla.ticketing.api.usecase.getavailability.GetAvailabilityUseCase;
import com.dbonilla.ticketing.api.usecase.getorder.GetOrderUseCase;
import com.dbonilla.ticketing.api.usecase.getorderhistory.GetOrderHistoryUseCase;
import com.dbonilla.ticketing.api.usecase.issuecomplimentary.IssueComplimentaryUseCase;
import com.dbonilla.ticketing.api.usecase.manageevents.ManageEventsUseCase;
import com.dbonilla.ticketing.api.usecase.placeorder.PlaceOrderUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.time.OffsetDateTime;

@WebFluxTest
@ContextConfiguration(classes = {RouterRest.class, EventHandler.class, AvailabilityHandler.class, OrderHandler.class,
        RequestValidator.class, SecurityConfig.class, ProblemWriter.class, GlobalErrorHandler.class,
        RateLimitFilter.class, SecurityHeadersConfig.class, BodySizeLimitFilter.class, WebFluxCodecConfig.class,
        JacksonConfig.class})
@TestPropertySource(properties = {
        "ticketing.security.jwt.issuer=https://auth.ticketing.local",
        "ticketing.security.jwt.audience=ticketing-api",
        "ticketing.security.jwt.public-key-location=classpath:unused.pem",
        "ticketing.rate-limit.orders.requests-per-second=10000",
        "ticketing.rate-limit.orders.burst=10000",
        "ticketing.rate-limit.public-endpoints.requests-per-second=10000",
        "ticketing.rate-limit.public-endpoints.burst=10000"})
public abstract class WebTestBase {

    protected static final Instant NOW = Instant.parse("2026-10-16T10:00:00.123Z");
    protected static final String EVENT_ID = "8b0e4f1a-6c2d-4e9b-b1a3-5d7c9e0f2a64";
    protected static final String ORDER_ID = "3f2c9a54-1b7e-5d2a-8c4f-0e6b9d1a7c33";
    protected static final String KEY = "3b241101-e2bb-4255-8caf-4136c566a962";
    protected static final String USER_ID = "user-1";

    @Autowired
    private ApplicationContext context;

    protected WebTestClient client;

    @BeforeEach
    void bindClient() {
        client = WebTestClient.bindToApplicationContext(context)
                .apply(SecurityMockServerConfigurers.springSecurity())
                .configureClient()
                .build();
    }

    @MockitoBean
    protected ReactiveJwtDecoder jwtDecoder;
    @MockitoBean
    protected ManageEventsUseCase manageEvents;
    @MockitoBean
    protected GetAvailabilityUseCase availability;
    @MockitoBean
    protected PlaceOrderUseCase placeOrder;
    @MockitoBean
    protected GetOrderUseCase getOrder;
    @MockitoBean
    protected ConfirmOrderUseCase confirmOrder;
    @MockitoBean
    protected GetOrderHistoryUseCase orderHistory;
    @MockitoBean
    protected IssueComplimentaryUseCase complimentary;

    protected WebTestClient as(String subject, String... scopes) {
        SimpleGrantedAuthority[] authorities = new SimpleGrantedAuthority[scopes.length];
        for (int i = 0; i < scopes.length; i++) {
            authorities[i] = new SimpleGrantedAuthority("SCOPE_" + scopes[i]);
        }
        return client.mutateWith(SecurityMockServerConfigurers.mockJwt()
                .jwt(jwt -> jwt.subject(subject))
                .authorities(authorities));
    }

    protected WebTestClient user() {
        return as(USER_ID, "ticketing:user");
    }

    protected WebTestClient admin() {
        return as("admin-1", "ticketing:admin");
    }

    protected static Event event() {
        return new Event(EVENT_ID, new EventDetails("Concert", OffsetDateTime.parse("2026-12-20T20:00:00-05:00"),
                "Stadium"), new Inventory(5, 3, 1, 0, 1, 0), 3, NOW, NOW);
    }

    protected static Order order(OrderStatus status) {
        return new Order(ORDER_ID, EVENT_ID, USER_ID, 2, status, null, NOW.plusSeconds(600), "h", null, NOW, NOW);
    }
}
