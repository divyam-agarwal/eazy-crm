package com.easycrm.platform.time;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Midnight correctness, proven with no Spring at all. This is why the Clock bean is never
 * overridden in an integration test: doing so would fork the context every IntegrationTest
 * subclass shares, and these edges are exactly what needed determinism. See spec
 * 2026-08-30-activity-follow-up-design.md §7.3.
 *
 * <p>IST is UTC+5:30, so an IST day runs from 18:30 UTC the previous day to 18:30 UTC.
 */
class DueWindowTest {

    @Test
    void middayIstResolvesToThatIstDay() {
        // 2026-08-30 12:00 IST == 2026-08-30 06:30 UTC
        DueWindow.Window w = DueWindow.today(Instant.parse("2026-08-30T06:30:00Z"));

        assertThat(w.startOfToday()).isEqualTo(Instant.parse("2026-08-29T18:30:00Z"));
        assertThat(w.endOfToday()).isEqualTo(Instant.parse("2026-08-30T18:30:00Z"));
    }

    @Test
    void oneSecondBeforeIstMidnightIsStillTheSameDay() {
        // 2026-08-30 23:59:59 IST == 2026-08-30 18:29:59 UTC
        DueWindow.Window w = DueWindow.today(Instant.parse("2026-08-30T18:29:59Z"));

        assertThat(w.startOfToday()).isEqualTo(Instant.parse("2026-08-29T18:30:00Z"));
        assertThat(w.endOfToday()).isEqualTo(Instant.parse("2026-08-30T18:30:00Z"));
    }

    @Test
    void istMidnightExactlyRollsToTheNextDay() {
        // 2026-08-31 00:00:00 IST == 2026-08-30 18:30:00 UTC
        DueWindow.Window w = DueWindow.today(Instant.parse("2026-08-30T18:30:00Z"));

        assertThat(w.startOfToday()).isEqualTo(Instant.parse("2026-08-30T18:30:00Z"));
        assertThat(w.endOfToday()).isEqualTo(Instant.parse("2026-08-31T18:30:00Z"));
    }

    @Test
    void aUtcInstantLateInTheEveningFallsOnTheNextIstDay() {
        // 2026-08-30 20:00 UTC == 2026-08-31 01:30 IST — the case a naive UTC-based
        // implementation gets wrong, and the reason this class exists at all.
        DueWindow.Window w = DueWindow.today(Instant.parse("2026-08-30T20:00:00Z"));

        assertThat(w.startOfToday()).isEqualTo(Instant.parse("2026-08-30T18:30:00Z"));
        assertThat(w.endOfToday()).isEqualTo(Instant.parse("2026-08-31T18:30:00Z"));
    }

    @Test
    void theWindowIsExactlyTwentyFourHours() {
        DueWindow.Window w = DueWindow.today(Instant.parse("2026-08-30T06:30:00Z"));

        assertThat(java.time.Duration.between(w.startOfToday(), w.endOfToday()))
            .isEqualTo(java.time.Duration.ofHours(24));
    }
}
