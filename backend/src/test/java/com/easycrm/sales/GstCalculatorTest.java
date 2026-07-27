package com.easycrm.sales;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GstCalculatorTest {

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    @Test
    void intraStateSplitsCgstSgstEqually() {
        var in = new GstCalculator.LineInput(bd("2"), bd("100.00"), BigDecimal.ZERO, bd("18"));
        var r = GstCalculator.computeLine(in, false); // intra-state
        assertThat(r.taxableValue()).isEqualByComparingTo("200.00");
        assertThat(r.cgst()).isEqualByComparingTo("18.00");
        assertThat(r.sgst()).isEqualByComparingTo("18.00");
        assertThat(r.igst()).isEqualByComparingTo("0.00");
        assertThat(r.lineTotal()).isEqualByComparingTo("236.00");
    }

    @Test
    void interStateChargesIgstOnly() {
        var in = new GstCalculator.LineInput(bd("2"), bd("100.00"), BigDecimal.ZERO, bd("18"));
        var r = GstCalculator.computeLine(in, true); // inter-state
        assertThat(r.cgst()).isEqualByComparingTo("0.00");
        assertThat(r.sgst()).isEqualByComparingTo("0.00");
        assertThat(r.igst()).isEqualByComparingTo("36.00");
        assertThat(r.lineTotal()).isEqualByComparingTo("236.00");
    }

    @Test
    void appliesLineDiscountToTaxableValue() {
        var in = new GstCalculator.LineInput(bd("1"), bd("100.00"), bd("10"), bd("18"));
        var r = GstCalculator.computeLine(in, true);
        assertThat(r.taxableValue()).isEqualByComparingTo("90.00"); // 100 - 10%
        assertThat(r.igst()).isEqualByComparingTo("16.20");
    }

    @Test
    void zeroRatedProducesNoTax() {
        var in = new GstCalculator.LineInput(bd("3"), bd("50.00"), BigDecimal.ZERO, bd("0"));
        var r = GstCalculator.computeLine(in, false);
        assertThat(r.taxableValue()).isEqualByComparingTo("150.00");
        assertThat(r.cgst()).isEqualByComparingTo("0.00");
        assertThat(r.lineTotal()).isEqualByComparingTo("150.00");
    }

    @Test
    void roundsAtLineThenSums_notSumThenRound() {
        // Two lines each taxable 0.125 → rounds to 0.13 each; round-then-sum = 0.26.
        // Sum-then-round of 0.25 would give 0.25 (or 0.26 depending) — this pins the Tally rule.
        var a = GstCalculator.computeLine(
            new GstCalculator.LineInput(bd("1"), bd("0.125"), BigDecimal.ZERO, bd("0")), false);
        var b = GstCalculator.computeLine(
            new GstCalculator.LineInput(bd("1"), bd("0.125"), BigDecimal.ZERO, bd("0")), false);
        assertThat(a.taxableValue()).isEqualByComparingTo("0.13"); // 0.125 HALF_UP → 0.13
        var totals = GstCalculator.totals(List.of(a, b));
        assertThat(totals.subTotal()).isEqualByComparingTo("0.26");
        assertThat(totals.grandTotal()).isEqualByComparingTo("0.26");
    }

    @Test
    void totalsSumRoundedLineValues() {
        var a = GstCalculator.computeLine(
            new GstCalculator.LineInput(bd("2"), bd("100.00"), BigDecimal.ZERO, bd("18")), false);
        var b = GstCalculator.computeLine(
            new GstCalculator.LineInput(bd("1"), bd("50.00"), BigDecimal.ZERO, bd("12")), true);
        var t = GstCalculator.totals(List.of(a, b));
        assertThat(t.subTotal()).isEqualByComparingTo("250.00");   // 200 + 50
        assertThat(t.totalTax()).isEqualByComparingTo("42.00");    // (18+18) + 6
        assertThat(t.grandTotal()).isEqualByComparingTo("292.00");
    }

    @Test
    void intraStateRoundsEachHalfIndependentlyMatchingTally() {
        // taxable 1.00 @ 1% → each half = round(1.00*1/2/100) = round(0.005) = 0.01.
        // Independent rounding yields 0.01 + 0.01 = 0.02 total tax (Tally's two half-rate
        // lines), NOT the 0.01 a single-rate calc would give.
        var in = new GstCalculator.LineInput(bd("1"), bd("1.00"), BigDecimal.ZERO, bd("1"));
        var r = GstCalculator.computeLine(in, false);
        assertThat(r.cgst()).isEqualByComparingTo("0.01");
        assertThat(r.sgst()).isEqualByComparingTo("0.01");
        assertThat(r.lineTotal()).isEqualByComparingTo("1.02");
    }
}
