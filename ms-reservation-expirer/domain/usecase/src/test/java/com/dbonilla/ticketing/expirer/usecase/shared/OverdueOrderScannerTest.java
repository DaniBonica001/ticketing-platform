package com.dbonilla.ticketing.expirer.usecase.shared;

import com.dbonilla.ticketing.expirer.model.execution.ExecutionContext;
import com.dbonilla.ticketing.expirer.model.execution.TimeBudget;
import com.dbonilla.ticketing.expirer.model.order.Order;
import com.dbonilla.ticketing.expirer.model.order.OrderPage;
import com.dbonilla.ticketing.expirer.model.order.OrderStatus;
import com.dbonilla.ticketing.expirer.model.order.OverdueOrdersQuery;
import com.dbonilla.ticketing.expirer.model.order.PageCursor;
import com.dbonilla.ticketing.expirer.model.order.gateways.OrderRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OverdueOrderScannerTest {

    private static final Instant NOW = Instant.parse("2026-10-16T10:12:00Z");
    private final OrderRepository repository = mock(OrderRepository.class);
    private final OverdueOrderScanner scanner = new OverdueOrderScanner(repository, 2);

    private static Order order(String id) {
        return new Order(id, "event-1", 1, OrderStatus.RESERVED, NOW.minusSeconds(10));
    }

    private static ExecutionContext context(TimeBudget budget) {
        return new ExecutionContext(NOW, "4bf92f3577b34da6a3ce929d0e0e4736", budget);
    }

    @Test
    void followsCursorsUntilTheLastPage() {
        PageCursor cursor = new PageCursor("o2", OrderStatus.RESERVED, NOW.minusSeconds(10));
        when(repository.findOverdue(any()))
                .thenReturn(Mono.just(new OrderPage(List.of(order("o1"), order("o2")), cursor)))
                .thenReturn(Mono.just(OrderPage.last(List.of(order("o3")))));

        StepVerifier.create(scanner.scan(OrderStatus.RESERVED, context(TimeBudget.unlimited())).map(Order::orderId))
                .expectNext("o1", "o2", "o3")
                .verifyComplete();

        ArgumentCaptor<OverdueOrdersQuery> queries = ArgumentCaptor.forClass(OverdueOrdersQuery.class);
        verify(repository, times(2)).findOverdue(queries.capture());
        assertThat(queries.getAllValues().get(0).exclusiveStart()).isEmpty();
        assertThat(queries.getAllValues().get(0).limit()).isEqualTo(2);
        assertThat(queries.getAllValues().get(0).dueBefore()).isEqualTo(NOW);
        assertThat(queries.getAllValues().get(1).exclusiveStart()).contains(cursor);
    }

    @Test
    void stopsPagingWhenTheBudgetIsExhausted() {
        AtomicBoolean exhausted = new AtomicBoolean(false);
        PageCursor cursor = new PageCursor("o1", OrderStatus.RESERVED, NOW.minusSeconds(10));
        when(repository.findOverdue(any())).thenAnswer(invocation -> {
            exhausted.set(true);
            return Mono.just(new OrderPage(List.of(order("o1")), cursor));
        });

        StepVerifier.create(scanner.scan(OrderStatus.RESERVED, context(exhausted::get)))
                .verifyComplete();

        verify(repository, times(1)).findOverdue(any());
    }

    @Test
    void doesNotQueryWhenAlreadyOutOfTime() {
        StepVerifier.create(scanner.scan(OrderStatus.RESERVED, context(() -> true))).verifyComplete();

        verifyNoInteractions(repository);
    }

    @Test
    void settingsMustBePositive() {
        assertThatThrownBy(() -> new ScanSettings(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScanSettings(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ScanSettings(10, 2).pageSize()).isEqualTo(10);
    }
}
