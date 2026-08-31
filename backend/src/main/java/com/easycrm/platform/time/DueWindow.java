package com.easycrm.platform.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Today's boundaries in IST, as instants. Pure and static so midnight correctness can be
 * proven without Spring — see DueWindowTest and spec §7.3.
 *
 * <p>Every tenant is Indian by product definition, so the zone is a constant rather than a
 * per-tenant column; adding that column before a non-Indian tenant exists would be
 * speculative. The value matches IndianFormats' own IST field, which is private to that
 * class; this is the same zone, not a second opinion about it.
 */
public final class DueWindow {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private DueWindow() {}

    /** Half-open: {@code [startOfToday, endOfToday)}. */
    public record Window(Instant startOfToday, Instant endOfToday) {}

    public static Window today(Instant now) {
        LocalDate todayInIst = todayDate(now);
        return new Window(
            todayInIst.atStartOfDay(IST).toInstant(),
            todayInIst.plusDays(1).atStartOfDay(IST).toInstant());
    }

    /**
     * Today's date in IST. Distinct from {@link #today(Instant)}, which returns the day's
     * instant boundaries; this returns the calendar date itself, for comparison against a
     * {@code LocalDate} column such as {@code quotation_version.valid_until} that a user
     * entered in IST. Comparing such a column against a UTC date would expire quotations
     * 5½ hours early every day.
     */
    public static LocalDate todayDate(Instant now) {
        return now.atZone(IST).toLocalDate();
    }
}
