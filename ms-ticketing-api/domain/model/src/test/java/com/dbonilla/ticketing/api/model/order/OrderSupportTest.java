package com.dbonilla.ticketing.api.model.order;

import com.dbonilla.ticketing.api.model.TestData;
import com.dbonilla.ticketing.api.model.exception.IdempotencyKeyReusedException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderSupportTest {

    @Test
    void orderIdIsTheUuidV5OfUserAndKey() {
        String orderId = OrderIdentity.orderId("u1", "3b241101-e2bb-4255-8caf-4136c566a962");

        assertThat(orderId).isEqualTo("05906a04-21cd-5a4d-921d-2973626dc08d");
        assertThat(OrderIdentity.orderId("u2", "3b241101-e2bb-4255-8caf-4136c566a962")).isNotEqualTo(orderId);
    }

    @Test
    void requestHashIsSha256Hex() {
        assertThat(OrderIdentity.requestHash("e1:2"))
                .isEqualTo("73d7c41084c59616554d9b51f4653a56868c4af39ff9e77fc6f6b00bd9255a07");
    }

    @Test
    void createdResultResolvesToTheNewOrder() {
        Order order = TestData.order(OrderStatus.QUEUED, null, null);

        assertThat(new OrderWriteResult.Created(order).resolve("anything")).isEqualTo(order);
    }

    @Test
    void replayWithSamePayloadReturnsStoredOrder() {
        Order stored = TestData.order(OrderStatus.RESERVED, null, null);

        assertThat(new OrderWriteResult.AlreadyExists(stored).resolve("hash")).isEqualTo(stored);
    }

    @Test
    void replayWithOtherPayloadIsRejected() {
        Order stored = TestData.order(OrderStatus.RESERVED, null, null);
        OrderWriteResult result = new OrderWriteResult.AlreadyExists(stored);

        assertThatThrownBy(() -> result.resolve("other")).isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void callerCanReadOwnOrdersAndAdminCanReadAny() {
        Order order = TestData.order(OrderStatus.QUEUED, null, null);

        assertThat(new Caller(TestData.USER_ID, false).canRead(order)).isTrue();
        assertThat(new Caller(TestData.USER_ID, false).owns(order)).isTrue();
        assertThat(new Caller("other", false).canRead(order)).isFalse();
        assertThat(new Caller("admin", true).canRead(order)).isTrue();
        assertThat(new Caller("admin", true).owns(order)).isFalse();
        assertThatThrownBy(() -> new Caller(null, false)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void policyRequiresPositiveDeadline() {
        assertThat(new OrderPolicy(Duration.ofMinutes(5)).reconciliationDeadline()).hasMinutes(5);
        assertThatThrownBy(() -> new OrderPolicy(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderPolicy(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderPolicy(null)).isInstanceOf(NullPointerException.class);
    }
}
