package com.easycrm.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.easycrm.platform.error.UnauthorizedException;
import com.easycrm.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RefreshTokenServiceTest extends IntegrationTest {
    @Autowired
    RefreshTokenService service;

    @Autowired
    RefreshTokenRepository repo;

    @Test
    void issueThenRotateReturnsNewTokenAndRevokesOld() {
        UUID user = UUID.randomUUID(), tenant = UUID.randomUUID();
        String raw = service.issue(user, tenant);
        assertTrue(repo.findByTokenHash(new TokenHasher().sha256Hex(raw)).isPresent());

        RefreshTokenService.RotationResult r = service.rotate(raw);
        assertNotEquals(raw, r.newRawToken());
        assertEquals(user, r.userId());
        assertEquals(tenant, r.tenantId());

        // Old token is now revoked -> rotating it again fails.
        assertThrows(UnauthorizedException.class, () -> service.rotate(raw));
    }

    @Test
    void rotateUnknownTokenIsUnauthorized() {
        assertThrows(UnauthorizedException.class, () -> service.rotate("not-a-real-token"));
    }
}
