package com.dbonilla.ticketing.processor.dynamodb.transaction;

import com.dbonilla.ticketing.processor.dynamodb.config.TableNames;
import com.dbonilla.ticketing.processor.model.exceptions.InventoryConditionFailedException;
import com.dbonilla.ticketing.processor.model.exceptions.OrderStateConflictException;
import com.dbonilla.ticketing.processor.model.exceptions.TransientFailureException;
import com.dbonilla.ticketing.processor.model.order.Order;
import com.dbonilla.ticketing.processor.model.order.OrderStatus;
import com.dbonilla.ticketing.processor.model.order.OrderTransition;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionErrorMapperTest {

    private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");
    private static final TransitionTransactionFactory FACTORY =
            new TransitionTransactionFactory(new TableNames("events", "orders", "audit"));
    private static final OrderTransition RESERVE = new Order("order-1", "event-1", "u", 1, OrderStatus.QUEUED,
            null, null, null).reserve(NOW, Duration.ofMinutes(10));
    private static final TransactionPlan RESERVE_PLAN = FACTORY.create(RESERVE, "t");
    private static final OrderTransition SELL = new Order("order-1", "event-1", "u", 1,
            OrderStatus.PENDING_CONFIRMATION, null, null, "REF").markSold(NOW);
    private static final TransactionPlan SELL_PLAN = FACTORY.create(SELL, "t");

    /** Codes in the same order as the items of the plan. */
    private static TransactionCanceledException canceled(String... codes) {
        return TransactionCanceledException.builder()
                .message("Transaction cancelled")
                .cancellationReasons(Arrays.stream(codes).map(code -> CancellationReason.builder().code(code).build()).toList())
                .build();
    }

    @Test
    void orderConditionFailureMeansAlreadyProcessed() {
        // T2 items: events, orders, audit
        Throwable mapped = TransactionErrorMapper.map(canceled("None", "ConditionalCheckFailed", "None"), RESERVE_PLAN, RESERVE);

        assertThat(mapped).isInstanceOf(OrderStateConflictException.class);
    }

    @Test
    void eventConditionFailureMeansNoInventory() {
        Throwable mapped = TransactionErrorMapper.map(canceled("ConditionalCheckFailed", "None", "None"), RESERVE_PLAN, RESERVE);

        assertThat(mapped).isInstanceOf(InventoryConditionFailedException.class);
    }

    @Test
    void orderConditionWinsWhenBothConditionsFail() {
        Throwable mapped = TransactionErrorMapper.map(
                canceled("ConditionalCheckFailed", "ConditionalCheckFailed", "None"), RESERVE_PLAN, RESERVE);

        assertThat(mapped).isInstanceOf(OrderStateConflictException.class);
    }

    @Test
    void indexesFollowThePlanOfEachTransition() {
        // T5 items: orders, events, audit -> index 0 is the order
        assertThat(TransactionErrorMapper.map(canceled("ConditionalCheckFailed", "None", "None"), SELL_PLAN, SELL))
                .isInstanceOf(OrderStateConflictException.class);
        assertThat(TransactionErrorMapper.map(canceled("None", "ConditionalCheckFailed", "None"), SELL_PLAN, SELL))
                .isInstanceOf(InventoryConditionFailedException.class);
    }

    @Test
    void transactionConflictIsTransientAndDetected() {
        TransactionCanceledException conflict = canceled("TransactionConflict", "None", "None");

        assertThat(TransactionErrorMapper.map(new CompletionException(conflict), RESERVE_PLAN, RESERVE))
                .isInstanceOf(TransientFailureException.class)
                .hasCause(conflict);
        assertThat(TransactionErrorMapper.hasTransactionConflict(new CompletionException(conflict))).isTrue();
        assertThat(TransactionErrorMapper.hasTransactionConflict(canceled("ThrottlingError", "None", "None"))).isFalse();
        assertThat(TransactionErrorMapper.hasTransactionConflict(new IllegalStateException())).isFalse();
        assertThat(TransactionErrorMapper.hasTransactionConflict(TransactionCanceledException.builder().build())).isFalse();
    }

    @Test
    void nonTransientCancellationIsPropagatedUnchanged() {
        TransactionCanceledException validation = canceled("ValidationError", "None", "None");

        assertThat(TransactionErrorMapper.map(validation, RESERVE_PLAN, RESERVE)).isSameAs(validation);
    }

    @Test
    void otherErrorsAreClassifiedWithTheTransientPredicate() {
        SdkClientException network = SdkClientException.create("connection reset");
        IllegalStateException bug = new IllegalStateException("bug");
        TransactionCanceledException withoutReasons = TransactionCanceledException.builder().build();

        assertThat(TransactionErrorMapper.map(network, RESERVE_PLAN, RESERVE)).isInstanceOf(TransientFailureException.class);
        assertThat(TransactionErrorMapper.map(bug, RESERVE_PLAN, RESERVE)).isSameAs(bug);
        assertThat(TransactionErrorMapper.map(withoutReasons, RESERVE_PLAN, RESERVE)).isSameAs(withoutReasons);
    }
}
