package com.dbonilla.ticketing.api.api.handler;

import com.dbonilla.ticketing.api.api.dto.ComplimentaryRequest;
import com.dbonilla.ticketing.api.api.dto.ConfirmOrderRequest;
import com.dbonilla.ticketing.api.api.dto.OrderResponse;
import com.dbonilla.ticketing.api.api.dto.OrderTransitionResponse;
import com.dbonilla.ticketing.api.api.dto.PlaceOrderRequest;
import com.dbonilla.ticketing.api.api.support.CallerResolver;
import com.dbonilla.ticketing.api.api.support.RequestParams;
import com.dbonilla.ticketing.api.api.support.RequestValidator;
import com.dbonilla.ticketing.api.model.order.Order;
import com.dbonilla.ticketing.api.usecase.confirmorder.ConfirmOrderCommand;
import com.dbonilla.ticketing.api.usecase.confirmorder.ConfirmOrderUseCase;
import com.dbonilla.ticketing.api.usecase.getorder.GetOrderUseCase;
import com.dbonilla.ticketing.api.usecase.getorderhistory.GetOrderHistoryUseCase;
import com.dbonilla.ticketing.api.usecase.issuecomplimentary.IssueComplimentaryCommand;
import com.dbonilla.ticketing.api.usecase.issuecomplimentary.IssueComplimentaryUseCase;
import com.dbonilla.ticketing.api.usecase.placeorder.PlaceOrderCommand;
import com.dbonilla.ticketing.api.usecase.placeorder.PlaceOrderUseCase;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.net.URI;

@Component
public class OrderHandler {

    static final String ORDERS_PATH = "/api/v1/orders/";

    private final PlaceOrderUseCase placeOrder;
    private final GetOrderUseCase getOrder;
    private final ConfirmOrderUseCase confirmOrder;
    private final GetOrderHistoryUseCase orderHistory;
    private final IssueComplimentaryUseCase complimentary;
    private final RequestValidator validator;

    public OrderHandler(PlaceOrderUseCase placeOrder, GetOrderUseCase getOrder, ConfirmOrderUseCase confirmOrder,
                        GetOrderHistoryUseCase orderHistory, IssueComplimentaryUseCase complimentary,
                        RequestValidator validator) {
        this.placeOrder = placeOrder;
        this.getOrder = getOrder;
        this.confirmOrder = confirmOrder;
        this.orderHistory = orderHistory;
        this.complimentary = complimentary;
        this.validator = validator;
    }

    public Mono<ServerResponse> place(ServerRequest request) {
        return Mono.fromCallable(() -> RequestParams.idempotencyKey(request))
                .flatMap(key -> validator.body(request, PlaceOrderRequest.class)
                        .zipWith(CallerResolver.resolve(request))
                        .map(input -> new PlaceOrderCommand(input.getT2(), key,
                                RequestParams.canonicalUuid("eventId", input.getT1().eventId()),
                                input.getT1().quantity())))
                .flatMap(placeOrder::place)
                .flatMap(order -> ServerResponse.accepted()
                        .location(location(order))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(OrderResponse.from(order)));
    }

    public Mono<ServerResponse> get(ServerRequest request) {
        return Mono.fromCallable(() -> RequestParams.uuidPath(request, "orderId"))
                .flatMap(orderId -> CallerResolver.resolve(request).flatMap(caller -> getOrder.get(orderId, caller)))
                .flatMap(order -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(OrderResponse.from(order)));
    }

    public Mono<ServerResponse> confirm(ServerRequest request) {
        return Mono.fromCallable(() -> RequestParams.uuidPath(request, "orderId"))
                .flatMap(orderId -> validator.body(request, ConfirmOrderRequest.class)
                        .zipWith(CallerResolver.resolve(request))
                        .map(input -> new ConfirmOrderCommand(input.getT2(), orderId, input.getT1().paymentReference())))
                .flatMap(confirmOrder::confirm)
                .flatMap(order -> ServerResponse.accepted().contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(OrderResponse.from(order)));
    }

    public Mono<ServerResponse> history(ServerRequest request) {
        return Mono.fromCallable(() -> RequestParams.uuidPath(request, "orderId"))
                .flatMap(orderId -> CallerResolver.resolve(request)
                        .flatMap(caller -> orderHistory.history(orderId, caller)
                                .map(OrderTransitionResponse::from)
                                .collectList()))
                .flatMap(transitions -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(transitions));
    }

    public Mono<ServerResponse> complimentary(ServerRequest request) {
        return Mono.fromCallable(() -> new ComplimentaryTarget(RequestParams.uuidPath(request, "eventId"),
                        RequestParams.idempotencyKey(request)))
                .flatMap(target -> validator.body(request, ComplimentaryRequest.class)
                        .zipWith(CallerResolver.resolve(request))
                        .map(input -> new IssueComplimentaryCommand(input.getT2(), target.idempotencyKey(),
                                target.eventId(), input.getT1().quantity(), input.getT1().recipientUserId())))
                .flatMap(complimentary::issue)
                .flatMap(order -> ServerResponse.created(location(order))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(OrderResponse.from(order)));
    }

    private static URI location(Order order) {
        return URI.create(ORDERS_PATH + order.orderId());
    }

    private record ComplimentaryTarget(String eventId, String idempotencyKey) {
    }
}
