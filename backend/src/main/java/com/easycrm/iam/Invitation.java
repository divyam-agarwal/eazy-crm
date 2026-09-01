package com.easycrm.iam;

import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * GLOBAL table (intentionally NOT tenant-scoped), like {@code refresh_token} and
 * {@code share_link}: accepting an invitation is pre-auth and must resolve a tenant from
 * the opaque token alone, so this cannot be tenant-filtered.
 *
 * <p>The token is stored HASHED, unlike {@code share_link}'s plaintext one. That token
 * only reads a frozen document and is deliberately idempotent; this one CREATES an
 * authenticated principal with a role, which is refresh-token-grade capability and gets
 * refresh-token-grade handling. It is also single-use — "the same link keeps working" is
 * the failure mode here, not the feature. See spec 2026-09-01 §3.
 *
 * <p>The plaintext token is a bearer credential and exists only in the mint response; it
 * is never stored, never logged, and this class has no toString().
 */
@Entity
@Table(name = "invitation")
public class Invitation extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private InvitationStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "invited_by", nullable = false)
    private UUID invitedBy;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "accepted_user_id")
    private UUID acceptedUserId;

    protected Invitation() {}

    public Invitation(UUID tenantId, String email, Role role, String tokenHash,
                      Instant expiresAt, UUID invitedBy) {
        this.tenantId = tenantId;
        this.email = email;
        this.role = role;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.invitedBy = invitedBy;
        this.status = InvitationStatus.PENDING;
    }

    public void accept(UUID userId, Instant when) {
        requirePending();
        this.status = InvitationStatus.ACCEPTED;
        this.acceptedUserId = userId;
        this.acceptedAt = when;
    }

    public void revoke() {
        requirePending();
        this.status = InvitationStatus.REVOKED;
    }

    /** Not expired AT the boundary instant — only strictly after it. */
    public boolean isExpired(Instant now) {
        return expiresAt.isBefore(now);
    }

    /**
     * The entity carries its own precondition rather than trusting the caller to have
     * checked — the same reason {@code Quotation.expire()} re-asserts SENT.
     */
    private void requirePending() {
        if (status != InvitationStatus.PENDING) {
            throw new ConflictException("invitation is no longer pending");
        }
    }

    public UUID getTenantId() { return tenantId; }
    public String getEmail() { return email; }
    public Role getRole() { return role; }
    public String getTokenHash() { return tokenHash; }
    public InvitationStatus getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public UUID getInvitedBy() { return invitedBy; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public UUID getAcceptedUserId() { return acceptedUserId; }
}
