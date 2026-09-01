package com.easycrm.sales;

import com.easycrm.iam.AssignedWorkload;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * PENDING follow-ups assigned to a member. The sharpest of the three: follow_up.assigned_to
 * is NOT NULL and VisibilityPolicy filters on it intrinsically, so a PENDING follow-up left
 * with a disabled member is invisible to every other SALES_EXEC and will never be actioned —
 * exactly the failure the activity/follow-up feature exists to prevent.
 */
@Component
public class FollowUpWorkload implements AssignedWorkload {

    private final FollowUpRepository followUps;

    public FollowUpWorkload(FollowUpRepository followUps) {
        this.followUps = followUps;
    }

    @Override
    public String label() {
        return "follow-ups";
    }

    @Override
    public long countOpenFor(UUID userId) {
        return followUps.countByAssignedToAndStatus(userId, FollowUpStatus.PENDING);
    }
}
