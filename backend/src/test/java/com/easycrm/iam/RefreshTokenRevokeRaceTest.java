package com.easycrm.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.easycrm.support.IntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A logout (or the stale-cookie revoke that login, signup and invitation accept perform AFTER
 * issuing a new session) racing a rotation of the same token. A second connection performs the
 * winning rotation inside an open transaction, holding the row lock, while revoke() runs and
 * blocks on it; then the winner commits.
 *
 * <p>Before the fix, revoke() loaded the row unlocked, saw it live, and saved it through the
 * entity: the @Version check failed on commit and surfaced as ObjectOptimisticLockingFailureException
 * — a 409 on a login or accept that had already succeeded (spec 2026-09-14-f0 §3.3).
 */
class RefreshTokenRevokeRaceTest extends IntegrationTest {

    @Autowired
    RefreshTokenService refreshTokens;

    @Autowired
    TokenHasher hasher;

    @Test
    void aRevokeThatLosesToAConcurrentRotationSucceedsAndEndsTheChain() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String raw = refreshTokens.issue(userId, tenantId);
        UUID successorId = UUID.randomUUID();
        ExecutorService pool = Executors.newSingleThreadExecutor();

        try (Connection winner = ownerConnection()) {
            winner.setAutoCommit(false);
            try (PreparedStatement ins = winner.prepareStatement(
                    "INSERT INTO refresh_token (id, token_hash, user_id, tenant_id, expires_at, version) "
                            + "VALUES (?, ?, ?, ?, now() + interval '30 days', 0)")) {
                ins.setObject(1, successorId);
                ins.setString(2, hasher.sha256Hex("winner-" + successorId));
                ins.setObject(3, userId);
                ins.setObject(4, tenantId);
                assertEquals(1, ins.executeUpdate());
            }
            try (PreparedStatement upd = winner.prepareStatement(
                    "UPDATE refresh_token SET revoked_at = now(), replaced_by_id = ?, version = version + 1 "
                            + "WHERE token_hash = ?")) {
                upd.setObject(1, successorId);
                upd.setString(2, hasher.sha256Hex(raw));
                assertEquals(1, upd.executeUpdate());
            }

            Future<Throwable> revoke = pool.submit(() -> {
                try {
                    refreshTokens.revoke(raw);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });

            awaitABlockedStatement(Duration.ofSeconds(10));
            winner.commit();

            assertNull(revoke.get(10, TimeUnit.SECONDS), "revoke must never fail on a concurrent rotation");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, liveTokensFor(userId), "the winner's successor must not survive the logout");
    }

    private static void awaitABlockedStatement(Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        try (Connection c = ownerConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query ILIKE '%refresh_token%'")) {
            while (Instant.now().isBefore(deadline)) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getLong(1) > 0) return;
                }
                Thread.sleep(25);
            }
        }
        fail("revoke() never blocked on the held row lock; the race was not forced");
    }

    private static long liveTokensFor(UUID userId) throws Exception {
        try (Connection c = ownerConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
