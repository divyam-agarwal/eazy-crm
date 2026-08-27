package com.easycrm.platform.money;

import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins where the event wire and the HTTP wire agree and where they diverge (LLD #1 Appendix B
 * item 3). "EventJson inherits nothing" is only meaningful if the differences are written down;
 * this is them, executable. A change to Boot's Jackson configuration that moves one of these
 * turns this test red, which is the point — the event contract must not drift silently behind an
 * API-shaping decision.
 */
class EventJsonDivergenceTest extends IntegrationTest {

    @Autowired ObjectMapper applicationMapper;

    record Sample(BigDecimal amount, Instant at, String absent) {}

    private static final Sample SAMPLE =
        new Sample(new BigDecimal("12.50"), Instant.parse("2026-08-27T09:15:30Z"), null);

    @Test
    void bothWiresAgreeThatMoneyIsAString() {
        // The one property that must never diverge. Both carry BigDecimalStringModule — the app
        // mapper via MoneyAutoConfiguration, the event mapper by explicit construction.
        assertThat(applicationMapper.writeValueAsString(SAMPLE)).contains("\"amount\":\"12.50\"");
        assertThat(EventJson.mapper().writeValueAsString(SAMPLE)).contains("\"amount\":\"12.50\"");
    }

    @Test
    void theEventWireIsNotDownstreamOfApiShapingDecisions() {
        // Whatever the application mapper is configured to do with nulls, the event wire keeps
        // them. If someone sets spring.jackson.default-property-inclusion=non_null tomorrow, the
        // app assertion below may change; the EventJson one must not.
        assertThat(EventJson.mapper().writeValueAsString(SAMPLE)).contains("\"absent\":null");
    }

    @Test
    void bothWiresWriteIso8601Timestamps() {
        assertThat(applicationMapper.writeValueAsString(SAMPLE)).contains("2026-08-27T09:15:30Z");
        assertThat(EventJson.mapper().writeValueAsString(SAMPLE)).contains("2026-08-27T09:15:30Z");
    }
}
