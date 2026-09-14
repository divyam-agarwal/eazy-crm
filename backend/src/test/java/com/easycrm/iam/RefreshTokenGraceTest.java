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
 * The lost-ACK case: the server commits a rotation, the response (and its Set-Cookie) never reaches
 * the browser, and the browser presents the now-revoked token again. Every tab shares that cookie, so
 * without grace the user is signed out everywhere. Spec 2026-09-14-f0 F0-5 and §3.3.
 */
class RefreshTokenGraceTest extends IntegrationTest {

    @Autowired
    RefreshTokenService refreshTokens;

    @Autowired
    TokenHasher hasher;

    private final Instant t0 = Instant.now();

    private record Owner(UUID userId, UUID tenantId, String raw) {}

    private Owner issue() {
        UUID u = UUID.randomUUID();
        UUID t = UUID.randomUUID();
        return new Owner(u, t, refreshTokens.issue(u, t));
    }

    @Test
    void aTokenWhoseResponseWasLostCanBePresentedOnceMore() throws Exception {
        Owner o = issue();
        var lost = refreshTokens.rotate(o.raw(), t0); // response never arrives

        var recovered = refreshTokens.rotate(o.raw(), t0.plusSeconds(10));

        assertNotEquals(lost.newRawToken(), recovered.newRawToken());
        assertThrows(
                UnauthorizedException.class,
                () -> refreshTokens.rotate(lost.newRawToken(), t0.plusSeconds(11)),
                "the orphaned successor must be revoked");
        assertThrows(
                UnauthorizedException.class,
                () -> refreshTokens.rotate(o.raw(), t0.plusSeconds(12)),
                "grace is single-use");
        assertEquals(1, liveTokensFor(o.userId()));
    }

    @Test
    void noGraceAfterThirtySeconds() {
        Owner o = issue();
        refreshTokens.rotate(o.raw(), t0);
        assertThrows(UnauthorizedException.class, () -> refreshTokens.rotate(o.raw(), t0.plusSeconds(31)));
    }

    @Test
    void noGraceOnceTheSuccessorHasBeenUsed() {
        // The client DID receive the successor and used it: the old token is a replay, not a lost ACK.
        Owner o = issue();
        var first = refreshTokens.rotate(o.raw(), t0);
        refreshTokens.rotate(first.newRawToken(), t0.plusSeconds(1));
        assertThrows(UnauthorizedException.class, () -> refreshTokens.rotate(o.raw(), t0.plusSeconds(2)));
    }

    @Test
    void noGraceForALoggedOutToken() {
        Owner o = issue();
        refreshTokens.revoke(o.raw());
        assertThrows(UnauthorizedException.class, () -> refreshTokens.rotate(o.raw(), t0.plusSeconds(1)));
    }

    @Test
    void aRealConcurrentRotationLeavesExactlyOneLiveToken() throws Exception {
        Owner o = issue();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection winner = ownerConnection()) {
            winner.setAutoCommit(false);
            UUID successorId = UUID.randomUUID();
            try (PreparedStatement ins = winner.prepareStatement(
                    "INSERT INTO refresh_token (id, token_hash, user_id, tenant_id, expires_at, version) "
                            + "VALUES (?, ?, ?, ?, now() + interval '30 days', 0)")) {
                ins.setObject(1, successorId);
                ins.setString(2, hasher.sha256Hex("winner-" + successorId));
                ins.setObject(3, o.userId());
                ins.setObject(4, o.tenantId());
                ins.executeUpdate();
            }
            try (PreparedStatement upd = winner.prepareStatement(
                    "UPDATE refresh_token SET revoked_at = now(), replaced_by_id = ?, version = version + 1 WHERE token_hash = ?")) {
                upd.setObject(1, successorId);
                upd.setString(2, hasher.sha256Hex(o.raw()));
                upd.executeUpdate();
            }

            Future<Throwable> loser = pool.submit(() -> {
                try {
                    refreshTokens.rotate(o.raw());
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            awaitABlockedStatement(Duration.ofSeconds(10));
            winner.commit();

            assertNull(loser.get(10, TimeUnit.SECONDS), "the loser recovers through grace");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, liveTokensFor(o.userId()), "never two live tokens for one session");
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
        fail("rotate() never blocked; the race was not forced");
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
