package com.easycrm.platform.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EventJsonTest {

    record Payload(BigDecimal grandTotal, Instant occurredAt, String note) {}

    @Test
    void serialisesBigDecimalAsAString() {
        // THE TB3 REGRESSION TEST. If this fails, money reaches SNS as an IEEE-754 double and
        // every consumer downstream inherits the rounding error BigDecimal exists to prevent.
        String json = EventJson.mapper()
            .writeValueAsString(new Payload(new BigDecimal("1180.00"), Instant.EPOCH, null));

        assertThat(json).contains("\"grandTotal\":\"1180.00\"");
        assertThat(json).doesNotContain("\"grandTotal\":1180");
    }

    @Test
    void writesTimestampsAsIso8601NotEpochNumbers() {
        // An additive-only contract read for years must not depend on a Jackson default. A numeric
        // epoch is also ambiguous about its unit in a way an ISO-8601 string never is.
        String json = EventJson.mapper()
            .writeValueAsString(new Payload(BigDecimal.ONE, Instant.parse("2026-08-27T09:15:30Z"), null));

        assertThat(json).contains("\"occurredAt\":\"2026-08-27T09:15:30Z\"");
    }

    @Test
    void keepsNullFieldsRatherThanOmittingThem() {
        // The whole reason this mapper is not the application mapper: a consumer must be able to
        // tell "field absent because the producer is older" from "field present and null".
        String json = EventJson.mapper()
            .writeValueAsString(new Payload(BigDecimal.ONE, Instant.EPOCH, null));

        assertThat(json).contains("\"note\":null");
    }

    @Test
    void ignoresUnknownPropertiesOnRead() {
        // Additive-only means a newer producer will send fields this consumer has never heard of.
        // Failing on them would make every additive change a breaking one.
        Payload p = EventJson.mapper().readValue(
            "{\"grandTotal\":\"5.00\",\"occurredAt\":\"2026-08-27T09:15:30Z\","
          + "\"note\":null,\"fieldFromANewerProducer\":42}", Payload.class);

        assertThat(p.grandTotal()).isEqualByComparingTo("5.00");
    }

    @Test
    void isASingleSharedInstance() {
        // Jackson 3 mappers are immutable and thread-safe, so one static final instance is correct:
        // no synchronisation, no per-call construction, no ThreadLocal pooling.
        assertThat(EventJson.mapper()).isSameAs(EventJson.mapper());
    }
}
