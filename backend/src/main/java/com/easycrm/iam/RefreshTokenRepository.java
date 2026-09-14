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

    /**
     * Logout's half of {@link #revokeIfLive}: revokes a live token without pointing it at a successor
     * and without an expiry term (an expired-but-unrevoked token is still worth burning). Like every
     * other write on this table it bypasses the entity, so a concurrent rotation makes it match zero
     * rows rather than fail a @Version check with a 409.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE refresh_token SET revoked_at = :now, version = version + 1, updated_at = :now
             WHERE token_hash = :hash AND revoked_at IS NULL
            """, nativeQuery = true)
    int revokeByHashIfLive(@Param("hash") String hash, @Param("now") Instant now);

    /** The successor a revoked token points at, read AFTER a conditional UPDATE has settled any race. */
    @Query(
            value = "SELECT replaced_by_id FROM refresh_token WHERE token_hash = :hash AND replaced_by_id IS NOT NULL",
            nativeQuery = true)
    Optional<UUID> findReplacedById(@Param("hash") String hash);

    /**
     * The orphaned successor of a token eligible for grace, locking the presented row so two grace
     * attempts serialize. Empty when the token is not recently rotated, already used its grace, is
     * expired, or was revoked by logout (replaced_by_id null).
     */
    @Query(value = """
            SELECT replaced_by_id FROM refresh_token
             WHERE token_hash = :hash
               AND revoked_at > :cutoff
               AND grace_used_at IS NULL
               AND expires_at > :now
               AND replaced_by_id IS NOT NULL
             FOR UPDATE
            """, nativeQuery = true)
    Optional<UUID> findGraceSuccessor(
            @Param("hash") String hash, @Param("cutoff") Instant cutoff, @Param("now") Instant now);

    /** Revokes one token only if nobody has used it yet. Zero rows means the successor was used. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE refresh_token SET revoked_at = :now, version = version + 1, updated_at = :now
             WHERE id = :id AND revoked_at IS NULL
            """, nativeQuery = true)
    int revokeByIdIfLive(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE refresh_token
               SET grace_used_at = :now, replaced_by_id = :successorId, version = version + 1, updated_at = :now
             WHERE token_hash = :hash AND grace_used_at IS NULL
            """, nativeQuery = true)
    int markGraceUsed(@Param("hash") String hash, @Param("successorId") UUID successorId, @Param("now") Instant now);
}
