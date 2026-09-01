package com.easycrm.platform.job;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.tenant.Tenant;
import com.easycrm.tenant.TenantRepository;
import com.easycrm.tenant.TenantStatus;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a unit of work once per tenant, with no HTTP request and no JWT behind it. The
 * codebase's first non-request execution path; every later scheduled job should come
 * through here rather than growing its own tenant loop.
 *
 * <p>Three things it exists to get right, all of which fail SILENTLY when hand-rolled:
 *
 * <ol>
 *   <li><b>Ordering.</b> {@code runAs} wraps {@code tx.execute}, never the reverse.
 *       TenantAwareTransactionManager.doBegin reads TenantContext to set the
 *       app.current_tenant GUC, and Hibernate resolves a session's tenant once at
 *       session-open and never re-reads it (challenge #9). Context set after the
 *       transaction opens binds to nothing, and doBegin returns early leaving the GUC
 *       unset -- so scoped tables return ZERO ROWS instead of raising.</li>
 *   <li><b>Its own transaction, always.</b> The template is built here with
 *       PROPAGATION_REQUIRES_NEW rather than injecting Boot's autoconfigured
 *       TransactionTemplate, which is PROPAGATION_REQUIRED. That closes two traps, not one.
 *       A @Transactional method called from this class's own loop would be a
 *       self-invocation: the proxy bypassed, the per-tenant transaction silently joining
 *       the caller's. And a caller that ALREADY holds a transaction would make a REQUIRED
 *       template join it -- doBegin would never run, the GUC would stay unset, the session
 *       would have resolved NO_TENANT, and every scoped read would return zero rows, which
 *       is the very failure this class exists to prevent.</li>
 *   <li><b>Failure isolation.</b> One tenant's bad data must not abort the sweep.</li>
 * </ol>
 *
 * <p>The principal is synthetic: {@code (tenantId, null, "SYSTEM")}, the same shape
 * AuthService uses pre-authentication. VisibilityPolicy treats it as unrestricted (only
 * SALES_EXEC is restricted), which is correct -- a job must see the whole tenant -- and
 * AuditLog.actorUserId is nullable, so the null user id records honestly that no human
 * did this. See spec 2026-08-31-quotation-auto-expiry-design.md §3.1.
 */
@Component
public class TenantJobRunner {

    private static final Logger log = LoggerFactory.getLogger(TenantJobRunner.class);

    private static final List<TenantStatus> JOB_ELIGIBLE = List.of(TenantStatus.TRIAL, TenantStatus.ACTIVE);

    private final TenantRepository tenants;
    private final TransactionTemplate tx;

    public TenantJobRunner(TenantRepository tenants, PlatformTransactionManager transactionManager) {
        this.tenants = tenants;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** What one sweep did. Counts are for the log line, not for control flow. */
    public record JobSummary(int tenantsSwept, int tenantsFailed, int itemsProcessed) {}

    /**
     * Runs {@code body} once per job-eligible tenant, each in its own transaction with the
     * tenant context bound. The body returns how many items it processed, purely so the
     * summary can say something useful.
     *
     * <p>Reading the tenant list happens with NO tenant context, which is safe because
     * Tenant is a global table (TenantScopingArchTest.GLOBAL_TABLES).
     *
     * <p>The body MAY RUN TWICE for a tenant, because of the optimistic-lock retry. Database
     * work is rolled back between attempts, but anything that escapes the transaction --
     * notably a REQUIRES_NEW write such as AuditService.recordIndependently -- will be
     * duplicated. A body must be safe to re-run.
     */
    public JobSummary forEachTenant(String jobName, ToIntFunction<UUID> body) {
        int swept = 0;
        int failed = 0;
        int items = 0;

        for (Tenant tenant : tenants.findByStatusIn(JOB_ELIGIBLE)) {
            try {
                items += runWithRetry(jobName, tenant.getId(), body);
                swept++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("job {} failed for tenant {}", jobName, tenant.getId(), e);
            }
        }

        log.info("job {} finished: {} tenants swept, {} failed, {} items processed", jobName, swept, failed, items);
        return new JobSummary(swept, failed, items);
    }

    /**
     * One retry on optimistic-lock failure only. With one transaction per tenant, a user
     * updating a row mid-sweep rolls back that tenant's WHOLE batch, not just the contended
     * row -- so the common case (one user, one record, a sub-second window) would otherwise
     * cost a tenant an entire run. The retry is bounded at one: repeated contention within a
     * single sweep is rare and self-corrects on the next run. See spec §6.
     */
    private int runWithRetry(String jobName, UUID tenantId, ToIntFunction<UUID> body) {
        try {
            return runInTenant(tenantId, body);
        } catch (ObjectOptimisticLockingFailureException e) {
            log.info("job {} hit a concurrent update for tenant {}; retrying once", jobName, tenantId);
            return runInTenant(tenantId, body);
        }
    }

    /**
     * The ordering that matters. Note the typed Supplier local: TenantContext.runAs is
     * overloaded on Runnable and Supplier, and an expression lambda whose body is a method
     * call matches both -- inlining this would not compile ("reference to runAs is
     * ambiguous").
     */
    private int runInTenant(UUID tenantId, ToIntFunction<UUID> body) {
        Supplier<Integer> work = () -> tx.execute(status -> body.applyAsInt(tenantId));
        Integer processed = TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, null, "SYSTEM"), work);
        return processed == null ? 0 : processed;
    }
}


   
