package com.dbonilla.ticketing.api.model.exception;

import com.dbonilla.ticketing.api.model.order.OrderStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExceptionsTest {

    @Test
    void everyExceptionCarriesItsCode() {
        IllegalStateException cause = new IllegalStateException("boom");

        assertThat(new DomainValidationException("f", "m").code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(new EventNotFoundException("e").code()).isEqualTo(ErrorCode.EVENT_NOT_FOUND);
        assertThat(new OrderNotFoundException("o").code()).isEqualTo(ErrorCode.ORDER_NOT_FOUND);
        assertThat(new InvalidOrderStateException("o", OrderStatus.SOLD, OrderStatus.RESERVED).code())
                .isEqualTo(ErrorCode.INVALID_ORDER_STATE);
        assertThat(new ReservationExpiredException("o").code()).isEqualTo(ErrorCode.RESERVATION_EXPIRED);
        assertThat(new InsufficientInventoryException("e", 3).code()).isEqualTo(ErrorCode.INSUFFICIENT_INVENTORY);
        assertThat(new VersionConflictException("e", 2).code()).isEqualTo(ErrorCode.VERSION_CONFLICT);
        assertThat(new IdempotencyKeyReusedException().code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        ServiceUnavailableException unavailable = new ServiceUnavailableException("DynamoDB", cause);
        assertThat(unavailable.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(unavailable.getCause()).isSameAs(cause);
    }

    @Test
    void validationExceptionExposesTheField() {
        assertThat(new DomainValidationException("quantity", "too many").field()).isEqualTo("quantity");
    }
}
