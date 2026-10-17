package com.dbonilla.ticketing.expirer.usecase.expirereservations;

import com.dbonilla.ticketing.expirer.model.execution.ExecutionContext;
import com.dbonilla.ticketing.expirer.model.execution.OrderFailure;
import com.dbonilla.ticketing.expirer.model.execution.TimeBudget;
import com.dbonilla.ticketing.expirer.model.order.ExpirationOutcome;
import com.dbonilla.ticketing.expirer.model.order.InventoryInvariantViolationException;
import com.dbonilla.ticketing.expirer.model.order.Order;
import com.dbonilla.ticketing.expirer.model.order.OrderPage;
import com.dbonilla.ticketing.expirer.model.order.OrderStatus;
import com.dbonilla.ticketing.expirer.model.order.PageCursor;
import com.dbonilla.ticketing.expirer.model.order.gateways.OrderRepository;
import com.dbonilla.ticketing.expirer.usecase.shared.ScanSettings;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExpireReservationsUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-16T10:12:00Z");
    private final OrderRepository repository = mock(OrderRepository.class);
    private final ExpireReservationsUseCase useCase = new ExpireReservationsUseCase(repository, new ScanSettings(2, 2));
    private final ExecutionContext context = new ExecutionContext(NOW, "4bf92f3577b34da6a3ce929d0e0e4736",
            TimeBudget.unlimited());

    private static Order reserved(String id) {
        return new Order(id, "event-1", 2, OrderStatus.RESERVED, NOW.minusSeconds(30));
    }

    @Test
    void expiresEveryOverdueReservation() {
        when(repository.findOverdue(argThat(query -> query.status() == OrderStatus.RESERVED)))
                .thenReturn(Mono.just(OrderPage.last(List.of(reserved("o1"), reserved("o2")))));
        when(repository.expire(any(), eq(context))).thenReturn(Mono.just(ExpirationOutcome.EXPIRED));

        StepVerifier.create(useCase.execute(context))
                .assertNext(result -> {
                    assertThat(result.expired()).isEqualTo(2);
                    assertThat(result.skipped()).isZero();
                    assertThat(result.failures()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void skipsOrdersConfirmedConcurrently() {
        when(repository.findOverdue(any())).thenReturn(Mono.just(OrderPage.last(List.of(reserved("o1")))));
        when(repository.expire(any(), any())).thenReturn(Mono.just(ExpirationOutcome.ALREADY_TRANSITIONED));

        StepVerifier.create(useCase.execute(context))
                .assertNext(result -> {
                    assertThat(result.expired()).isZero();
                    assertThat(result.skipped()).isEqualTo(1);
                    assertThat(result.failures()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void skipsWithoutWritingWhenTheIndexReturnsAnOrderThatIsNotOverdue() {
        Order notYetDue = new Order("o1", "event-1", 1, OrderStatus.RESERVED, NOW.plusSeconds(30));
        when(repository.findOverdue(any())).thenReturn(Mono.just(OrderPage.last(List.of(notYetDue))));

        StepVerifier.create(useCase.execute(context))
                .assertNext(result -> assertThat(result.skipped()).isEqualTo(1))
                .verifyComplete();

        verify(repository, never()).expire(any(), any());
    }

    @Test
    void processesEveryPage() {
        PageCursor cursor = new PageCursor("o2", OrderStatus.RESERVED, NOW.minusSeconds(30));
        when(repository.findOverdue(any()))
                .thenReturn(Mono.just(new OrderPage(List.of(reserved("o1"), reserved("o2")), cursor)))
                .thenReturn(Mono.just(OrderPage.last(List.of(reserved("o3")))));
        when(repository.expire(any(), any())).thenReturn(Mono.just(ExpirationOutcome.EXPIRED));

        StepVerifier.create(useCase.execute(context))
                .assertNext(result -> assertThat(result.expired()).isEqualTo(3))
                .verifyComplete();

        verify(repository, times(2)).findOverdue(any());
    }

    @Test
    void isolatesTheFailureOfOneOrder() {
        when(repository.findOverdue(any()))
                .thenReturn(Mono.just(OrderPage.last(List.of(reserved("o1"), reserved("o2"), reserved("o3")))));
        when(repository.expire(any(), any())).thenReturn(Mono.just(ExpirationOutcome.EXPIRED));
        when(repository.expire(argThat(order -> order != null && order.orderId().equals("o2")), any()))
                .thenReturn(Mono.error(new InventoryInvariantViolationException("o2", "event-1")));

        StepVerifier.create(useCase.execute(context))
                .assertNext(result -> {
                    assertThat(result.expired()).isEqualTo(2);
                    assertThat(result.failures()).singleElement().satisfies(failure -> {
                        assertThat(failure.orderId()).isEqualTo("o2");
                        assertThat(failure.operation()).isEqualTo(ExpireReservationsUseCase.EXPIRE_OPERATION);
                        assertThat(failure.errorType()).isEqualTo(InventoryInvariantViolationException.class.getName());
                    });
                })
                .verifyComplete();
    }

    @Test
    void keepsResultsOfEarlierPagesWhenALaterQueryFails() {
        PageCursor cursor = new PageCursor("o1", OrderStatus.RESERVED, NOW.minusSeconds(30));
        when(repository.findOverdue(any()))
                .thenReturn(Mono.just(new OrderPage(List.of(reserved("o1")), cursor)))
                .thenReturn(Mono.error(new IllegalStateException("query failed")));
        when(repository.expire(any(), any())).thenReturn(Mono.just(ExpirationOutcome.EXPIRED));

        StepVerifier.create(useCase.execute(context))
                .assertNext(result -> {
                    assertThat(result.expired()).isEqualTo(1);
                    assertThat(result.failures()).extracting(OrderFailure::operation)
                            .containsExactly(ExpireReservationsUseCase.QUERY_OPERATION);
                })
                .verifyComplete();
    }

    @Test
    void stopsWhenTheTimeBudgetRunsOut() {
        AtomicBoolean exhausted = new AtomicBoolean(false);
        ExecutionContext limited = new ExecutionContext(NOW, context.traceId(), exhausted::get);
        PageCursor cursor = new PageCursor("o2", OrderStatus.RESERVED, NOW.minusSeconds(30));
        when(repository.findOverdue(any()))
                .thenReturn(Mono.just(new OrderPage(List.of(reserved("o1"), reserved("o2")), cursor)));
        when(repository.expire(any(), any())).thenAnswer(invocation -> {
            exhausted.set(true);
            return Mono.just(ExpirationOutcome.EXPIRED);
        });

        StepVerifier.create(useCase.execute(limited))
                .assertNext(result -> assertThat(result.expired()).isBetween(1, 2))
                .verifyComplete();

        verify(repository, times(1)).findOverdue(any());
    }

    @Test
    void returnsAnEmptyResultWhenNothingIsOverdue() {
        when(repository.findOverdue(any())).thenReturn(Mono.just(OrderPage.last(List.of())));

        StepVerifier.create(useCase.execute(context))
                .expectNext(ExpirationResult.EMPTY)
                .verifyComplete();
    }
}
