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
 * A logged contact against one of the four funnel aggregates. Append-mostly: body and
 * outcome may be corrected by whoever logged the row; nothing else ever changes and
 * nothing is ever deleted. See spec 2026-08-30-activity-follow-up-design.md §5.1, §7.1.
 *
 * <p>Visibility is derived from the SUBJECT, not from this row — there is no assigned_to
 * here. ActivityRepository declares no read that is not subject-scoped, so every path to
 * an Activity passes VisibleFinder.requireVisibleSubject first (§4.2).
 */
@Entity
@Table(name = "activity")
public class Activity extends TenantScopedEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, updatable = false, length = 16)
    private SubjectType subjectType;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private ActivityType type;

    @Column(length = 2000)
    private String body;

    /**
     * Free text, deliberately not an enum: the parent spec names the field but never
     * enumerates its values, and nothing reports on it yet. Promoting it once real
     * outcomes have been observed is a migration; inventing the wrong enum now and
     * living with it is the expensive direction (spec §5.1).
     */
    @Column(length = 200)
    private String outcome;

    /**
     * When the contact happened, which is NOT when the row was written — a 3pm call gets
     * logged at 9pm after the shop closes. The timeline sorts on this; createdAt remains
     * the immutable record of insertion.
     */
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "logged_by", updatable = false)
    private UUID loggedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 8)
    private ActivitySource source;

    protected Activity() {}

    private Activity(
            SubjectType subjectType,
            UUID subjectId,
            ActivityType type,
            String body,
            String outcome,
            Instant occurredAt,
            UUID loggedBy,
            ActivitySource source,
            Instant now) {
        if (occurredAt.isAfter(now)) {
            throw new ValidationException("occurredAt", "cannot log a contact that has not happened yet");
        }
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.type = type;
        this.body = body;
        this.outcome = outcome;
        this.occurredAt = occurredAt;
        this.loggedBy = loggedBy;
        this.source = source;
    }

    /** A human logged this. {@code now} is passed in so the invariant is unit-testable. */
    public static Activity manual(
            SubjectType subjectType,
            UUID subjectId,
            ActivityType type,
            String body,
            String outcome,
            Instant occurredAt,
            UUID loggedBy,
            Instant now) {
        return new Activity(
                subjectType, subjectId, type, body, outcome, occurredAt, loggedBy, ActivitySource.MANUAL, now);
    }

    /**
     * The application logged this in response to something it observed. occurredAt is
     * always now — a system event happens when it happens.
     */
    public static Activity system(
            SubjectType subjectType, UUID subjectId, ActivityType type, String body, UUID actorUserId, Instant now) {
        return new Activity(subjectType, subjectId, type, body, null, now, actorUserId, ActivitySource.SYSTEM, now);
    }

    /**
     * Correct a typo. Scoped to body and outcome: changing which enquiry a call was about,
     * or when it happened, is rewriting history rather than correcting a mistake.
     *
     * <p>Both guards run BEFORE any assignment, so a rejected edit leaves the row
     * untouched — asserted directly in ActivityTest.
     */
    public void edit(String body, String outcome, UUID editorUserId) {
        if (source == ActivitySource.SYSTEM) {
            throw new ValidationException("id", "a system-logged activity cannot be edited");
        }
        if (loggedBy == null || !loggedBy.equals(editorUserId)) {
            throw new ValidationException("id", "only the user who logged an activity may edit it");
        }
        this.body = body;
        this.outcome = outcome;
    }

    public SubjectType getSubjectType() {
        return subjectType;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public ActivityType getType() {
        return type;
    }

    public String getBody() {
        return body;
    }

    public String getOutcome() {
        return outcome;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public UUID getLoggedBy() {
        return loggedBy;
    }

    public ActivitySource getSource() {
        return source;
    }
}
