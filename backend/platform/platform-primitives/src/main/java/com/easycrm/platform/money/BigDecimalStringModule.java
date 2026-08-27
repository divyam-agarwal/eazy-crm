package com.easycrm.platform.money;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

import java.math.BigDecimal;

/**
 * Serializes every BigDecimal as a JSON string in plain notation. JS numbers are IEEE-754
 * doubles, so money on the wire as a number re-introduces the rounding error BigDecimal
 * exists to prevent (challenge #2). Deserialization is unchanged — Jackson already coerces
 * a JSON string back to BigDecimal. Jackson 3 lives under tools.jackson.* (challenge #10).
 */
public class BigDecimalStringModule extends SimpleModule {

    public BigDecimalStringModule() {
        addSerializer(BigDecimal.class, new BigDecimalStringSerializer());
    }

    static final class BigDecimalStringSerializer extends ValueSerializer<BigDecimal> {
        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeString(value.toPlainString());
        }
    }
}
