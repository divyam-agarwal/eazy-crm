package com.easycrm.tenant;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {
    Optional<Tenant> findBySlug(String slug);

    /**
     * Tenants a scheduled job should act on. Callers pass TRIAL + ACTIVE; SUSPENDED is
     * deliberately excluded (spec 2026-08-31 D4). Tenant is a GLOBAL table -- no @TenantId,
     * no RLS -- so this is legitimately callable with no tenant context set, which is
     * exactly the situation a job starts in.
     */
    List<Tenant> findByStatusIn(Collection<TenantStatus> statuses);

    /**
     * PESSIMISTIC_WRITE -> SELECT ... FOR UPDATE on one tenant row. Member-admin writes take
     * it so the last-active-owner check cannot lose to write skew: two owners demoting each
     * other concurrently both pass a plain count, write DISJOINT rows (so @Version sees no
     * conflict), and strand the workspace at zero owners. Postgres REPEATABLE READ does not
     * detect write skew; only SERIALIZABLE does, and that would mean retry handling
     * everywhere. Locking the row both transactions must touch materialises the conflict
     * instead. Same idiom as DocumentCounterRepository.findForUpdate.
     *
     * <p>tenant is a GLOBAL table, so this needs no RLS context of its own.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Tenant t where t.id = :id")
    Optional<Tenant> findForUpdate(@Param("id") UUID id);
}
