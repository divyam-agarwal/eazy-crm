package com.easycrm.iam;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface UserRepository extends JpaRepository<User, UUID> {

    // Runs in a (tenant-aware) transaction so TenantAwareTransactionManager sets the
    // Postgres app.current_tenant GUC before the query — otherwise RLS sees no tenant and
    // returns zero rows. Spring Data does not wrap derived query methods in a transaction
    // by default; the concrete CRUD methods (save/findAll) inherit one from SimpleJpaRepository.
    @Transactional(readOnly = true)
    Optional<User> findByEmail(String email);

    /**
     * Membership by address, not by spelling. An email address is one identity however it
     * is capitalised, so "is this person already in the workspace?" must fold case — see
     * uq_user_tenant_email_lower (V32), which is the structural half of the same rule.
     * At most one row can match, because that index says so.
     */
    @Transactional(readOnly = true)
    Optional<User> findByEmailIgnoreCase(String email);
}
