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
 *
 * <p><b>Which of these settings actually change today's behaviour:</b> verified empirically by
 * building a bare {@code JsonMapper.builder().build()} against the same 3.1.4 jars with none of
 * these calls present. Only the {@link BigDecimalStringModule} line does anything today — remove
 * it and {@code serialisesBigDecimalAsAString} fails. The date/timestamp pair, the
 * unknown-properties tolerance, and null inclusion are all already Jackson 3's out-of-the-box
 * behaviour; removing any of those three lines leaves every current test green. They are pinned
 * here anyway, on purpose: each is a stated property of an event contract that must stay readable
 * for years, and "matches today's default" is not a guarantee about tomorrow's default. Do not read
 * "currently redundant" as "safe to delete."
 */
public final class EventJson {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            // Money and every other BigDecimal as a plain-notation string, never a JSON number.
            // This is TB3's structural fix (challenges #2 and #17), and the only line here that
            // changes behaviour relative to a bare JsonMapper.builder().build() today.
            .addModule(new BigDecimalStringModule())
            // ISO-8601 timestamps, not epoch numbers: a number is ambiguous about its unit and a
            // stored event is read years later by something that cannot ask. In Jackson 3 these
            // timestamp-format switches moved off SerializationFeature onto the java.time-specific
            // DateTimeFeature (a DatatypeFeature) — SerializationFeature no longer declares them.
            // Already Jackson 3's default (WRITE_DATES_AS_TIMESTAMPS is enabledByDefault=false);
            // stated explicitly anyway so a future Jackson/Boot default change can't alter it.
            .disable(DateTimeFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            // Null fields are written, not omitted — see the class comment. The unconfigured
            // ConfigOverrides default is JsonInclude.Value.of(USE_DEFAULTS, USE_DEFAULTS), which
            // resolves to ALWAYS for a plain field at write time — so this already matches today's
            // behaviour. Stated explicitly anyway so a future default change cannot alter the event
            // contract.
            .changeDefaultPropertyInclusion(inc -> inc.withValueInclusion(
                    com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS))
            // An additive-only contract means a newer producer sends fields this consumer has never
            // heard of. Failing on them would make every additive change a breaking change. Already
            // Jackson 3's default (FAIL_ON_UNKNOWN_PROPERTIES is enabledByDefault=false); stated
            // explicitly anyway so a future default change cannot alter the event contract.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private EventJson() {}

    /** The mapper for anything persisted or published. Immutable and thread-safe (Jackson 3). */
    public static JsonMapper mapper() {
        return MAPPER;
    }
}
