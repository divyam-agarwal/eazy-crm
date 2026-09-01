package com.easycrm.iam;

import com.easycrm.iam.email.EmailSender;
import com.easycrm.iam.web.dto.InvitationResponse;
import com.easycrm.iam.web.dto.InviteRequest;
import com.easycrm.iam.web.dto.PendingInvitationResponse;
import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.security.RoleGuard;
import com.easycrm.platform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class InvitationService {

    static final long TTL_DAYS = 7;

    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder base64Url = Base64.getUrlEncoder().withoutPadding();

    private final InvitationRepository invitations;
    private final UserRepository users;
    private final TokenHasher hasher;
    private final RoleGuard roleGuard;
    private final AuditService audit;
    private final EmailSender emailSender;
    private final TransactionTemplate tx;
    private final String publicBaseUrl;

    public InvitationService(InvitationRepository invitations, UserRepository users,
                             TokenHasher hasher, RoleGuard roleGuard, AuditService audit,
                             EmailSender emailSender, TransactionTemplate tx,
                             @Value("${easycrm.public-base-url}") String publicBaseUrl) {
        this.invitations = invitations;
        this.users = users;
        this.hasher = hasher;
        this.roleGuard = roleGuard;
        this.audit = audit;
        this.emailSender = emailSender;
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
        emailSender.send(req.email(), "You have been invited to EasyCRM",
            "Open this link to join: " + acceptUrl);

        return new InvitationResponse(minted.id(), minted.email(), req.role(),
            minted.expiresAt(), acceptUrl);
    }

    private record Minted(UUID id, String email, Instant expiresAt) {}

    /** Carries no annotation on purpose — the caller supplies the transaction. */
    private Minted mintInTransaction(InviteRequest req, String rawToken) {
        UUID tenantId = TenantContext.tenantId();
        UUID invitedBy = TenantContext.get()
            .map(TenantContext.TenantPrincipal::userId).orElse(null);

        // Already a member? The read is RLS-scoped to this tenant.
        users.findByEmail(req.email()).ifPresent(u -> {
            throw new ConflictException("that email is already a user of this workspace");
        });

        // Already invited? The partial unique index is the real guard against a race; this
        // pre-check exists so the ordinary case gets a clear message rather than the
        // DataIntegrityViolation backstop's generic one.
        boolean alreadyPending = invitations
            .findByTenantIdAndStatus(tenantId, InvitationStatus.PENDING).stream()
            .anyMatch(i -> i.getEmail().toLowerCase(Locale.ROOT)
                            .equals(req.email().toLowerCase(Locale.ROOT)));
        if (alreadyPending) {
            throw new ConflictException("that email already has a pending invitation");
        }

        Invitation inv = invitations.save(new Invitation(
            tenantId, req.email(), Role.valueOf(req.role()), hasher.sha256Hex(rawToken),
            Instant.now().plus(TTL_DAYS, ChronoUnit.DAYS), invitedBy));

        audit.record("INVITE_SENT", invitedBy,
            Map.of("email", req.email(), "role", req.role()));

        return new Minted(inv.getId(), inv.getEmail(), inv.getExpiresAt());
    }

    @Transactional(readOnly = true)
    public List<PendingInvitationResponse> listPending() {
        roleGuard.requireOwner("only an owner may view invitations");
        Instant now = Instant.now();
        return invitations
            .findByTenantIdAndStatus(TenantContext.tenantId(), InvitationStatus.PENDING)
            .stream()
            .map(i -> new PendingInvitationResponse(i.getId(), i.getEmail(),
                i.getRole().name(), i.getExpiresAt(), i.isExpired(now)))
            .toList();
    }

    @Transactional
    public void revoke(UUID id) {
        roleGuard.requireOwner("only an owner may revoke an invitation");
        Invitation inv = invitations.findById(id)
            .filter(i -> i.getTenantId().equals(TenantContext.tenantId()))
            .orElseThrow(() -> new NotFoundException("invitation not found"));
        inv.revoke();                 // throws ConflictException unless PENDING
        invitations.save(inv);
        audit.record("INVITE_REVOKED",
            TenantContext.get().map(TenantContext.TenantPrincipal::userId).orElse(null),
            Map.of("email", inv.getEmail()));
    }

    private String randomToken() {
        byte[] bytes = new byte[32]; // 256 bits, matching RefreshTokenService
        random.nextBytes(bytes);
        return base64Url.encodeToString(bytes);
    }
}
