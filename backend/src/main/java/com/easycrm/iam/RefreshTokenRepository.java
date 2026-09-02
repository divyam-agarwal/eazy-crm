package com.easycrm.iam;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Every live session belonging to one member. refresh_token is a GLOBAL, RLS-exempt
     * table, so the tenantId term is load-bearing rather than belt-and-braces — the same
     * reasoning as InvitationService.revoke (challenge #54) — even though a userId UUID is
     * already globally unique.
     */
    List<RefreshToken> findByUserIdAndTenantIdAndRevokedAtIsNull(UUID userId, UUID tenantId);
}
