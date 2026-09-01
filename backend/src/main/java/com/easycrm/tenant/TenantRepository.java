package com.easycrm.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {
    Optional<Tenant> findBySlug(String slug);

    /**
     * Tenants a scheduled job should act on. Callers pass TRIAL + ACTIVE; SUSPENDED is
     * deliberately excluded (spec 2026-08-31 D4). Tenant is a GLOBAL table -- no @TenantId,
     * no RLS -- so this is legitimately callable with no tenant context set, which is
     * exactly the situation a job starts in.
     */
    List<Tenant> findByStatusIn(Collection<TenantStatus> statuses);
}
