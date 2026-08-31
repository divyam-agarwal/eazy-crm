package com.easycrm.sales;

import com.easycrm.platform.error.ValidationException;
import com.easycrm.platform.visibility.SubjectType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure aggregate invariants. See spec 2026-08-30-activity-follow-up-design.md §7.2. */
class FollowUpTest {

    private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");
    private static final Instant DUE = Instant.parse("2026-09-02T04:30:00Z");
    private static final UUID SUBJECT = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();

    @Test
    void aNewFollowUpIsPending() {
        FollowUp f = pending();

        assertThat(f.getStatus()).isEqualTo(FollowUpStatus.PENDING);
        assertThat(f.getDueAt()).isEqualTo(DUE);
        assertThat(f.getAssignedTo()).isEqualTo(ME);
        assertThat(f.getCompletedAt()).isNull();
    }

    @Test
    void aPastDueDateIsAllowedOnCreate() {
        // "I should have called them yesterday" is real and useful to record. It lands in
        // scope=OVERDUE, which is exactly where it belongs (spec §7.2).
        FollowUp f = new FollowUp(SubjectType.ENQUIRY, SUBJECT,
            NOW.minusSeconds(86_400), ME, "overdue already", ME);

        assertThat(f.getStatus()).isEqualTo(FollowUpStatus.PENDING);
    }

    @Test
    void completingRecordsTheNoteAndTheTime() {
        FollowUp f = pending();
        f.complete("spoke to them, sending a revised quote", NOW);

        assertThat(f.getStatus()).isEqualTo(FollowUpStatus.DONE);
        assertThat(f.getCompletedAt()).isEqualTo(NOW);
        assertThat(f.getCompletionNote()).isEqualTo("spoke to them, sending a revised quote");
    }

    @Test
    void cancellingRecordsTheReason() {
        FollowUp f = pending();
        f.cancel("customer went with a competitor", NOW);

        assertThat(f.getStatus()).isEqualTo(FollowUpStatus.CANCELLED);
        assertThat(f.getCompletedAt()).isEqualTo(NOW);
        assertThat(f.getCompletionNote()).isEqualTo("customer went with a competitor");
    }

    @Test
    void cancellingWithoutAReasonIsRejected_andNothingIsMutated() {
        FollowUp f = pending();

        assertThatThrownBy(() -> f.cancel("  ", NOW))
            .isInstanceOf(ValidationException.class)
            .satisfies(e -> assertThat(((ValidationException) e).getFields())
                .containsKey("reason"));

        assertThat(f.getStatus()).isEqualTo(FollowUpStatus.PENDING);
        assertThat(f.getCompletedAt()).isNull();
        assertThat(f.getCompletionNote()).isNull();
    }

    @Test
    void completingTwiceIsRejected_andNothingIsMutated() {
        FollowUp f = pending();
        f.complete("done", NOW);
        Instant later = NOW.plusSeconds(3600);

        assertThatThrownBy(() -> f.complete("again", later))
            .isInstanceOf(ValidationException.class)
            .satisfies(e -> assertThat(((ValidationException) e).getFields().get("status"))
                .contains("done"));

        assertThat(f.getCompletedAt()).isEqualTo(NOW);
        assertThat(f.getCompletionNote()).isEqualTo("done");
    }

    @Test
    void cancellingACompletedFollowUpIsRejected_andNothingIsMutated() {
        FollowUp f = pending();
        f.complete("done", NOW);

        assertThatThrownBy(() -> f.cancel("changed my mind", NOW.plusSeconds(60)))
            .isInstanceOf(ValidationException.class);

        assertThat(f.getStatus()).isEqualTo(FollowUpStatus.DONE);
        assertThat(f.getCompletionNote()).isEqualTo("done");
    }

    @Test
    void reschedulingAPendingFollowUpMovesIt() {
        FollowUp f = pending();
        Instant newDue = DUE.plusSeconds(86_400);
        UUID someoneElse = UUID.randomUUID();

        f.reschedule(newDue, someoneElse, "pushed to Wednesday");

        assertThat(f.getDueAt()).isEqualTo(newDue);
        assertThat(f.getAssignedTo()).isEqualTo(someoneElse);
        assertThat(f.getNote()).isEqualTo("pushed to Wednesday");
    }

    @Test
    void reschedulingACancelledFollowUpIsRejected_andNothingIsMutated() {
        FollowUp f = pending();
        f.cancel("not interested", NOW);

        assertThatThrownBy(() -> f.reschedule(DUE.plusSeconds(86_400), ME, "revived"))
            .isInstanceOf(ValidationException.class);

        assertThat(f.getDueAt()).isEqualTo(DUE);
        assertThat(f.getNote()).isEqualTo("ring back about the rate");
    }

    @Test
    void anAssigneeIsRequired() {
        assertThatThrownBy(() -> new FollowUp(SubjectType.ENQUIRY, SUBJECT, DUE, null,
            "nobody owns this", ME))
            .isInstanceOf(ValidationException.class)
            .satisfies(e -> assertThat(((ValidationException) e).getFields())
                .containsKey("assignedTo"));
    }

    private static FollowUp pending() {
        return new FollowUp(SubjectType.ENQUIRY, SUBJECT, DUE, ME,
            "ring back about the rate", ME);
    }
}
