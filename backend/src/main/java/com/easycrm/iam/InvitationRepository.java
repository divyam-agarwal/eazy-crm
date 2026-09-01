package com.easycrm.iam;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    // Derived queries are not transactional by default, which would leave the RLS GUC
    // unset. invitation has no RLS, but the annotation keeps these consistent with the
    // rest of the codebase and correct if a policy is ever added — the same reasoning
    // ShareLinkRepository documents.
    @Transactional(readOnly = true)
    Optional<Invitation> findByTokenHash(String tokenHash);

    @Transactional(readOnly = true)
    List<Invitation> findByTenantIdAndStatus(UUID tenantId, InvitationStatus status);
}
