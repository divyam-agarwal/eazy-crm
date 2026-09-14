package com.easycrm.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.easycrm.iam.web.dto.AuthResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A record's generated toString() prints every component; a raw refresh token must not reach a log line. */
class RawRefreshTokenRedactionTest {

    private static final String RAW = "raw-refresh-token-value-0123456789";

    @Test
    void issuedSessionDoesNotPrintTheRawToken() {
        var session = new IssuedSession(
                new AuthResponse("access", UUID.randomUUID(), UUID.randomUUID(), "slug", "a@b.test", "OWNER"), RAW);
        String s = session.toString();
        assertFalse(s.contains(RAW), s);
        assertTrue(s.contains("refreshToken=<redacted>"), s);
    }

    @Test
    void rotationResultDoesNotPrintTheRawToken() {
        var rot = new RefreshTokenService.RotationResult(RAW, UUID.randomUUID(), UUID.randomUUID());
        String s = rot.toString();
        assertFalse(s.contains(RAW), s);
        assertTrue(s.contains("newRawToken=<redacted>"), s);
    }
}
