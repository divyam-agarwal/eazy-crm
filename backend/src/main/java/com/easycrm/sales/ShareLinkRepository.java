package com.easycrm.sales;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface ShareLinkRepository extends JpaRepository<ShareLink, UUID> {

    // Derived queries are not transactional by default, which would leave the RLS GUC
    // unset. share_link has no RLS, but the annotation keeps these consistent with the
    // rest of the codebase and correct if a policy is ever added.
    @Transactional(readOnly = true)
    Optional<ShareLink> findByToken(String token);

    @Transactional(readOnly = true)
    Optional<ShareLink> findByQuotationVersionId(UUID quotationVersionId);
}
