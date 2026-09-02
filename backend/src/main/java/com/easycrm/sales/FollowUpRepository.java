package com.easycrm.sales;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unlike ActivityRepository, this one extends JpaRepository normally: a follow-up has its
 * own assigned_to, so it is filtered by VisibilityPolicy through VisibleFinder rather than
 * gated at a subject.
 *
 * <p>Declare no custom READ finder here without adding its name to the shared
 * ALLOWED_METHODS set in VisibilityScopingArchTest — a visibility decision requiring the
 * same review as adding a table to TenantScopingArchTest.GLOBAL_TABLES. The one exception
 * on record is the count below, which is an invariant check and must not be filtered.
 */
public interface FollowUpRepository extends JpaRepository<FollowUp, UUID>, JpaSpecificationExecutor<FollowUp> {

    /** Tenant-wide, deliberately unfiltered — see AssignedWorkload. */
    @Transactional(readOnly = true)
    long countByAssignedToAndStatus(UUID assignedTo, FollowUpStatus status);
}
