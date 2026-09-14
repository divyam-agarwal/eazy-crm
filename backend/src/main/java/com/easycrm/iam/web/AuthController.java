package com.easycrm.iam.web;

import com.easycrm.iam.AuthService;
import com.easycrm.iam.IssuedSession;
import com.easycrm.iam.web.dto.AuthResponse;
import com.easycrm.iam.web.dto.LoginRequest;
import com.easycrm.iam.web.dto.MeResponse;
import com.easycrm.iam.web.dto.SignupRequest;
import com.easycrm.iam.web.dto.SignupStatusResponse;
import com.easycrm.platform.error.ApiErrorResponse;
import com.easycrm.platform.error.ForbiddenException;
import com.easycrm.platform.error.UnauthorizedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
    @ApiResponse(
            responseCode = "201",
            description = "workspace created; the refresh token is set as the httpOnly easycrm_rt cookie")
    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(
            @Valid @RequestBody SignupRequest req, HttpServletRequest request, HttpServletResponse response) {
        IssuedSession session = auth.signup(req);
        // Same reason as login: creating a workspace in a browser that is already signed in must not
        // leave that browser's previous refresh token live for 30 days.
        cookie.read(request).ifPresent(auth::logout);
        return ResponseEntity.status(HttpStatus.CREATED).body(issue(session, response));
    }

    @SecurityRequirements
    @PostMapping("/login")
    public AuthResponse login(
            @Valid @RequestBody LoginRequest req, HttpServletRequest request, HttpServletResponse response) {
        IssuedSession session = auth.login(req);
        // Switching users on a device must not leave the previous refresh token live for 30 days.
        // Revoked only after a SUCCESSFUL login, and never used to authenticate (spec §3.1).
        cookie.read(request).ifPresent(auth::logout);
        return issue(session, response);
    }

    @SecurityRequirements
    @Operation(description = COOKIE_AUTH + " Rotates the cookie and returns a fresh access token.")
    @Parameters(
            @Parameter(
                    in = ParameterIn.HEADER,
                    name = CLIENT_HEADER,
                    required = true,
                    description = "CSRF defence: must be exactly `web`; missing or different answers 403",
                    schema = @Schema(type = "string", allowableValues = "web")))
    @ApiResponse(
            responseCode = "401",
            description = MISSING_COOKIE,
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = MISSING_HEADER,
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @PostMapping("/refresh")
    public AuthResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        requireWebClient(request);
        String raw = cookie.read(request).orElseThrow(() -> new UnauthorizedException("invalid refresh token"));
        return issue(auth.refresh(raw), response);
    }

    @SecurityRequirements
    @Operation(
            description = COOKIE_AUTH
                    + " Revokes the presented refresh token (if any) and clears the cookie. Idempotent:"
                    + " answers 204 with or without a cookie, so it never returns 401.")
    @Parameters(
            @Parameter(
                    in = ParameterIn.HEADER,
                    name = CLIENT_HEADER,
                    required = true,
                    description = "CSRF defence: must be exactly `web`; missing or different answers 403",
                    schema = @Schema(type = "string", allowableValues = "web")))
    @ApiResponse(responseCode = "204", description = "signed out; the easycrm_rt cookie is cleared")
    @ApiResponse(
            responseCode = "401",
            description = "not returned: logout answers 204 whether or not a valid refresh cookie is sent",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = MISSING_HEADER,
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        requireWebClient(request);
        cookie.read(request).ifPresent(auth::logout);
        cookie.clear(response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public MeResponse me() {
        return auth.me();
    }

    @SecurityRequirements
    @GetMapping("/signup/status")
    public SignupStatusResponse signupStatus() {
        return new SignupStatusResponse(auth.signupOpen());
    }

    private AuthResponse issue(IssuedSession session, HttpServletResponse response) {
        cookie.write(response, session.refreshToken());
        return session.body();
    }

    private static final String COOKIE_AUTH = "Authenticates with the httpOnly easycrm_rt refresh cookie"
            + " (Path=/api/v1/auth), not a bearer token, and requires the header X-EasyCRM-Client: web.";
    private static final String MISSING_COOKIE =
            "the easycrm_rt refresh cookie is missing, invalid, expired or revoked";
    private static final String MISSING_HEADER = "the X-EasyCRM-Client: web header is missing";

    /**
     * CSRF defence-in-depth on the two routes that authenticate with the cookie (spec §3.2). A
     * cross-site form cannot set a custom header, and a cross-origin fetch that sets one fails its
     * preflight. Explicit code rather than @RequestHeader, which would answer 400 instead of 403.
     */
    static final String CLIENT_HEADER = "X-EasyCRM-Client";

    private static void requireWebClient(HttpServletRequest request) {
        if (!"web".equals(request.getHeader(CLIENT_HEADER))) {
            throw new ForbiddenException("missing client header");
        }
    }
}
