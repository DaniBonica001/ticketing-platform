package com.dbonilla.ticketing.api.config;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessConfigTest {

    @Test
    void buildsPolicyFromBusinessProperties() {
        BusinessProperties properties = new BusinessProperties(new BusinessProperties.Reservation(Duration.ofMinutes(10)),
                new BusinessProperties.Reconciliation(Duration.ofMinutes(5)));
        UseCasesConfig config = new UseCasesConfig();

        assertThat(config.orderPolicy(properties).reconciliationDeadline()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.reservation().ttl()).isEqualTo(Duration.ofMinutes(10));
        Clock clock = config.clock();
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void businessPropertiesAreMandatory() {
        assertThatThrownBy(() -> new BusinessProperties(null, new BusinessProperties.Reconciliation(Duration.ZERO)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BusinessProperties(new BusinessProperties.Reservation(Duration.ZERO), null))
                .isInstanceOf(NullPointerException.class);
    }
}
