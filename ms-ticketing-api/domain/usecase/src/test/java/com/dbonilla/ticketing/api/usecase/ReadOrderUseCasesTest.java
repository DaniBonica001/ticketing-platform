package com.dbonilla.ticketing.api.usecase;

import com.dbonilla.ticketing.api.model.exception.OrderNotFoundException;
import com.dbonilla.ticketing.api.model.order.Caller;
import com.dbonilla.ticketing.api.model.order.Order;
import com.dbonilla.ticketing.api.model.order.OrderStatus;
import com.dbonilla.ticketing.api.model.order.gateways.OrderRepository;
import com.dbonilla.ticketing.api.model.ordertransition.OrderTransition;
import com.dbonilla.ticketing.api.model.ordertransition.gateways.OrderTransitionRepository;
import com.dbonilla.ticketing.api.usecase.getorder.GetOrderUseCase;
import com.dbonilla.ticketing.api.usecase.getorderhistory.GetOrderHistoryUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static com.dbonilla.ticketing.api.usecase.Fixtures.ORDER_ID;
import static com.dbonilla.ticketing.api.usecase.Fixtures.USER_ID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReadOrderUseCasesTest {

    private static final Order ORDER = Fixtures.order(OrderStatus.QUEUED, null, null);

    @Mock
    private OrderRepository orders;
    @Mock
    private OrderTransitionRepository transitions;

    @Test
    void ownerReadsOrder() {
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(ORDER));

        StepVerifier.create(new GetOrderUseCase(orders).get(ORDER_ID, new Caller(USER_ID, false)))
                .expectNext(ORDER)
                .verifyComplete();
    }

    @Test
    void adminReadsAnyOrder() {
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(ORDER));

        StepVerifier.create(new GetOrderUseCase(orders).get(ORDER_ID, new Caller("admin", true)))
                .expectNext(ORDER)
                .verifyComplete();
    }

    @Test
    void foreignOrderIsReportedAsNotFound() {
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(ORDER));

        StepVerifier.create(new GetOrderUseCase(orders).get(ORDER_ID, new Caller("intruder", false)))
                .verifyError(OrderNotFoundException.class);
    }

    @Test
    void historyOfOwnOrder() {
        OrderTransition created = OrderTransition.created(ORDER);
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(ORDER));
        when(transitions.findByOrderId(ORDER_ID)).thenReturn(Flux.just(created));

        StepVerifier.create(new GetOrderHistoryUseCase(orders, transitions).history(ORDER_ID, new Caller(USER_ID, false)))
                .expectNext(created)
                .verifyComplete();
    }

    @Test
    void historyOfForeignOrderIsNotFound() {
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(ORDER));

        StepVerifier.create(new GetOrderHistoryUseCase(orders, transitions).history(ORDER_ID, new Caller("x", false)))
                .verifyError(OrderNotFoundException.class);
        verify(transitions, never()).findByOrderId(any());
    }

    @Test
    void historyOfMissingOrderIsNotFound() {
        when(orders.findById(ORDER_ID)).thenReturn(Mono.empty());

        StepVerifier.create(new GetOrderHistoryUseCase(orders, transitions).history(ORDER_ID, new Caller(USER_ID, false)))
                .verifyError(OrderNotFoundException.class);
    }
}
