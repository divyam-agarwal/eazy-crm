package com.easycrm.platform.format;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Presentation-only formatting for Indian business documents. Computes nothing. */
public final class IndianFormats {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DAY_FIRST = DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ROOT);

    private IndianFormats() {}

    public static String rupees(BigDecimal amount) {
        if (amount == null) return "";
        return "Rs. " + indianGrouped(amount);
    }

    public static String qty(BigDecimal quantity) {
        return quantity == null ? "" : quantity.stripTrailingZeros().toPlainString();
    }

    public static String percent(BigDecimal pct) {
        return pct == null ? "" : pct.stripTrailingZeros().toPlainString();
    }

    public static String date(LocalDate date) {
        return date == null ? "" : DAY_FIRST.format(date);
    }

    public static String date(Instant instant) {
        return instant == null ? "" : DAY_FIRST.format(instant.atZone(IST));
    }

    /**
     * Indian (lakh/crore) digit grouping: the first group from the right is three
     * digits, every group after it is two — 1,23,456.78 and 1,00,00,000.00.
     *
     * Hand-rolled rather than delegating to DecimalFormat, which holds a single
     * groupingSize and silently ignores the secondary grouping in a "#,##,##0.00"
     * pattern, and rather than an en-IN locale lookup, which would tie this output
     * to whichever CLDR version ships with the JDK. This is a pure function.
     */
    private static String indianGrouped(BigDecimal amount) {
        BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_UP);
        boolean negative = scaled.signum() < 0;
        String plain = scaled.abs().toPlainString(); // setScale(2) guarantees a '.'
        int dot = plain.indexOf('.');
        String whole = plain.substring(0, dot);
        String fraction = plain.substring(dot);

        String grouped;
        if (whole.length() <= 3) {
            grouped = whole;
        } else {
            String lastThree = whole.substring(whole.length() - 3);
            String rest = whole.substring(0, whole.length() - 3);
            StringBuilder sb = new StringBuilder();
            int i = rest.length();
            while (i > 2) {
                sb.insert(0, "," + rest.substring(i - 2, i));
                i -= 2;
            }
            sb.insert(0, rest, 0, i);
            grouped = sb + "," + lastThree;
        }
        return (negative ? "-" : "") + grouped + fraction;
    }
}
