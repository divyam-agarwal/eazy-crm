package com.easycrm.platform.job;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.crm.CustomerSource;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.tenant.Tenant;
import com.easycrm.tenant.TenantRepository;
import com.easycrm.tenant.TenantStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seam every future scheduled job inherits. The isolation test below is the one that
 * matters: it is the only thing proving runAs ran BEFORE the transaction opened, which is
 * the failure mode that returns zero rows instead of raising. See spec 2026-08-31 §3.1.
 */
@SpringBootTest
class TenantJobRunnerTest extends IntegrationTest {

    @Autowired TenantJobRunner runner;
    @Autowired TenantRepository tenants;
    @Autowired CustomerRepository customers;

    private UUID trialA, activeB, suspendedC;
    private String slugSeed;

    @BeforeEach
    void seed() {
        slugSeed = UUID.randomUUID().toString().substring(0, 8);
        trialA     = newTenant("trial-a",     TenantStatus.TRIAL);
        activeB    = newTenant("active-b",    TenantStatus.ACTIVE);
        suspendedC = newTenant("suspended-c", TenantStatus.SUSPENDED);
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void sweepsTrialAndActiveTenantsAndSkipsSuspendedOnes() {
        List<UUID> visited = new ArrayList<>();
        runner.forEachTenant("test-job", t -> { visited.add(t); return 0; });

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

    @Test
    void oneFailingTenantDoesNotAbortTheSweep() {
        List<UUID> visited = new ArrayList<>();
        TenantJobRunner.JobSummary summary = runner.forEachTenant("test-job", t -> {
            visited.add(t);
            if (t.equals(trialA)) throw new IllegalStateException("boom");
            return 1;
        });

        assertThat(visited).contains(trialA, activeB);   // reached the later tenant anyway
        assertThat(summary.tenantsFailed()).isGreaterThanOrEqualTo(1);
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

        assertThat(attemptsForA.get()).isEqualTo(2);              // tried, failed, retried
        assertThat(summary.itemsProcessed()).isGreaterThanOrEqualTo(7);
    }

    @Test
    void sumsTheItemCountsTheBodyReports() {
        TenantJobRunner.JobSummary summary =
            runner.forEachTenant("test-job", t -> t.equals(trialA) ? 3 : 0);

        assertThat(summary.itemsProcessed()).isGreaterThanOrEqualTo(3);
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
        TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, null, "SYSTEM"),
            () -> {
                customers.saveAndFlush(new Customer(
                    "Customer of " + tenantId, null, "27", null, null, 0, null, null,
                    CustomerSource.MANUAL));
            });
    }
}
