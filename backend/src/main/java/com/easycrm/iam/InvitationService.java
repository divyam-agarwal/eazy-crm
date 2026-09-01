package com.easycrm.iam;

import com.easycrm.iam.email.EmailSender;
import com.easycrm.iam.web.dto.AcceptInvitationRequest;
import com.easycrm.iam.web.dto.AuthResponse;
import com.easycrm.iam.web.dto.InvitationPreviewResponse;
import com.easycrm.iam.web.dto.InvitationResponse;
import com.easycrm.iam.web.dto.InviteRequest;
import com.easycrm.iam.web.dto.PendingInvitationResponse;
import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.security.JwtService;
import com.easycrm.platform.security.RoleGuard;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.tenant.Tenant;
import com.easycrm.tenant.TenantRepository;
import com.easycrm.tenant.TenantStatus;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class InvitationService {

    static final long TTL_DAYS = 7;

    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder base64Url = Base64.getUrlEncoder().withoutPadding();

    private final InvitationRepository invitations;
    private final UserRepository users;
    private final TenantRepository tenants;
    private final TokenHasher hasher;
    private final RoleGuard roleGuard;
    private final AuditService audit;
    private final EmailSender emailSender;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final RefreshTokenService refreshTokens;
    private final TransactionTemplate tx;
    private final String publicBaseUrl;

    public InvitationService(
            InvitationRepository invitations,
            UserRepository users,
            TenantRepository tenants,
            TokenHasher hasher,
            RoleGuard roleGuard,
            AuditService audit,
            EmailSender emailSender,
            PasswordEncoder encoder,
            JwtService jwt,
            RefreshTokenService refreshTokens,
            TransactionTemplate tx,
            @Value("${easycrm.public-base-url}") String publicBaseUrl) {
        this.invitations = invitations;
        this.users = users;
        this.tenants = tenants;
        this.hasher = hasher;
        this.roleGuard = roleGuard;
        this.audit = audit;
        this.emailSender = emailSender;
        this.encoder = encoder;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.tx = tx;
        this.publicBaseUrl = publicBaseUrl;
    }

    /**
     * Runs under the owner's JWT, so the user lookup below is RLS-scoped as normal.
     *
     * <p>The write runs through TransactionTemplate rather than @Transactional on this
     * method: a self-invoked @Transactional method is not proxied and its annotation is
     * silently ignored. AuthService uses the same form for the same reason.
     *
     * <p>The email is sent AFTER commit — no email for a rollback, matching
     * AuthService.signup.
     */
    public InvitationResponse invite(InviteRequest req) {
        roleGuard.requireOwner("only an owner may invite users");

        String rawToken = randomToken();
        Minted minted = tx.execute(status -> mintInTransaction(req, rawToken));

        String acceptUrl = publicBaseUrl + "/invite/" + rawToken;
        emailSender.send(req.email(), "You have been invited to EasyCRM", "Open this link to join: " + acceptUrl);

        return new InvitationResponse(minted.id(), minted.email(), req.role(), minted.expiresAt(), acceptUrl);
    }

    private record Minted(UUID id, String email, Instant expiresAt) {}

    /** Carries no annotation on purpose — the caller supplies the transaction. */
    private Minted mintInTransaction(InviteRequest req, String rawToken) {
        UUID tenantId = TenantContext.tenantId();
        // NOT orElse(null): invited_by is NOT NULL, so a null here would surface as a
        // DataIntegrityViolation's generic 409 — a broken server-side invariant dressed up
        // as the caller's fault. requireOwner has already rejected the only principal that
        // carries no user id (the "SYSTEM" one), so this is unreachable; say so out loud
        // rather than encoding a silent null.
        UUID invitedBy = TenantContext.get()
                .map(TenantContext.TenantPrincipal::userId)
                .orElseThrow(() -> new IllegalStateException("an owner principal must carry a user id to invite"));

        // Already a member? The read is RLS-scoped to this tenant. IgnoreCase because an
        // address is one identity however it is spelled: an exact match would let
        // "Ravi@shop.in" be invited over an existing "ravi@shop.in" and create a second
        // ACTIVE user for one human. uq_user_tenant_email_lower (V32) closes the
        // check-then-act window this pre-check leaves open.
        users.findByEmailIgnoreCase(req.email()).ifPresent(u -> {
            throw new ConflictException("that email is already a user of this workspace");
        });

        // Already invited? The partial unique index is the real guard against a race; this
        // pre-check exists so the ordinary case gets a clear message rather than the
        // DataIntegrityViolation backstop's generic one. Case-folded to agree with that
        // index, which is on lower(email).
        invitations
                .findByTenantIdAndStatusAndEmailIgnoreCase(tenantId, InvitationStatus.PENDING, req.email())
                .ifPresent(existing -> {
                    if (!existing.isExpired(Instant.now())) {
                        throw new ConflictException("that email already has a pending invitation");
                    }
                    // Expiry is lazy (D6), so an expired invitation stays PENDING forever and
                    // would otherwise block this address from ever being re-invited — the
                    // owner's own list already shows it as expired. Retire it here and carry
                    // on; the partial index frees itself in this same transaction.
                    //
                    // saveAndFlush, not save: Hibernate's action queue runs ALL inserts before
                    // ANY update, so a plain save would let the new PENDING row hit
                    // uq_invitation_pending_email while the old one is still PENDING on disk.
                    // The flush forces the UPDATE out first.
                    existing.revoke();
                    invitations.saveAndFlush(existing);
                });

        Invitation inv = invitations.save(new Invitation(
                tenantId,
                req.email(),
                Role.valueOf(req.role()),
                hasher.sha256Hex(rawToken),
                Instant.now().plus(TTL_DAYS, ChronoUnit.DAYS),
                invitedBy));

        audit.record("INVITE_SENT", invitedBy, Map.of("email", req.email(), "role", req.role()));

        return new Minted(inv.getId(), inv.getEmail(), inv.getExpiresAt());
    }

    @Transactional(readOnly = true)
    public List<PendingInvitationResponse> listPending() {
        roleGuard.requireOwner("only an owner may view invitations");
        Instant now = Instant.now();
        return invitations.findByTenantIdAndStatus(TenantContext.tenantId(), InvitationStatus.PENDING).stream()
                .map(i -> new PendingInvitationResponse(
                        i.getId(), i.getEmail(), i.getRole().name(), i.getExpiresAt(), i.isExpired(now)))
                .toList();
    }

    @Transactional
    public void revoke(UUID id) {
        roleGuard.requireOwner("only an owner may revoke an invitation");
        Invitation inv = invitations
                .findById(id)
                .filter(i -> i.getTenantId().equals(TenantContext.tenantId()))
                .orElseThrow(() -> new NotFoundException("invitation not found"));
        inv.revoke(); // throws ConflictException unless PENDING
        invitations.save(inv);
        audit.record(
                "INVITE_REVOKED",
                TenantContext.get().map(TenantContext.TenantPrincipal::userId).orElse(null),
                Map.of("email", inv.getEmail()));
    }

    /**
     * Pre-auth. NOT @Transactional: the tenant context must be set BEFORE the transaction
     * (and its Hibernate session) opens, because a session resolves its tenant only at
     * open and TenantAwareTransactionManager reads it in doBegin to set the RLS GUC. The
     * User insert below is @TenantId + RLS, so getting this order wrong does not throw —
     * it silently writes an unbound row. Same trap as challenge #9 and #52.
     */
    public AuthResponse accept(String rawToken, AcceptInvitationRequest req) {
        Invitation inv = requireLive(rawToken).invitation();

        TenantContext.set(new TenantContext.TenantPrincipal(inv.getTenantId(), null, "SYSTEM"));
        try {
            return tx.execute(status -> {
                // Re-read inside the transaction and claim it. @Version means a concurrent
                // second accept of this same token loses here and gets a 409.
                Invitation claimed = invitations
                        .findById(inv.getId())
                        .orElseThrow(() -> new NotFoundException("invitation not found"));

                User user = users.save(new User(
                        claimed.getEmail(),
                        req.phone(),
                        encoder.encode(req.password()),
                        claimed.getRole(),
                        UserStatus.ACTIVE));

                claimed.accept(user.getId(), Instant.now());
                invitations.save(claimed);

                audit.record(
                        "INVITE_ACCEPTED",
                        user.getId(),
                        Map.of(
                                "email",
                                claimed.getEmail(),
                                "role",
                                claimed.getRole().name()));

                String access = jwt.mint(
                        claimed.getTenantId(), user.getId(), claimed.getRole().name());
                String refresh = refreshTokens.issue(user.getId(), claimed.getTenantId());
                return new AuthResponse(
                        access,
                        refresh,
                        claimed.getTenantId(),
                        user.getId(),
                        claimed.getRole().name());
            });
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Pre-auth, but — unlike accept — it binds no tenant: invitation and tenant are BOTH
     * global tables, so an ordinary read is correct. "Pre-auth" and "must bind a tenant"
     * are separate properties and only accept has both.
     *
     * <p>Rejects with the SAME NotFoundException as accept, for every state, so the GET
     * cannot be used as an oracle against the POST.
     */
    @Transactional(readOnly = true)
    public InvitationPreviewResponse preview(String rawToken) {
        Live live = requireLive(rawToken);
        return new InvitationPreviewResponse(
                live.tenant().getBusinessName(),
                live.invitation().getEmail(),
                live.invitation().getRole().name());
    }

    /** A live invitation and the tenant it admits you to. */
    private record Live(Invitation invitation, Tenant tenant) {}

    /**
     * The single gate both public endpoints pass through, and the SOLE enforcement of the
     * enumeration contract (spec §8): unknown, revoked, consumed, expired and
     * suspended-tenant all leave by the same {@code throw}, so no caller can tell them
     * apart and the GET cannot be used as an oracle against the POST. Extracted so that
     * contract lives in one place — as two copies it was a remember-to-update-both
     * convention, and one helpful "this invitation has expired" would have quietly
     * reopened the oracle. See engineering-challenges #55.
     *
     * <p>The tenant load is not just for the preview's business name. A SUSPENDED tenant
     * must not mint credentials: AuthService.login refuses one explicitly, and accept is
     * the only other entry point that resolves a tenant from something other than an
     * existing JWT. Without this, a tenant suspended for non-payment could keep onboarding
     * staff through links issued before suspension.
     *
     * <p>Both tables read here are GLOBAL, so this is correct with no tenant context bound
     * — which is the state accept calls it in, deliberately and necessarily (see accept).
     */
    private Live requireLive(String rawToken) {
        Invitation inv = invitations
                .findByTokenHash(hasher.sha256Hex(rawToken))
                .filter(i -> i.getStatus() == InvitationStatus.PENDING)
                .filter(i -> !i.isExpired(Instant.now()))
                .orElseThrow(() -> new NotFoundException("invitation not found"));

        Tenant tenant = tenants.findById(inv.getTenantId())
                .filter(t -> t.getStatus() != TenantStatus.SUSPENDED)
                .orElseThrow(() -> new NotFoundException("invitation not found"));

        return new Live(inv, tenant);
    }

    private String randomToken() {
        byte[] bytes = new byte[32]; // 256 bits, matching RefreshTokenService
        random.nextBytes(bytes);
        return base64Url.encodeToString(bytes);
    }
}
