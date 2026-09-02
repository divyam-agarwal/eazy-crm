package com.easycrm.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.easycrm.iam.web.dto.AuthResponse;
import com.easycrm.iam.web.dto.SignupRequest;
import com.easycrm.iam.web.dto.TokenResponse;
import com.easycrm.platform.error.UnauthorizedException;
import com.easycrm.platform.security.JwtService;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class AuthServiceRefreshTest extends IntegrationTest {
    @Autowired
    AuthService auth;

    @Autowired
    JwtService jwt;

    @Autowired
    UserRepository users;

    @Autowired
    TransactionTemplate tx;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private AuthResponse signup(String slug) {
        AuthResponse r =
                auth.signup(new SignupRequest(slug, "Biz", "27", null, "u@" + slug + ".test", null, "correct-horse"));
        TenantContext.clear();
        return r;
    }

    @Test
    void refreshRotatesAndMintsNewAccessTokenWithRole() {
        AuthResponse signed = signup("refresh-a");
        TokenResponse res = auth.refresh(signed.refreshToken());

        assertNotEquals(signed.refreshToken(), res.refreshToken(), "refresh token rotated");
        TenantContext.TenantPrincipal p = jwt.parse(res.accessToken());
        assertEquals(signed.tenantId(), p.tenantId());
        assertEquals("OWNER", p.role());
    }

    @Test
    void logoutRevokesRefreshToken() {
        AuthResponse signed = signup("refresh-b");
        auth.logout(signed.refreshToken());
        assertThrows(UnauthorizedException.class, () -> auth.refresh(signed.refreshToken()));
    }

    @Test
    void aDisabledMemberCannotRefresh() {
        AuthResponse signed = signup("refresh-disabled");

        TenantContext.set(new TenantContext.TenantPrincipal(signed.tenantId(), null, "SYSTEM"));
        tx.executeWithoutResult(
                s -> users.findById(signed.userId()).orElseThrow().disable());
        TenantContext.clear();

        // Same generic message as every other refresh failure: no new enumeration signal.
        UnauthorizedException ex = assertThrows(UnauthorizedException.class, () -> auth.refresh(signed.refreshToken()));
        assertEquals("invalid refresh token", ex.getMessage());
    }
}
