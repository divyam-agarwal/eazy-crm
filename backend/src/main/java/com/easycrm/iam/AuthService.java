package com.easycrm.iam;

import com.easycrm.iam.web.dto.AuthResponse;
import com.easycrm.iam.web.dto.SignupRequest;
import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.security.JwtService;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.tenant.Tenant;
import com.easycrm.tenant.TenantRepository;
import com.easycrm.tenant.TenantStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Service
public class AuthService {

    private static final long TRIAL_DAYS = 14;

    private final TenantRepository tenants;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final RefreshTokenService refreshTokens;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public AuthService(TenantRepository tenants, UserRepository users,
                       PasswordEncoder encoder, JwtService jwt,
                       RefreshTokenService refreshTokens, AuditService audit,
                       TransactionTemplate tx) {
        this.tenants = tenants;
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.audit = audit;
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
        Tenant tenant = new Tenant(
            req.slug(), req.businessName(), req.stateCode(), req.gstin(),
            TenantStatus.TRIAL, Instant.now().plus(TRIAL_DAYS, ChronoUnit.DAYS));

        TenantContext.set(new TenantContext.TenantPrincipal(tenant.getId(), null, "SYSTEM"));
        try {
            return tx.execute(status -> {
                tenants.save(tenant);
                User owner = users.save(new User(
                    req.email(), req.phone(), encoder.encode(req.password()),
                    Role.OWNER, UserStatus.ACTIVE));

                audit.record("SIGNUP", owner.getId(), Map.of("slug", req.slug()));

                String access = jwt.mint(tenant.getId(), owner.getId(), Role.OWNER.name());
                String refresh = refreshTokens.issue(owner.getId(), tenant.getId());
                return new AuthResponse(access, refresh, tenant.getId(), owner.getId(), Role.OWNER.name());
            });
        } finally {
            TenantContext.clear();
        }
    }
}
