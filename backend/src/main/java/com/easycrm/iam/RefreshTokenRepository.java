package com.easycrm.iam;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Every live session belonging to one member. refresh_token is a GLOBAL, RLS-exempt
     * table, so the tenantId term is load-bearing rather than belt-and-braces — the same
     * reasoning as InvitationService.revoke (challenge #54) — even though a userId UUID is
     * already globally unique.
     */
    List<RefreshToken> findByUserIdAndTenantIdAndRevokedAtIsNull(UUID userId, UUID tenantId);

    /**
     * The race-safe half of rotation: revokes the presented token ONLY if it is still live, in one
     * statement. Postgres row-locks the target, so of two concurrent rotations the second blocks,
     * re-evaluates {@code revoked_at IS NULL} against the committed row, and matches zero rows.
     *
     * <p>Native, so it bypasses @Version and auditing: both are maintained by hand here.
     * {@code flushAutomatically} writes the pending successor INSERT first, so the id this row points
     * at exists in the same transaction.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE refresh_token
               SET revoked_at = :now, replaced_by_id = :successorId, version = version + 1, updated_at = :now
             WHERE token_hash = :hash AND revoked_at IS NULL AND expires_at > :now
            """, nativeQuery = true)
    int revokeIfLive(@Param("hash") String hash, @Param("successorId") UUID successorId, @Param("now") Instant now);
}
