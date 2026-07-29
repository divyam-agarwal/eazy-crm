package com.easycrm.platform.format;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class IndianFormatsTest {

    @Test
    void rupeesUsesIndianDigitGrouping() {
        // Lakh/crore grouping: the first group is 3 digits, every group after it is 2.
        assertEquals("Rs. 1,23,456.78", IndianFormats.rupees(new BigDecimal("123456.78")));
        assertEquals("Rs. 1,00,00,000.00", IndianFormats.rupees(new BigDecimal("10000000")));
        assertEquals("Rs. 999.00", IndianFormats.rupees(new BigDecimal("999")));
        assertEquals("Rs. 0.00", IndianFormats.rupees(BigDecimal.ZERO));
    }

    @Test
    void rupeesNeverUsesTheRupeeGlyph() {
        // U+20B9 is absent from the base-14 PDF fonts and no font is embedded.
        assertFalse(IndianFormats.rupees(new BigDecimal("1")).contains("₹"));
    }

    @Test
    void nullsRenderAsEmptyStringsRatherThanTheWordNull() {
        assertEquals("", IndianFormats.rupees(null));
        assertEquals("", IndianFormats.percent(null));
        assertEquals("", IndianFormats.date((LocalDate) null));
        assertEquals("", IndianFormats.date((Instant) null));
    }

    @Test
    void qtyAndPercentStripMeaninglessTrailingZeros() {
        assertEquals("10", IndianFormats.qty(new BigDecimal("10.000")));
        assertEquals("2.5", IndianFormats.qty(new BigDecimal("2.500")));
        assertEquals("18", IndianFormats.percent(new BigDecimal("18.0000")));
        assertEquals("2.5", IndianFormats.percent(new BigDecimal("2.5000")));
    }

    @Test
    void datesRenderDayFirstAndInstantsUseIndianStandardTime() {
        assertEquals("28-07-2026", IndianFormats.date(LocalDate.of(2026, 7, 28)));
        // 21:00 UTC on the 27th is 02:30 on the 28th in Asia/Kolkata — the date the
        // Indian user would expect to see on the document.
        assertEquals("28-07-2026", IndianFormats.date(Instant.parse("2026-07-27T21:00:00Z")));
    }

    @Test
    void negativeAmountsKeepTheSignAheadOfTheDigits() {
        assertEquals("Rs. -1,23,456.78", IndianFormats.rupees(new BigDecimal("-123456.78")));
        // signum() reads the unscaled value, so negative zero must not render a stray minus
        assertEquals("Rs. 0.00", IndianFormats.rupees(new BigDecimal("-0.00")));
    }

    @Test
    void groupingIsCorrectAtEachBoundaryWhereTheLoopChangesShape() {
        assertEquals("Rs. 1,000.00", IndianFormats.rupees(new BigDecimal("1000")));
        assertEquals("Rs. 99,999.00", IndianFormats.rupees(new BigDecimal("99999")));
        assertEquals("Rs. 1,00,000.00", IndianFormats.rupees(new BigDecimal("100000")));
    }

    @Test
    void roundingIsAppliedCorrectly() {
        assertEquals("Rs. 1.01", IndianFormats.rupees(new BigDecimal("1.005")));
    }
}
