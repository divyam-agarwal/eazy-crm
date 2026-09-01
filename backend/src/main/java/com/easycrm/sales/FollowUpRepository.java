package com.easycrm.sales;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Unlike ActivityRepository, this one extends JpaRepository normally: a follow-up has its
 * own assigned_to, so it is filtered by VisibilityPolicy through VisibleFinder rather than
 * gated at a subject. Task 9 adds it to VisibilityScopingArchTest.GUARDED_REPOSITORIES,
 * after which every read here must go through VisibleFinder or the build fails.
 *
 * <p>Declare no custom finders. Any added would need a name in that test's shared
 * ALLOWED_METHODS set, which is a visibility decision requiring the same review as adding
 * a table to TenantScopingArchTest.GLOBAL_TABLES.
 */
public interface FollowUpRepository extends JpaRepository<FollowUp, UUID>, JpaSpecificationExecutor<FollowUp> {}
