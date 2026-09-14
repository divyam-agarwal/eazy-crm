package com.easycrm.iam.web;

import com.easycrm.iam.RefreshTokenService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The only code that builds, reads or clears the refresh cookie (spec 2026-09-14-f0 §3.1, guarded by
 * AuthSessionBoundaryArchTest).
 *
 * <p>Path-scoped to /api/v1/auth rather than __Host-prefixed: __Host- forces Path=/, which would send
 * the refresh token with every API call. Secure is unconditional; Chromium accepts Secure cookies on
 * http://localhost, which is the supported dev and E2E browser.
 */
@Component
public class RefreshCookie {

    public static final String NAME = "easycrm_rt";
    static final String PATH = "/api/v1/auth";
    static final Duration MAX_AGE = Duration.ofDays(RefreshTokenService.TTL_DAYS);

    public void write(HttpServletResponse response, String rawToken) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(rawToken, MAX_AGE).toString());
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, build("", Duration.ZERO).toString());
    }

    public Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();
        return Arrays.stream(cookies)
                .filter(c -> NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }

    private static ResponseCookie build(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(PATH)
                .maxAge(maxAge)
                .build();
    }
}
