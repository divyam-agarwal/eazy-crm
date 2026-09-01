package com.easycrm.iam;

import com.easycrm.platform.error.ValidationException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * "Is this user someone I can assign work to?" — extracted from the identical private
 * copies that had accumulated in EnquiryService and CustomerService when FollowUpService
 * needed a third. See spec 2026-08-30-activity-follow-up-design.md §7.4.
 *
 * <p>A null assignee is allowed and is a no-op: the funnel aggregates treat unassigned as
 * a legitimate pool state. FollowUp does not — it rejects a null owner in its own
 * constructor, which is a separate and stricter rule.
 */
@Component
public class AssignableUsers {

    private final UserRepository users;

    public AssignableUsers(UserRepository users) {
        this.users = users;
    }

    public void require(UUID userId) {
        if (userId == null) return;
        users.findById(userId)
                .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new ValidationException("assignedTo", "must be an active user"));
    }
}
