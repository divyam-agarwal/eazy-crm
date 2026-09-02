package com.easycrm.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The last-active-owner rule is check-then-act, and the anomaly it invites is WRITE SKEW:
 * two transactions read an overlapping set (the active owners) and then write DISJOINT rows
 * within it. @Version cannot see it (different rows), no constraint expresses "at least
 * one", and Postgres REPEATABLE READ does not detect it — only SERIALIZABLE does. The tenant
 * row lock materialises the conflict instead.
 */
class MemberOwnerRaceTest extends IntegrationTest {

    @Autowired
    MemberService members;

    @Autowired
    UserRepository users;

    @Autowired
    TestTokens tokens;

    @Autowired
    TransactionTemplate tx;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private UUID addOwner(UUID tenantId, String email) {
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, null, "SYSTEM"));
        try {
            return tx.execute(s -> users.save(new User(email, null, "hash", Role.OWNER, UserStatus.ACTIVE))
                    .getId());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void twoOwnersDemotingEachOtherCannotStrandTheWorkspace() throws Exception {
        var provisioned = tokens.provisionOwner("27");
        UUID tenantId = provisioned.tenantId();
        UUID asha = addOwner(tenantId, "asha@race.test");
        UUID bilal = addOwner(tenantId, "bilal@race.test");

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        // Each thread acts AS one owner and demotes the OTHER, releasing together so both
        // count the active owners before either commits.
        Callable<Throwable> ashaDemotesBilal = demote(tenantId, asha, bilal, barrier);
        Callable<Throwable> bilalDemotesAsha = demote(tenantId, bilal, asha, barrier);

        List<Future<Throwable>> results = pool.invokeAll(List.of(ashaDemotesBilal, bilalDemotesAsha));
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "both attempts finished");

        List<Throwable> losses =
                results.stream().map(this::outcome).filter(Objects::nonNull).toList();
        assertEquals(1, results.size() - losses.size(), "exactly one demotion may win");
        assertInstanceOf(
                ConflictException.class,
                losses.get(0),
                "the loser must be refused by the last-owner invariant, not by an unrelated failure");

        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, null, "SYSTEM"));
        long remaining = tx.execute(s -> users.countByRoleAndStatus(Role.OWNER, UserStatus.ACTIVE));
        assertEquals(1, remaining, "the workspace must never be left without an active owner");
    }

    private Callable<Throwable> demote(UUID tenantId, UUID actor, UUID target, CyclicBarrier barrier) {
        return () -> {
            // TenantContext is a ThreadLocal, so each thread binds its own principal.
            TenantContext.set(new TenantContext.TenantPrincipal(tenantId, actor, "OWNER"));
            try {
                barrier.await(10, TimeUnit.SECONDS);
                members.changeRole(target, "SALES_EXEC");
                return null;
            } catch (Throwable t) {
                return t;
            } finally {
                TenantContext.clear();
            }
        };
    }

    private Throwable outcome(Future<Throwable> f) {
        try {
            return f.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            return e;
        }
    }
}
