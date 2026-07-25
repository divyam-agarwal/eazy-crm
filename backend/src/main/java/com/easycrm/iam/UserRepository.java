package com.easycrm.iam;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    // Runs in a (tenant-aware) transaction so TenantAwareTransactionManager sets the
    // Postgres app.current_tenant GUC before the query — otherwise RLS sees no tenant and
    // returns zero rows. Spring Data does not wrap derived query methods in a transaction
    // by default; the concrete CRUD methods (save/findAll) inherit one from SimpleJpaRepository.
    @Transactional(readOnly = true)
    Optional<User> findByEmail(String email);
}
