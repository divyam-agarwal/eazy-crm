package com.easycrm.iam;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    // @Transactional so the tenant GUC is set before this derived query runs against the
    // RLS-scoped audit_log table even when called outside a service transaction; see
    // engineering-challenges #8.
    @Transactional(readOnly = true)
    long countByAction(String action);

    // Same RLS caveat as countByAction above: needs its own @Transactional to see the
    // tenant GUC when called outside a service transaction.
    @Transactional(readOnly = true)
    Optional<AuditLog> findFirstByAction(String action);
}
