package com.easycrm.sales;

import com.easycrm.platform.error.ValidationException;
import com.easycrm.platform.persistence.TenantScopedEntity;
import com.easycrm.platform.visibility.SubjectType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A due-dated task against one of the four funnel aggregates — the thing the product's
 * "you never lose a follow-up" promise is made of. See spec
 * 2026-08-30-activity-follow-up-design.md §5.2, §7.2.
 *
 * <p>Unlike Activity, this row carries its own assigned_to and therefore has intrinsic
 * visibility: it joins the guarded-repository set and is filtered by VisibilityPolicy
 * (§4.1). That asymmetry is deliberate, not an oversight.
 */
@Entity
@Table(name = "follow_up")
public class FollowUp extends TenantScopedEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, updatable = false, length = 16)
    private SubjectType subjectType;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    /**
     * NOT NULL by design. A follow-up nobody owns is precisely the failure this feature
     * exists to prevent, which is also why VisibilityPolicy.followUps() is a plain
     * equality rather than the ownedOrUnassigned() shape the other aggregates use — the
     * IS NULL branch would be unreachable (§4.1).
     */
    @Column(name = "assigned_to", nullable = false)
    private UUID assignedTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FollowUpStatus status;

    @Column(length = 500)
    private String note;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** The note sent to /complete, or the reason sent to /cancel — status says which. */
    @Column(name = "completion_note", length = 500)
    private String completionNote;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    protected FollowUp() {}

    public FollowUp(
            SubjectType subjectType, UUID subjectId, Instant dueAt, UUID assignedTo, String note, UUID createdBy) {
        if (assignedTo == null) {
            throw new ValidationException("assignedTo", "a follow-up must have an owner");
        }
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.dueAt = dueAt;
        this.assignedTo = assignedTo;
        this.note = note;
        this.createdBy = createdBy;
        this.status = FollowUpStatus.PENDING;
    }

    /** Move, reassign, or re-word a pending follow-up. Full replace, per house PATCH. */
    public void reschedule(Instant dueAt, UUID assignedTo, String note) {
        requirePending("rescheduled");
        if (assignedTo == null) {
            throw new ValidationException("assignedTo", "a follow-up must have an owner");
        }
        this.dueAt = dueAt;
        this.assignedTo = assignedTo;
        this.note = note;
    }

    public void complete(String completionNote, Instant now) {
        requirePending("completed");
        this.status = FollowUpStatus.DONE;
        this.completedAt = now;
        this.completionNote = completionNote;
    }

    public void cancel(String reason, Instant now) {
        requirePending("cancelled");
        if (reason == null || reason.isBlank()) {
            throw new ValidationException("reason", "a reason is required to cancel a follow-up");
        }
        this.status = FollowUpStatus.CANCELLED;
        this.completedAt = now;
        this.completionNote = reason;
    }

    /**
     * Every guard runs BEFORE any assignment, so a rejected transition leaves the row
     * untouched. FollowUpTest asserts that directly rather than only asserting the
     * exception type — deferred-backlog item 11 records what happens when it does not.
     */
    private void requirePending(String verb) {
        if (status.isTerminal()) {
            throw new ValidationException(
                    "status", "a " + status.name().toLowerCase() + " follow-up cannot be " + verb);
        }
    }

    public SubjectType getSubjectType() {
        return subjectType;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public UUID getAssignedTo() {
        return assignedTo;
    }

    public FollowUpStatus getStatus() {
        return status;
    }

    public String getNote() {
        return note;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public String getCompletionNote() {
        return completionNote;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }
}
