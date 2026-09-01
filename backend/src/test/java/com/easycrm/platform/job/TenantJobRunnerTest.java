package com.easycrm.platform.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.crm.CustomerSource;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.tenant.Tenant;
import com.easycrm.tenant.TenantRepository;
import com.easycrm.tenant.TenantStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The seam every future scheduled job inherits. The isolation test below is the one that
 * matters: it is the only thing proving runAs ran BEFORE the transaction opened, which is
 * the failure mode that returns zero rows instead of raising. See spec 2026-08-31 §3.1.
 */
@SpringBootTest
class TenantJobRunnerTest extends IntegrationTest {

    @Autowired
    TenantJobRunner runner;

    @Autowired
    TenantRepository tenants;

    @Autowired
    CustomerRepository customers;

    @Autowired
    TransactionTemplate outerTx; // Boot's autoconfigured, PROPAGATION_REQUIRED bean --
    // stands in for a transactional caller.

    private UUID trialA, activeB, suspendedC;
    private String slugSeed;

    @BeforeEach
    void seed() {
        slugSeed = UUID.randomUUID().toString().substring(0, 8);
        trialA = newTenant("trial-a", TenantStatus.TRIAL);
        activeB = newTenant("active-b", TenantStatus.ACTIVE);
        suspendedC = newTenant("suspended-c", TenantStatus.SUSPENDED);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void sweepsTrialAndActiveTenantsAndSkipsSuspendedOnes() {
        List<UUID> visited = new ArrayList<>();
        runner.forEachTenant("test-job", t -> {
            visited.add(t);
            return 0;
        });

        assertThat(visited).contains(trialA, activeB);
        assertThat(visited).doesNotContain(suspendedC);
    }

    @Test
    void bindsTheTenantContextInsideTheBody() {
        List<UUID> seenInContext = new ArrayList<>();
        runner.forEachTenant("test-job", t -> {
            seenInContext.add(TenantContext.tenantId());
            return 0;
        });

        assertThat(seenInContext).contains(trialA, activeB);
    }

    /**
     * THE load-bearing test. Each tenant gets a customer; the body counts the customers it
     * can see. If runAs and tx.execute were ordered the other way round the GUC would be
     * unset, RLS would return zero rows, and every count would be 0 rather than 1.
     */
    @Test
    void eachTenantsBodySeesOnlyItsOwnRows() {
        seedOneCustomerFor(trialA);
        seedOneCustomerFor(activeB);

        Map<UUID, Long> counts = new HashMap<>();
        runner.forEachTenant("test-job", t -> {
            counts.put(t, customers.count());
            return 0;
        });

        // Each of MY tenants sees exactly its own single customer -- never zero (context
        // not bound) and never the cross-tenant total (isolation broken). Keyed by tenant
        // rather than asserted over every count: the sweep also visits tenants left behind
        // by other test classes, which have no customers and would contribute 0.
        assertThat(counts).containsEntry(trialA, 1L);
        assertThat(counts).containsEntry(activeB, 1L);
    }

    /**
     * Pins the fix for the REQUIRED-vs-REQUIRES_NEW gap: if the injected TransactionTemplate
     * were Boot's autoconfigured (PROPAGATION_REQUIRED) bean, a caller that already holds a
     * transaction would make the per-tenant work join it -- doBegin would never run, the GUC
     * would stay unset, and the count below would come back 0 instead of 1.
     */
    @Test
    void opensItsOwnTransactionEvenWhenTheCallerAlreadyHasOne() {
        seedOneCustomerFor(trialA);

        Map<UUID, Long> counts = new HashMap<>();
        outerTx.execute(status -> {
            runner.forEachTenant("test-job", t -> {
                counts.put(t, customers.count());
                return 0;
            });
            return null;
        });

        assertThat(counts).containsEntry(trialA, 1L);
    }

    @Test
    void oneFailingTenantDoesNotAbortTheSweep() {
        List<UUID> visited = new ArrayList<>();
        TenantJobRunner.JobSummary summary = runner.forEachTenant("test-job", t -> {
            visited.add(t);
            if (t.equals(trialA)) throw new IllegalStateException("boom");
            return 1;
        });

        assertThat(visited).contains(trialA, activeB); // reached the later tenant anyway
        assertThat(summary.tenantsFailed()).isGreaterThanOrEqualTo(1);
        // A failed tenant contributes neither an item nor a swept count -- catches an
        // implementation that increments "swept" in a finally regardless of outcome.
        assertThat(summary.itemsProcessed()).isEqualTo(summary.tenantsSwept());
    }

    @Test
    void retriesATenantOnceAfterAnOptimisticLockFailure() {
        AtomicInteger attemptsForA = new AtomicInteger();
        TenantJobRunner.JobSummary summary = runner.forEachTenant("test-job", t -> {
            if (!t.equals(trialA)) return 0;
            if (attemptsForA.getAndIncrement() == 0) {
                throw new ObjectOptimisticLockingFailureException(Customer.class, UUID.randomUUID());
            }
            return 7;
        });

        assertThat(attemptsForA.get()).isEqualTo(2); // tried, failed, retried
        // Every other tenant returns 0, so 7 is deterministic; >= 7 would not catch an
        // implementation that double-counted both attempts as 14.
        assertThat(summary.itemsProcessed()).isEqualTo(7);
    }

    /**
     * Proves the retry is bounded at one, not "at least one." A body that ALWAYS throws would
     * pass retriesATenantOnceAfterAnOptimisticLockFailure under a retry-twice or an unbounded
     * loop just as well as under a correct single retry -- this is the test that actually
     * pins the bound.
     */
    @Test
    void givesUpAfterASingleRetryRatherThanLoopingForever() {
        AtomicInteger attemptsForA = new AtomicInteger();
        List<UUID> visited = new ArrayList<>();
        TenantJobRunner.JobSummary summary = runner.forEachTenant("test-job", t -> {
            visited.add(t);
            if (t.equals(trialA)) {
                attemptsForA.incrementAndGet();
                throw new ObjectOptimisticLockingFailureException(Customer.class, UUID.randomUUID());
            }
            return 0;
        });

        assertThat(attemptsForA.get()).isEqualTo(2); // one attempt, one retry, then stop
        assertThat(summary.tenantsFailed()).isGreaterThanOrEqualTo(1);
        assertThat(visited).contains(activeB); // the sweep carried on regardless
    }

    @Test
    void sumsTheItemCountsTheBodyReports() {
        TenantJobRunner.JobSummary summary = runner.forEachTenant("test-job", t -> t.equals(trialA) ? 3 : 0);

        assertThat(summary.itemsProcessed()).isEqualTo(3);
        assertThat(summary.tenantsSwept()).isGreaterThanOrEqualTo(2);
    }

    private UUID newTenant(String name, TenantStatus status) {
        Tenant t = new Tenant(name + "-" + slugSeed, "Test " + name, "27", null, status, null);
        return tenants.saveAndFlush(t).getId();
    }

    private void seedOneCustomerFor(UUID tenantId) {
        // BLOCK lambda, not an expression lambda: runAs is overloaded on Runnable and
        // Supplier, and `() -> customers.saveAndFlush(...)` matches both ("reference to
        // runAs is ambiguous"). The braces make it unambiguously a Runnable.
        TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, null, "SYSTEM"), () -> {
            customers.saveAndFlush(new Customer(
                    "Customer of " + tenantId, null, "27", null, null, 0, null, null, CustomerSource.MANUAL));
        });
    }
}
