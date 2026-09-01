package com.easycrm.iam;

import com.easycrm.platform.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * GLOBAL table (intentionally NOT tenant-scoped): the refresh endpoint is pre-auth and
 * must resolve identity from the opaque token alone, so this cannot be tenant-filtered.
 * Safe because the token is looked up by a 256-bit hash and is stored hashed at rest.
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken extends BaseEntity {

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by_id")
    private UUID replacedById;

    protected RefreshToken() {}

    public RefreshToken(String tokenHash, UUID userId, UUID tenantId, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.userId = userId;
        this.tenantId = tenantId;
        this.expiresAt = expiresAt;
    }

    public void revoke(Instant when, UUID replacedById) {
        this.revokedAt = when;
        this.replacedById = replacedById;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getReplacedById() {
        return replacedById;
    }
}
