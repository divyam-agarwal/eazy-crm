package com.easycrm.sales;

import com.easycrm.iam.AssignableUsers;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.platform.visibility.SubjectType;
import com.easycrm.platform.visibility.VisibleFinder;
import com.easycrm.platform.web.PageResponse;
import com.easycrm.sales.web.dto.ActivityCreateRequest;
import com.easycrm.sales.web.dto.ActivityResponse;
import com.easycrm.sales.web.dto.ActivityUpdateRequest;
import com.easycrm.sales.web.dto.NextFollowUpRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class ActivityService {

    private final ActivityRepository activities;
    private final FollowUpRepository followUps;
    private final VisibleFinder finder;
    private final AssignableUsers assignableUsers;
    private final Clock clock;

    public ActivityService(ActivityRepository activities, FollowUpRepository followUps,
                           VisibleFinder finder, AssignableUsers assignableUsers, Clock clock) {
        this.activities = activities;
        this.followUps = followUps;
        this.finder = finder;
        this.assignableUsers = assignableUsers;
        this.clock = clock;
    }

    /**
     * The subject is resolved ONCE and reused for both rows, and both are written in one
     * transaction: two round-trips means the second can fail somewhere and the follow-up —
     * the half this whole feature exists to protect — is what goes missing (spec §6.1).
     */
    @Transactional
    public ActivityResponse create(ActivityCreateRequest req) {
        finder.requireVisibleSubject(req.subjectType(), req.subjectId());
        Instant now = clock.instant();
        Instant occurredAt = req.occurredAt() == null ? now : req.occurredAt();
        Activity saved = activities.save(Activity.manual(
            req.subjectType(), req.subjectId(), req.type(), req.body(), req.outcome(),
            occurredAt, currentUserId(), now));

        UUID followUpId = null;
        NextFollowUpRequest next = req.nextFollowUp();
        if (next != null) {
            assignableUsers.require(next.assignedTo());
            followUpId = followUps.save(new FollowUp(req.subjectType(), req.subjectId(),
                next.dueAt(), next.assignedTo(), next.note(), currentUserId())).getId();
        }
        return ActivityResponse.of(saved, followUpId);
    }

    @Transactional(readOnly = true)
    public PageResponse<ActivityResponse> list(SubjectType subjectType, UUID subjectId,
                                               Pageable pageable) {
        finder.requireVisibleSubject(subjectType, subjectId);
        return PageResponse.of(activities
            .findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(subjectType, subjectId, pageable)
            .map(ActivityResponse::of));
    }

    /**
     * Writes a SYSTEM activity for something the application observed. Deliberately does
     * NOT call requireVisibleSubject: the caller is an internal flow that has already
     * loaded and authorised the subject (an event listener, or a follow-up transition on a
     * row the caller just read through VisibleFinder). Re-resolving would be a second
     * query for no gain, and worse, it would fail outright for a listener running under a
     * synthetic principal that VisibilityPolicy treats as unrestricted-but-userless.
     *
     * <p>The safety argument is therefore "the caller already passed the gate", which is
     * only sound because every call site can make it. There are two:
     * QuotationAcceptedActivityListener, and QuotationExpiredActivityListener (whose
     * subject was loaded through VisibleFinder.listQuotations inside
     * QuotationExpirySweep). Any new caller must be able to make the same claim; one that
     * cannot wants create() and the full gate.
     * (The activity written when a follow-up is completed does NOT come through here: a
     * user typed that one, so it must stay editable and goes through
     * logManualForGatedCaller instead.)
     */
    @Transactional
    public void logSystem(SubjectType subjectType, UUID subjectId, ActivityType type,
                          String body, UUID actorUserId) {
        Instant now = clock.instant();
        activities.save(Activity.system(subjectType, subjectId, type, body, actorUserId, now));
    }

    /**
     * A MANUAL activity written on behalf of a caller that has ALREADY passed the subject
     * gate — currently only FollowUpService.complete, which loaded its follow-up through
     * VisibleFinder, whose subject was gated when that row was created.
     *
     * <p>Distinct from logSystem in exactly one way that matters: these rows are editable,
     * because a human wrote them. Any new caller must be able to make the same
     * already-gated claim; if it cannot, it wants create() and the full gate.
     */
    @Transactional
    public void logManualForGatedCaller(SubjectType subjectType, UUID subjectId,
                                        ActivityType type, String body, String outcome) {
        Instant now = clock.instant();
        activities.save(Activity.manual(subjectType, subjectId, type, body, outcome,
            now, currentUserId(), now));
    }

    /**
     * Full replace of the two editable fields, matching the house PATCH convention: an
     * omitted body or outcome is CLEARED, not preserved (see deferred-backlog item 8).
     *
     * <p>Note the ordering: the subject gate runs first, so an activity on an invisible
     * subject 404s before ownership is ever considered. Ownership then yields 422, which
     * is correct rather than a departure from the 404 rule — the row is already provably
     * visible to this caller, so a 404 would reveal nothing extra and would actively
     * mislead a client into retrying a GET that succeeds (spec §7.1).
     */
    @Transactional
    public ActivityResponse update(UUID id, ActivityUpdateRequest req) {
        finder.requireVisibleSubject(req.subjectType(), req.subjectId());
        Activity a = activities
            .findByIdAndSubjectTypeAndSubjectId(id, req.subjectType(), req.subjectId())
            .orElseThrow(() -> new NotFoundException("activity " + id + " was not found"));
        a.edit(req.body(), req.outcome(), currentUserId());
        return ActivityResponse.of(a);
    }

    private static UUID currentUserId() {
        return TenantContext.get().map(TenantContext.TenantPrincipal::userId).orElse(null);
    }
}
