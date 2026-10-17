package com.dbonilla.ticketing.expirer.lambda;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdsTest {

    @Test
    void convertsTheXrayRootIntoAW3cTraceId() {
        assertThat(TraceIds.fromXrayHeader("Root=1-5759E988-BD862E3FE1BE46A994272793;Parent=53995c3f42cd8ad8;Sampled=1"))
                .contains("5759e988bd862e3fe1be46a994272793");
    }

    @Test
    void ignoresMissingMalformedOrZeroHeaders() {
        assertThat(TraceIds.fromXrayHeader(null)).isEmpty();
        assertThat(TraceIds.fromXrayHeader("Parent=53995c3f42cd8ad8")).isEmpty();
        assertThat(TraceIds.fromXrayHeader("Root=1-00000000-000000000000000000000000")).isEmpty();
    }

    @Test
    void usesTheRuntimePropertyWhenPresent() {
        String previous = System.getProperty(TraceIds.TRACE_HEADER_PROPERTY);
        try {
            System.setProperty(TraceIds.TRACE_HEADER_PROPERTY, "Root=1-5759e988-bd862e3fe1be46a994272793");
            assertThat(TraceIds.current()).isEqualTo("5759e988bd862e3fe1be46a994272793");
        } finally {
            if (previous == null) {
                System.clearProperty(TraceIds.TRACE_HEADER_PROPERTY);
            } else {
                System.setProperty(TraceIds.TRACE_HEADER_PROPERTY, previous);
            }
        }
    }

    @Test
    void generatesRandomIdsOtherwise() {
        assertThat(TraceIds.random()).matches("[0-9a-f]{32}").isNotEqualTo(TraceIds.random());
    }
}
