package com.easycrm.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easycrm.platform.error.ValidationException;
import com.easycrm.platform.visibility.SubjectType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure aggregate invariants. See spec 2026-08-30-activity-follow-up-design.md §7.1. */
class ActivityTest {

    private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");
    private static final UUID SUBJECT = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    private static final UUID SOMEONE_ELSE = UUID.randomUUID();

    @Test
    void aManualActivityRecordsWhoLoggedItAndIsMarkedManual() {
        Activity a = manual(NOW.minusSeconds(3600), ME);

        assertThat(a.getLoggedBy()).isEqualTo(ME);
        assertThat(a.getSource()).isEqualTo(ActivitySource.MANUAL);
        assertThat(a.getSubjectType()).isEqualTo(SubjectType.ENQUIRY);
        assertThat(a.getSubjectId()).isEqualTo(SUBJECT);
    }

    @Test
    void occurredAtMayBeInThePast() {
        assertThat(manual(NOW.minusSeconds(86_400), ME).getOccurredAt()).isEqualTo(NOW.minusSeconds(86_400));
    }

    @Test
    void occurredAtMayBeExactlyNow() {
        assertThat(manual(NOW, ME).getOccurredAt()).isEqualTo(NOW);
    }

    @Test
    void occurredAtInTheFutureIsRejected() {
        assertThatThrownBy(() -> manual(NOW.plusSeconds(1), ME))
                .isInstanceOf(ValidationException.class)
                .satisfies(
                        e -> assertThat(((ValidationException) e).getFields()).containsKey("occurredAt"));
    }

    @Test
    void theLoggerMayEditTheirOwnEntry() {
        Activity a = manual(NOW, ME);
        a.edit("corrected body", "spoke to the owner", ME);

        assertThat(a.getBody()).isEqualTo("corrected body");
        assertThat(a.getOutcome()).isEqualTo("spoke to the owner");
    }

    @Test
    void anotherUserCannotEditIt_andNothingIsMutated() {
        Activity a = manual(NOW, ME);

        assertThatThrownBy(() -> a.edit("hijacked", "nope", SOMEONE_ELSE)).isInstanceOf(ValidationException.class);

        assertThat(a.getBody()).isEqualTo("rang them");
        assertThat(a.getOutcome()).isEqualTo("no answer");
    }

    @Test
    void aSystemActivityCannotBeEdited_andNothingIsMutated() {
        Activity a = Activity.system(SubjectType.QUOTATION, SUBJECT, ActivityType.NOTE, "Quotation accepted", ME, NOW);

        assertThatThrownBy(() -> a.edit("rewritten", null, ME)).isInstanceOf(ValidationException.class);

        assertThat(a.getBody()).isEqualTo("Quotation accepted");
        assertThat(a.getSource()).isEqualTo(ActivitySource.SYSTEM);
    }

    private static Activity manual(Instant occurredAt, UUID loggedBy) {
        return Activity.manual(
                SubjectType.ENQUIRY, SUBJECT, ActivityType.CALL, "rang them", "no answer", occurredAt, loggedBy, NOW);
    }
}
