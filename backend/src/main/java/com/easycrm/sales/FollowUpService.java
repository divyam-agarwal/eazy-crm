package com.easycrm.sales;

import com.easycrm.iam.AssignableUsers;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.platform.time.DueWindow;
import com.easycrm.platform.visibility.SubjectType;
import com.easycrm.platform.visibility.VisibleFinder;
import com.easycrm.platform.web.PageResponse;
import com.easycrm.sales.web.dto.FollowUpCompleteRequest;
import com.easycrm.sales.web.dto.FollowUpCreateRequest;
import com.easycrm.sales.web.dto.FollowUpResponse;
import com.easycrm.sales.web.dto.FollowUpSummaryResponse;
import com.easycrm.sales.web.dto.FollowUpUpdateRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class FollowUpService {

    private final FollowUpRepository followUps;
    private final VisibleFinder finder;
    private final AssignableUsers assignableUsers;
    private final ActivityService activities;
    private final Clock clock;

    public FollowUpService(FollowUpRepository followUps, VisibleFinder finder,
                           AssignableUsers assignableUsers, ActivityService activities,
                           Clock clock) {
        this.followUps = followUps;
        this.finder = finder;
        this.assignableUsers = assignableUsers;
        this.activities = activities;
        this.clock = clock;
    }

    @Transactional
    public FollowUpResponse create(FollowUpCreateRequest req) {
        finder.requireVisibleSubject(req.subjectType(), req.subjectId());
        assignableUsers.require(req.assignedTo());
        FollowUp saved = followUps.save(new FollowUp(req.subjectType(), req.subjectId(),
            req.dueAt(), req.assignedTo(), req.note(), currentUserId()));
        return FollowUpResponse.of(saved, clock.instant());
    }

    @Transactional(readOnly = true)
    public FollowUpResponse get(UUID id) {
        return FollowUpResponse.of(find(id), clock.instant());
    }

    @Transactional(readOnly = true)
    public PageResponse<FollowUpResponse> list(FollowUpScope scope, FollowUpStatus status,
                                               UUID assignedTo, SubjectType subjectType,
                                               UUID subjectId, Pageable pageable) {
        Instant now = clock.instant();
        Instant endOfToday = DueWindow.today(now).endOfToday();
        return PageResponse.of(finder.pageFollowUps(
                FollowUpSpecifications.filter(scope, status, assignedTo, subjectType,
                    subjectId, now, endOfToday),
                pageable)
            .map(f -> FollowUpResponse.of(f, now)));
    }

    /**
     * The dashboard tile. Counts are taken through pageFollowUps rather than a count query
     * so they pass through exactly the same visibility filter as the lists they summarise
     * — a summary that counted rows the list would not show is worse than no summary.
     */
    @Transactional(readOnly = true)
    public FollowUpSummaryResponse summary() {
        Instant now = clock.instant();
        Instant endOfToday = DueWindow.today(now).endOfToday();
        return new FollowUpSummaryResponse(
            countIn(FollowUpScope.OVERDUE, now, endOfToday),
            countIn(FollowUpScope.DUE_TODAY, now, endOfToday),
            countIn(FollowUpScope.UPCOMING, now, endOfToday));
    }

    private long countIn(FollowUpScope scope, Instant now, Instant endOfToday) {
        return finder.pageFollowUps(
            FollowUpSpecifications.filter(scope, null, null, null, null, now, endOfToday),
            PageRequest.of(0, 1)).getTotalElements();
    }

    /** Full-header-replace, per house PATCH convention. Rejected by the aggregate once terminal. */
    @Transactional
    public FollowUpResponse update(UUID id, FollowUpUpdateRequest req) {
        FollowUp f = find(id);
        assignableUsers.require(req.assignedTo());
        f.reschedule(req.dueAt(), req.assignedTo(), req.note());
        return FollowUpResponse.of(f, clock.instant());
    }

    /**
     * Completes the follow-up and, when the request carries an activity type, logs that
     * activity against the follow-up's OWN subject in the same transaction (spec §6.2).
     *
     * <p>The activity is MANUAL, not SYSTEM: a user typed that body, so they must be able
     * to correct it later, and a SYSTEM row is permanently uneditable. It goes through
     * logManualForGatedCaller rather than the normal create path because the subject does
     * not need re-resolving — find(id) above already loaded this row through VisibleFinder,
     * and the follow-up's subject was itself gated when the row was created.
     */
    @Transactional
    public FollowUpResponse complete(UUID id, FollowUpCompleteRequest req) {
        FollowUp f = find(id);
        Instant now = clock.instant();
        f.complete(req.note(), now);
        if (req.type() != null) {
            activities.logManualForGatedCaller(f.getSubjectType(), f.getSubjectId(),
                req.type(), req.body(), req.outcome());
        }
        return FollowUpResponse.of(f, now);
    }

    @Transactional
    public FollowUpResponse cancel(UUID id, String reason) {
        FollowUp f = find(id);
        Instant now = clock.instant();
        f.cancel(reason, now);
        return FollowUpResponse.of(f, now);
    }

    /** Visibility-filtered load; 404 when the caller may not see it. Used by transitions. */
    FollowUp find(UUID id) {
        return finder.findFollowUp(id)
            .orElseThrow(() -> new NotFoundException("follow-up " + id + " was not found"));
    }

    private static UUID currentUserId() {
        return TenantContext.get().map(TenantContext.TenantPrincipal::userId).orElse(null);
    }
}
