package com.easycrm.platform.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * MF5: the serialiser had no unit test at all — only MoneyWireFormatTest, a full @SpringBootTest.
 * These run in milliseconds and say exactly which property broke.
 *
 * <p>It is a numeric-precision serialiser, not a money serialiser. QuotationItem carries nine
 * BigDecimal fields and only six are money: qty is NUMERIC(18,3) and gstRate/discountPct are
 * NUMERIC(18,4). A quantity of 2.5 KG has exactly the IEEE-754 problem money has, so keying off
 * the Java type rather than the field's meaning is deliberate.
 */
class BigDecimalStringModuleTest {

    private final JsonMapper mapper =
            JsonMapper.builder().addModule(new BigDecimalStringModule()).build();

    private String write(BigDecimal v) {
        return mapper.writeValueAsString(new Holder(v));
    }

    record Holder(BigDecimal amount) {}

    @Test
    void serialisesAsAQuotedStringNotANumber() {
        assertThat(write(new BigDecimal("12.50"))).isEqualTo("{\"amount\":\"12.50\"}");
    }

    @Test
    void preservesScaleRatherThanNormalising() {
        // 12.50 must not become 12.5: the column is NUMERIC(18,2) and the wire shows what is stored.
        assertThat(write(new BigDecimal("12.50"))).contains("\"12.50\"");
        assertThat(write(new BigDecimal("18.0000"))).contains("\"18.0000\"");
    }

    @Test
    void doesNotRound() {
        // Rounding is GstCalculator's job (per line, HALF_UP, then sum) and the column's job.
        // A wire that silently rounded would hide the disagreement with Tally this design exists
        // to prevent.
        assertThat(write(new BigDecimal("1250"))).contains("\"1250\"");
        assertThat(write(new BigDecimal("0.123456"))).contains("\"0.123456\"");
    }

    @Test
    void usesPlainNotationNeverScientific() {
        // toString() would emit "1.25E+3", which no Indian accountant and no Tally import accepts.
        assertThat(write(new BigDecimal("1.25E+3"))).contains("\"1250\"");
        assertThat(write(new BigDecimal("0.00000001"))).contains("\"0.00000001\"");
    }

    @Test
    void handlesNegativeAndZero() {
        assertThat(write(new BigDecimal("-1.00"))).contains("\"-1.00\"");
        assertThat(write(new BigDecimal("0.00"))).contains("\"0.00\"");
    }

    @Test
    void deserialisationStillAcceptsBothStringAndNumber() {
        // Not touched by the module: Jackson already coerces. A client can still send a JSON
        // number, which is contained rather than prevented — the server recomputes every total
        // and is authoritative, and the client preview is never trusted.
        assertThat(mapper.readValue("{\"amount\":\"12.50\"}", Holder.class).amount())
                .isEqualByComparingTo("12.50");
        assertThat(mapper.readValue("{\"amount\":12.50}", Holder.class).amount())
                .isEqualByComparingTo("12.50");
    }
}
