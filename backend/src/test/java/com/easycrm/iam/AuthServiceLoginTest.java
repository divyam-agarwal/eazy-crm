package com.easycrm.iam;

import com.easycrm.iam.web.dto.AuthResponse;
import com.easycrm.iam.web.dto.LoginRequest;
import com.easycrm.iam.web.dto.SignupRequest;
import com.easycrm.platform.error.UnauthorizedException;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceLoginTest extends IntegrationTest {
    @Autowired AuthService auth;

    @AfterEach void clear() { TenantContext.clear(); }

    private void signup(String slug, String email, String pass) {
        auth.signup(new SignupRequest(slug, "Biz", "27", null, email, null, pass));
        TenantContext.clear();
    }

    @Test
    void loginWithCorrectPasswordReturnsTokens() {
        signup("login-a", "u@login-a.test", "correct-horse");
        AuthResponse res = auth.login(new LoginRequest("login-a", "u@login-a.test", "correct-horse"));
        assertNotNull(res.accessToken());
        assertEquals("OWNER", res.role());
    }

    @Test
    void wrongPasswordIsUnauthorized() {
        signup("login-b", "u@login-b.test", "correct-horse");
        assertThrows(UnauthorizedException.class,
            () -> auth.login(new LoginRequest("login-b", "u@login-b.test", "WRONG")));
    }

    @Test
    void unknownSlugIsUnauthorized() {
        assertThrows(UnauthorizedException.class,
            () -> auth.login(new LoginRequest("ghost", "x@ghost.test", "whatever12")));
    }
}
