package com.easycrm.iam;

import com.easycrm.platform.error.UnauthorizedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private static final long TTL_DAYS = 30;
    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder base64Url = Base64.getUrlEncoder().withoutPadding();

    private final RefreshTokenRepository tokens;
    private final TokenHasher hasher;

    public RefreshTokenService(RefreshTokenRepository tokens, TokenHasher hasher) {
        this.tokens = tokens;
        this.hasher = hasher;
    }

    public record RotationResult(String newRawToken, UUID userId, UUID tenantId) {}

    @Transactional
    public String issue(UUID userId, UUID tenantId) {
        String raw = randomToken();
        tokens.save(new RefreshToken(hasher.sha256Hex(raw), userId, tenantId,
            Instant.now().plus(TTL_DAYS, ChronoUnit.DAYS)));
        return raw;
    }

    @Transactional
    public RotationResult rotate(String rawToken) {
        RefreshToken current = tokens.findByTokenHash(hasher.sha256Hex(rawToken))
            .orElseThrow(() -> new UnauthorizedException("invalid refresh token"));
        if (current.getRevokedAt() != null || current.getExpiresAt().isBefore(Instant.now())) {
            throw new UnauthorizedException("invalid refresh token");
        }
        String newRaw = randomToken();
        RefreshToken replacement = tokens.save(new RefreshToken(
            hasher.sha256Hex(newRaw), current.getUserId(), current.getTenantId(),
            Instant.now().plus(TTL_DAYS, ChronoUnit.DAYS)));
        current.revoke(Instant.now(), replacement.getId());
        tokens.save(current);
        return new RotationResult(newRaw, current.getUserId(), current.getTenantId());
    }

    @Transactional
    public void revoke(String rawToken) {
        tokens.findByTokenHash(hasher.sha256Hex(rawToken)).ifPresent(t -> {
            if (t.getRevokedAt() == null) {
                t.revoke(Instant.now(), null);
                tokens.save(t);
            }
        });
    }

    private String randomToken() {
        byte[] bytes = new byte[32]; // 256 bits of entropy
        random.nextBytes(bytes);
        return base64Url.encodeToString(bytes);
    }
}
