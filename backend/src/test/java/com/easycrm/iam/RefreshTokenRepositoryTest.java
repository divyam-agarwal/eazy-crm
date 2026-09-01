package com.easycrm.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.easycrm.support.IntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RefreshTokenRepositoryTest extends IntegrationTest {
    @Autowired
    RefreshTokenRepository tokens;

    @Test
    void savesAndFindsByHashWithoutTenantContext() {
        // No TenantContext set: refresh_token is a GLOBAL table, looked up by hash.
        RefreshToken rt = new RefreshToken(
                "abc123hash", UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-12-31T00:00:00Z"));
        tokens.save(rt);

        RefreshToken found = tokens.findByTokenHash("abc123hash").orElseThrow();
        assertNull(found.getRevokedAt());
    }

    @Test
    void revokeMarksRevokedAndReplacement() {
        RefreshToken rt =
                new RefreshToken("hash2", UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-12-31T00:00:00Z"));
        tokens.save(rt);
        UUID replacement = UUID.randomUUID();
        rt.revoke(Instant.parse("2026-07-25T00:00:00Z"), replacement);
        tokens.save(rt);

        RefreshToken found = tokens.findByTokenHash("hash2").orElseThrow();
        assertNotNull(found.getRevokedAt());
        assertEquals(replacement, found.getReplacedById());
    }
}
