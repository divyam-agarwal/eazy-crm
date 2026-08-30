package com.easycrm.sales;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.platform.visibility.SubjectType;
import com.easycrm.platform.visibility.VisibleFinder;
import com.easycrm.platform.web.PageResponse;
import com.easycrm.sales.web.dto.ActivityCreateRequest;
import com.easycrm.sales.web.dto.ActivityResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class ActivityService {

    private final ActivityRepository activities;
    private final VisibleFinder finder;
    private final Clock clock;

    public ActivityService(ActivityRepository activities, VisibleFinder finder, Clock clock) {
        this.activities = activities;
        this.finder = finder;
        this.clock = clock;
    }

    @Transactional
    public ActivityResponse create(ActivityCreateRequest req) {
        finder.requireVisibleSubject(req.subjectType(), req.subjectId());
        Instant now = clock.instant();
        Instant occurredAt = req.occurredAt() == null ? now : req.occurredAt();
        return ActivityResponse.of(activities.save(Activity.manual(
            req.subjectType(), req.subjectId(), req.type(), req.body(), req.outcome(),
            occurredAt, currentUserId(), now)));
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
     * only sound because this method has exactly ONE call site —
     * QuotationAcceptedActivityListener, added in a later task. Any new caller must be
     * able to make the same claim; one that cannot wants create() and the full gate.
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

    private static UUID currentUserId() {
        return TenantContext.get().map(TenantContext.TenantPrincipal::userId).orElse(null);
    }
}
