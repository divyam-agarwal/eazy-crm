package com.easycrm.iam;

import com.easycrm.iam.email.EmailSender;
import com.easycrm.iam.web.dto.AuthResponse;
import com.easycrm.iam.web.dto.LoginRequest;
import com.easycrm.iam.web.dto.MeResponse;
import com.easycrm.iam.web.dto.SignupRequest;
import com.easycrm.iam.web.dto.TokenResponse;
import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.error.UnauthorizedException;
import com.easycrm.platform.error.ValidationException;
import com.easycrm.platform.gst.Gstin;
import com.easycrm.platform.gst.StateCode;
import com.easycrm.platform.security.JwtService;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.tenant.Tenant;
import com.easycrm.tenant.TenantRepository;
import com.easycrm.tenant.TenantStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthService {

    private static final long TRIAL_DAYS = 14;

    private final TenantRepository tenants;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final RefreshTokenService refreshTokens;
    private final AuditService audit;
    private final EmailSender emailSender;
    private final TransactionTemplate tx;

    public AuthService(
            TenantRepository tenants,
            UserRepository users,
            PasswordEncoder encoder,
            JwtService jwt,
            RefreshTokenService refreshTokens,
            AuditService audit,
            EmailSender emailSender,
            TransactionTemplate tx) {
        this.tenants = tenants;
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.audit = audit;
        this.emailSender = emailSender;
        this.tx = tx;
    }

    /**
     * Atomic self-serve provisioning. NOT annotated @Transactional: the tenant context must
     * be set BEFORE the transaction (and its Hibernate session) opens, because a session
     * resolves its tenant only at open. So we assign the tenant's UUIDv7 id up front, set
     * the context, then run tenant + owner inserts in one TransactionTemplate transaction
     * whose session opens already bound to the new tenant (@TenantId + RLS both satisfied).
     * See engineering-challenges #9.
     */
    public AuthResponse signup(SignupRequest req) {
        if (tenants.findBySlug(req.slug()).isPresent()) {
            throw new ConflictException("slug already taken");
        }
        // MF1: a buyer's GSTIN goes through Gstin.parse and StateCode.requireValid in
        // CustomerService; the seller's went through neither. QuotationService.isInterState
        // compares tenant.stateCode against the customer's to choose CGST+SGST vs IGST, so an
        // invalid seller state code silently decides the tax split of every quotation this tenant
        // ever issues — and the unvalidated GSTIN prints on every PDF letterhead.
        String stateCode = req.stateCode();
        String gstin = null;
        if (req.gstin() != null && !req.gstin().isBlank()) {
            Gstin parsed = Gstin.parse(req.gstin()); // 422 on charset, checksum or state prefix
            if (!parsed.stateCode().equals(stateCode)) {
                throw new ValidationException("stateCode", "must match the GSTIN state code");
            }
            // Persist the parsed form, not the raw input: CustomerService already stores g.value(),
            // and an un-normalised seller GSTIN prints on every PDF letterhead and defeats exact-match
            // against a customer's.
            gstin = parsed.value();
        }
        StateCode.requireValid(stateCode);
        Tenant tenant = new Tenant(
                req.slug(),
                req.businessName(),
                stateCode,
                gstin,
                TenantStatus.TRIAL,
                Instant.now().plus(TRIAL_DAYS, ChronoUnit.DAYS));

        TenantContext.set(new TenantContext.TenantPrincipal(tenant.getId(), null, "SYSTEM"));
        try {
            AuthResponse res = tx.execute(status -> {
                tenants.save(tenant);
                User owner = users.save(new User(
                        req.email(), req.phone(), encoder.encode(req.password()), Role.OWNER, UserStatus.ACTIVE));

                audit.record("SIGNUP", owner.getId(), Map.of("slug", req.slug()));

                String access = jwt.mint(tenant.getId(), owner.getId(), Role.OWNER.name());
                String refresh = refreshTokens.issue(owner.getId(), tenant.getId());
                return new AuthResponse(access, refresh, tenant.getId(), owner.getId(), Role.OWNER.name());
            });
            // Sent only after the provisioning transaction commits (no email for a rollback).
            emailSender.send(req.email(), "Welcome to EasyCRM", "Your workspace " + req.slug() + " is ready.");
            return res;
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Resolve the tenant by slug, bind the context BEFORE the transaction (so the RLS-scoped
     * user lookup and audit run under the tenant), verify the bcrypt hash, and issue tokens.
     * Every failure throws the same generic 401 (no slug/email enumeration).
     */
    public AuthResponse login(LoginRequest req) {
        Tenant tenant =
                tenants.findBySlug(req.slug()).orElseThrow(() -> new UnauthorizedException("invalid credentials"));
        if (tenant.getStatus() == TenantStatus.SUSPENDED) {
            throw new UnauthorizedException("invalid credentials");
        }
        TenantContext.set(new TenantContext.TenantPrincipal(tenant.getId(), null, "SYSTEM"));
        try {
            return tx.execute(status -> {
                User user = users.findByEmail(req.email()).orElse(null);
                if (user == null
                        || user.getStatus() != UserStatus.ACTIVE
                        || !encoder.matches(req.password(), user.getPasswordHash())) {
                    if (user != null) {
                        // REQUIRES_NEW: this must survive the rollback caused by the throw below.
                        audit.recordIndependently("LOGIN_FAILED", user.getId(), Map.of("email", req.email()));
                    }
                    throw new UnauthorizedException("invalid credentials");
                }
                audit.record("LOGIN_SUCCESS", user.getId(), Map.of());
                String access =
                        jwt.mint(tenant.getId(), user.getId(), user.getRole().name());
                String refresh = refreshTokens.issue(user.getId(), tenant.getId());
                return new AuthResponse(
                        access,
                        refresh,
                        tenant.getId(),
                        user.getId(),
                        user.getRole().name());
            });
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Rotate the opaque refresh token (global table, resolves user+tenant), then load the
     * user under its tenant to mint a fresh access token with the current role.
     */
    public TokenResponse refresh(String rawToken) {
        RefreshTokenService.RotationResult rot = refreshTokens.rotate(rawToken);
        TenantContext.set(new TenantContext.TenantPrincipal(rot.tenantId(), rot.userId(), "SYSTEM"));
        try {
            return tx.execute(status -> {
                User user = users.findById(rot.userId())
                        .orElseThrow(() -> new UnauthorizedException("invalid refresh token"));
                String access =
                        jwt.mint(rot.tenantId(), rot.userId(), user.getRole().name());
                return new TokenResponse(access, rot.newRawToken());
            });
        } finally {
            TenantContext.clear();
        }
    }

    public void logout(String rawToken) {
        refreshTokens.revoke(rawToken);
    }

    /**
     * Current principal. The JWT filter has already set TenantContext for this request, so
     * this @Transactional read runs under the tenant's RLS context (GUC set at doBegin).
     */
    @Transactional(readOnly = true)
    public MeResponse me() {
        TenantContext.TenantPrincipal p =
                TenantContext.get().orElseThrow(() -> new UnauthorizedException("not authenticated"));
        User user = users.findById(p.userId()).orElseThrow(() -> new UnauthorizedException("not authenticated"));
        Tenant tenant =
                tenants.findById(p.tenantId()).orElseThrow(() -> new UnauthorizedException("not authenticated"));
        return new MeResponse(
                user.getId(), tenant.getId(), user.getEmail(), user.getRole().name(), tenant.getSlug());
    }
}
