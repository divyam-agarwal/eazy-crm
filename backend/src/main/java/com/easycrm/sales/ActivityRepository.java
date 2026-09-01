package com.easycrm.sales;

import com.easycrm.platform.visibility.SubjectType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/**
 * EXTENDS THE BARE {@code Repository} MARKER ON PURPOSE — do not "fix" this to
 * JpaRepository. Every other repository here extends JpaRepository, which inherits
 * findById/findAll/findAllById. Those methods are not DECLARED on the sub-interface, so a
 * guard phrased over declared methods would happily pass a service calling
 * {@code activities.findById(id)} with no subject resolution at all. Repository is a pure
 * marker and inherits nothing, so the three methods below are the complete set of
 * operations that exist: an activity cannot be read without naming a subject, because
 * there is no method that lets you.
 *
 * <p>ActivityRepositoryScopingArchTest fails the build if this supertype changes or if a
 * non-subject-scoped read is added. See spec 2026-08-30-activity-follow-up-design.md §4.2, §8.
 */
public interface ActivityRepository extends Repository<Activity, UUID> {

    Activity save(Activity activity);

    Page<Activity> findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(
            SubjectType subjectType, UUID subjectId, Pageable pageable);

    Optional<Activity> findByIdAndSubjectTypeAndSubjectId(UUID id, SubjectType subjectType, UUID subjectId);
}
