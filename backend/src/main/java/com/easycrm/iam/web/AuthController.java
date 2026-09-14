package com.easycrm.iam.web;

import com.easycrm.iam.AuthService;
import com.easycrm.iam.IssuedSession;
import com.easycrm.iam.web.dto.AuthResponse;
import com.easycrm.iam.web.dto.LoginRequest;
import com.easycrm.iam.web.dto.MeResponse;
import com.easycrm.iam.web.dto.SignupRequest;
import com.easycrm.platform.error.UnauthorizedException;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;
    private final RefreshCookie cookie;

    public AuthController(AuthService auth, RefreshCookie cookie) {
        this.auth = auth;
        this.cookie = cookie;
    }

    // Public: SecurityConfig permits this without a token, so the contract must not
    // claim otherwise. An empty @SecurityRequirements clears the document-level
    // bearer-jwt requirement for this operation only.
    @SecurityRequirements
    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest req, HttpServletResponse response) {
        return ResponseEntity.status(HttpStatus.CREATED).body(issue(auth.signup(req), response));
    }

    @SecurityRequirements
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req, HttpServletResponse response) {
        return issue(auth.login(req), response);
    }

    @SecurityRequirements
    @PostMapping("/refresh")
    public AuthResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        String raw = cookie.read(request).orElseThrow(() -> new UnauthorizedException("invalid refresh token"));
        return issue(auth.refresh(raw), response);
    }

    @SecurityRequirements
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        cookie.read(request).ifPresent(auth::logout);
        cookie.clear(response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public MeResponse me() {
        return auth.me();
    }

    private AuthResponse issue(IssuedSession session, HttpServletResponse response) {
        cookie.write(response, session.refreshToken());
        return session.body();
    }
}
