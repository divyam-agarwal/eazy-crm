package com.easycrm.iam;

import com.easycrm.platform.error.UnauthorizedException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenService {

    public static final long TTL_DAYS = 30;
    public static final Duration GRACE = Duration.ofSeconds(30);
    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder base64Url = Base64.getUrlEncoder().withoutPadding();

    private final RefreshTokenRepository tokens;
    private final TokenHasher hasher;
    private final Clock clock;

    public RefreshTokenService(RefreshTokenRepository tokens, TokenHasher hasher, Clock clock) {
        this.tokens = tokens;
        this.hasher = hasher;
        this.clock = clock;
    }

    public record RotationResult(String newRawToken, UUID userId, UUID tenantId) {
        /** The generated toString() would print the raw token into any log line that touches this. */
        @Override
        public String toString() {
            return "RotationResult[newRawToken=<redacted>, userId=" + userId + ", tenantId=" + tenantId + "]";
        }
    }

    @Transactional
    public String issue(UUID userId, UUID tenantId) {
        String raw = randomToken();
        tokens.save(new RefreshToken(
                hasher.sha256Hex(raw), userId, tenantId, clock.instant().plus(TTL_DAYS, ChronoUnit.DAYS)));
        return raw;
    }

    @Transactional
    public RotationResult rotate(String rawToken) {
        return rotate(rawToken, clock.instant());
    }

    /**
     * {@code now} is explicit so tests can move time (ClockConfig: no test overrides the Clock bean).
     *
     * <p>Order matters: the successor is inserted first so the conditional UPDATE can point at it,
     * and if the UPDATE matches nothing the throw rolls the insert back with it. The presented row is
     * read only for its owner; it is never modified through the entity, so no @Version write can lose
     * a race here and surface as a 409.
     */
    @Transactional
    public RotationResult rotate(String rawToken, Instant now) {
        String hash = hasher.sha256Hex(rawToken);
        RefreshToken presented = tokens.findByTokenHash(hash).orElseThrow(RefreshTokenService::invalid);
        UUID userId = presented.getUserId();
        UUID tenantId = presented.getTenantId();

        String newRaw = randomToken();
        RefreshToken successor = tokens.save(
                new RefreshToken(hasher.sha256Hex(newRaw), userId, tenantId, now.plus(TTL_DAYS, ChronoUnit.DAYS)));

        if (tokens.revokeIfLive(hash, successor.getId(), now) == 1) {
            return new RotationResult(newRaw, userId, tenantId);
        }

        // Lost-ACK grace (spec §3.3): the presented token was revoked by a rotation whose response
        // may never have arrived. Recover exactly once, within GRACE, and only if the successor that
        // rotation minted is still unused — a used successor means this is a replay, not a lost reply.
        UUID orphan = tokens.findGraceSuccessor(hash, now.minus(GRACE), now).orElseThrow(RefreshTokenService::invalid);
        if (tokens.revokeByIdIfLive(orphan, now) != 1) {
            throw invalid();
        }
        if (tokens.markGraceUsed(hash, successor.getId(), now) != 1) {
            throw invalid();
        }
        return new RotationResult(newRaw, userId, tenantId);
    }

    /**
     * Idempotent and never a 409: a token presented for logout may be live, unknown, already revoked
     * with no successor (a plain replay), or already rotated — whose successor may never have reached
     * the browser, possibly twice over (a lost rotation, then a lost grace recovery).
     *
     * <p>Only native conditional UPDATEs, never an entity save. Login, signup and invitation accept
     * call this AFTER issuing a new session, so an @Version conflict with a concurrent rotation of the
     * same token would turn their success into a 409. If the "revoke if live" UPDATE matches nothing
     * — including because a rotation committed while it waited on the row lock — the token was
     * rotated or revoked, and the rotated case ends the chain: its successor is revoked whether or not
     * grace was already spent, and the grace is burned so {@link #rotate(String, Instant)} cannot
     * recover an orphan after the session has ended (spec §3.3).
     */
    @Transactional
    public void revoke(String rawToken) {
        String hash = hasher.sha256Hex(rawToken);
        Instant now = clock.instant();
        if (tokens.revokeByHashIfLive(hash, now) == 1) {
            return;
        }
        tokens.findReplacedById(hash).ifPresent(successor -> {
            tokens.revokeByIdIfLive(successor, now);
            tokens.markGraceUsed(hash, successor, now); // no-op when grace was already used
        });
    }

    /**
     * Ends every live session a member has. Returns how many were revoked, which
     * MemberService records in the audit row — "disabled, 3 sessions killed" is a materially
     * different event from "disabled, was not logged in".
     */
    @Transactional
    public int revokeAllForUser(UUID userId, UUID tenantId) {
        List<RefreshToken> live = tokens.findByUserIdAndTenantIdAndRevokedAtIsNull(userId, tenantId);
        Instant now = clock.instant();
        live.forEach(t -> t.revoke(now, null));
        tokens.saveAll(live);
        return live.size();
    }

    private static UnauthorizedException invalid() {
        return new UnauthorizedException("invalid refresh token");
    }

    private String randomToken() {
        byte[] bytes = new byte[32]; // 256 bits of entropy
        random.nextBytes(bytes);
        return base64Url.encodeToString(bytes);
    }
}
