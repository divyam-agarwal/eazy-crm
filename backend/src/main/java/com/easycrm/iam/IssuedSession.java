package com.easycrm.iam;

import com.easycrm.iam.web.dto.AuthResponse;
import java.util.UUID;

/**
 * What a session-issuing service call produces: the response body, plus the raw refresh token that
 * only the web layer may see, and only to put in a cookie. Keeping the token out of AuthResponse
 * makes "a refresh token in a JSON body" unrepresentable rather than merely unlikely.
 */
public record IssuedSession(AuthResponse body, String refreshToken) {

    public String accessToken() {
        return body.accessToken();
    }

    public UUID userId() {
        return body.userId();
    }

    public UUID tenantId() {
        return body.tenantId();
    }

    public String role() {
        return body.role();
    }

    /** The generated toString() would print the raw refresh token into any log line that touches this. */
    @Override
    public String toString() {
        return "IssuedSession[body=" + body + ", refreshToken=<redacted>]";
    }
}
