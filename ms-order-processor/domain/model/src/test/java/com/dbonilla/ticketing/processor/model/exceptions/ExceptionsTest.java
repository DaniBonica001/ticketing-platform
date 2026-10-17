package com.dbonilla.ticketing.processor.model.exceptions;

import com.dbonilla.ticketing.processor.model.order.OrderStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class ExceptionsTest {

    @Test
    void messagesIdentifyTheOrderOrEvent() {
        assertThat(new OrderNotFoundException("o-1")).hasMessageContaining("o-1");
        assertThat(new OrderStateConflictException("o-1", OrderStatus.QUEUED)).hasMessageContaining("QUEUED");
        assertThat(new InventoryConditionFailedException("e-1")).hasMessageContaining("e-1");
        assertThat(new MissingPaymentReferenceException("o-1")).hasMessageContaining("o-1");
        assertThat(new InvalidOrderStateException("o-1", OrderStatus.SOLD, OrderStatus.RESERVED))
                .hasMessageContaining("SOLD").hasMessageContaining("RESERVED");
    }

    @Test
    void wrappingExceptionsKeepTheirCause() {
        IOException cause = new IOException("boom");

        assertThat(new TransientFailureException("t", cause)).hasCause(cause);
        assertThat(new PaymentGatewayUnavailableException("p", cause)).hasCause(cause);
    }
}
