package com.easycrm.platform.money;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The mapper for anything persisted or published — outbox {@code payload} JSONB, SNS, SQS.
 *
 * <p><b>This is deliberately not the application ObjectMapper, and the duplication is not an
 * accident.</b> The two wires have different owners and different change cadences. HTTP responses
 * are owned by Spring Boot and versioned by the API; an event payload is an additive-only contract
 * that must stay readable for years. Injecting the application mapper here would mean that setting
 * {@code spring.jackson.default-property-inclusion=non_null} to slim an API response silently drops
 * null fields from every subsequent event — after which a consumer cannot distinguish "field absent
 * because the producer is older" from "field present and null". {@code rebuild()} inherits the same
 * coupling one step later. If you are here to unify the two mappers, this paragraph is the reason
 * not to (MB4).
 *
 * <p>Every setting below is stated explicitly: this wire inherits nothing, so a change to Boot's
 * Jackson defaults cannot reach it.
 */
public final class EventJson {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            // Money and every other BigDecimal as a plain-notation string, never a JSON number.
            // This is TB3's structural fix (challenges #2 and #17).
            .addModule(new BigDecimalStringModule())
            // ISO-8601 timestamps, not epoch numbers: a number is ambiguous about its unit and a
            // stored event is read years later by something that cannot ask. In Jackson 3 these
            // timestamp-format switches moved off SerializationFeature onto the java.time-specific
            // DateTimeFeature (a DatatypeFeature) — SerializationFeature no longer declares them.
            .disable(DateTimeFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            // Null fields are written, not omitted — see the class comment. Jackson's default is
            // ALWAYS; stated here so a future default change cannot alter the event contract.
            .changeDefaultPropertyInclusion(inc -> inc.withValueInclusion(
                    com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS))
            // An additive-only contract means a newer producer sends fields this consumer has never
            // heard of. Failing on them would make every additive change a breaking change.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private EventJson() {}

    /** The mapper for anything persisted or published. Immutable and thread-safe (Jackson 3). */
    public static JsonMapper mapper() {
        return MAPPER;
    }
}
