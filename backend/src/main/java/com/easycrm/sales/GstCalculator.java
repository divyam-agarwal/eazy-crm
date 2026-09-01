package com.easycrm.sales;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Pure GST computation. Rounds at the line to 2 dp (HALF_UP), then sums the rounded line
 * values — matching Tally and avoiding the accumulated-rounding error of sum-then-round
 * (challenge #2). Whether a line is inter-state (IGST) or intra-state (CGST+SGST) is decided
 * by the caller (customer place-of-supply vs tenant/supplier state) and passed in.
 */
public final class GstCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWO = new BigDecimal("2");

    private GstCalculator() {}

    public record LineInput(BigDecimal qty, BigDecimal rate, BigDecimal discountPct, BigDecimal gstRate) {}

    public record LineResult(
            BigDecimal taxableValue, BigDecimal cgst, BigDecimal sgst, BigDecimal igst, BigDecimal lineTotal) {}

    public record Totals(BigDecimal subTotal, BigDecimal totalTax, BigDecimal grandTotal) {}

    public static LineResult computeLine(LineInput in, boolean interState) {
        BigDecimal discount = in.discountPct() == null ? BigDecimal.ZERO : in.discountPct();
        BigDecimal gross = in.qty().multiply(in.rate());
        BigDecimal discountFactor = BigDecimal.ONE.subtract(discount.divide(HUNDRED));
        BigDecimal taxable = round(gross.multiply(discountFactor));

        BigDecimal cgst = BigDecimal.ZERO.setScale(2);
        BigDecimal sgst = BigDecimal.ZERO.setScale(2);
        BigDecimal igst = BigDecimal.ZERO.setScale(2);
        if (interState) {
            igst = round(taxable.multiply(in.gstRate()).divide(HUNDRED));
        } else {
            // CGST and SGST each rounded INDEPENDENTLY to 2 dp — matches Tally's two
            // half-rate lines (challenge #2). Their sum can legitimately differ by 0.01
            // from a single-rate calc; that is the correct Tally behavior.
            BigDecimal half = round(taxable.multiply(in.gstRate()).divide(TWO).divide(HUNDRED));
            cgst = half;
            sgst = half;
        }
        BigDecimal lineTotal = taxable.add(cgst).add(sgst).add(igst);
        return new LineResult(taxable, cgst, sgst, igst, lineTotal);
    }

    public static Totals totals(List<LineResult> lines) {
        BigDecimal sub = BigDecimal.ZERO.setScale(2);
        BigDecimal tax = BigDecimal.ZERO.setScale(2);
        for (LineResult l : lines) {
            sub = sub.add(l.taxableValue());
            tax = tax.add(l.cgst()).add(l.sgst()).add(l.igst());
        }
        return new Totals(sub, tax, sub.add(tax));
    }

    private static BigDecimal round(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
