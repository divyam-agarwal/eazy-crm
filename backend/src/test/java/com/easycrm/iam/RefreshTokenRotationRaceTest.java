package com.easycrm.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.easycrm.platform.error.UnauthorizedException;
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
 * Two rotations of one refresh token, forced to overlap deterministically: a second connection
 * performs the "winning" rotation inside an open transaction, holding the row lock, while the
 * service's rotate() runs and blocks on it. Then the winner commits.
 *
 * <p>Before the fix, rotate() read the row unlocked, saw it live, and lost at its @Version check
 * on commit, surfacing ObjectOptimisticLockingFailureException — which ApiExceptionHandler maps to
 * 409. A refresh failure must be a 401. See spec 2026-09-14-f0 Part 2 #4 and §3.3.
 */
class RefreshTokenRotationRaceTest extends IntegrationTest {

    @Autowired
    RefreshTokenService refreshTokens;

    @Autowired
    TokenHasher hasher;

    @Test
    void theLoserOfAConcurrentRotationGets401AndLeavesNoToken() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String raw = refreshTokens.issue(userId, tenantId);
        ExecutorService pool = Executors.newSingleThreadExecutor();

        try (Connection winner = ownerConnection()) {
            winner.setAutoCommit(false);
            try (PreparedStatement ps = winner.prepareStatement(
                    "UPDATE refresh_token SET revoked_at = now(), replaced_by_id = ?, version = version + 1 "
                            + "WHERE token_hash = ?")) {
                // A successor id that does not exist: this test is about the race, not about grace
                // (RefreshTokenGraceTest owns that), so the loser must find nothing to recover.
                ps.setObject(1, UUID.randomUUID());
                ps.setString(2, hasher.sha256Hex(raw));
                assertEquals(1, ps.executeUpdate());
            }

            Future<Throwable> loser = pool.submit(() -> {
                try {
                    refreshTokens.rotate(raw);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });

            awaitABlockedStatementOn("refresh_token", Duration.ofSeconds(10));
            winner.commit();

            Throwable outcome = loser.get(10, TimeUnit.SECONDS);
            assertInstanceOf(UnauthorizedException.class, outcome, "loser outcome was " + outcome);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, liveTokensFor(userId), "the loser's successor insert must have rolled back");
    }

    private static void awaitABlockedStatementOn(String table, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        try (Connection c = ownerConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query ILIKE ?")) {
            ps.setString(1, "%" + table + "%");
            while (Instant.now().isBefore(deadline)) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getLong(1) > 0) return;
                }
                Thread.sleep(25);
            }
        }
        fail("rotate() never blocked on the held row lock; the race was not forced");
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
