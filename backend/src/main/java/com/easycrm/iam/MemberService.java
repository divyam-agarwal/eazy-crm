package com.easycrm.iam;

import com.easycrm.iam.web.dto.MemberResponse;
import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.security.RoleGuard;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.tenant.TenantRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner-only administration of the people in a workspace. The sequel to InvitationService:
 * that one gets a member in, this one manages them afterwards. See spec
 * 2026-09-01-members-management-design.md.
 */
@Service
public class MemberService {

    private final UserRepository users;
    private final TenantRepository tenants;
    private final RoleGuard roleGuard;
    private final AuditService audit;
    private final RefreshTokenService refreshTokens;
    private final List<AssignedWorkload> workloads;

    public MemberService(
            UserRepository users,
            TenantRepository tenants,
            RoleGuard roleGuard,
            AuditService audit,
            RefreshTokenService refreshTokens,
            List<AssignedWorkload> workloads) {
        this.users = users;
        this.tenants = tenants;
        this.roleGuard = roleGuard;
        this.audit = audit;
        this.refreshTokens = refreshTokens;
        this.workloads = workloads;
    }

    @Transactional(readOnly = true)
    public List<MemberResponse> list() {
        roleGuard.requireOwner("only an owner may view members");
        // findAll is @TenantId + RLS scoped; DISABLED members are included on purpose, since
        // the point of the list is to administer them.
        return users.findAll(Sort.by("email")).stream()
                .map(MemberService::toResponse)
                .toList();
    }

    @Transactional
    public MemberResponse changeRole(UUID id, String role) {
        roleGuard.requireOwner("only an owner may change a member's role");
        lockTenant();
        User member = requireMember(id);
        // Already @Pattern-validated at the edge, so valueOf cannot throw here.
        Role target = Role.valueOf(role);
        Role previous = member.getRole();

        if (previous == Role.OWNER && target != Role.OWNER) {
            requireAnotherActiveOwner(member);
        }
        member.changeRole(target);
        users.save(member);

        audit.record(
                "MEMBER_ROLE_CHANGED",
                actorUserId(),
                Map.of("email", member.getEmail(), "from", previous.name(), "to", target.name()));
        return toResponse(member);
    }

    @Transactional
    public MemberResponse disable(UUID id) {
        roleGuard.requireOwner("only an owner may disable a member");
        lockTenant();
        User member = requireMember(id);

        // Both invariants BEFORE any mutation, so a refusal leaves no partial state.
        if (member.getRole() == Role.OWNER) {
            requireAnotherActiveOwner(member);
        }
        requireNoOpenWork(member);

        member.disable(); // ConflictException if already disabled
        users.save(member);
        int revoked = refreshTokens.revokeAllForUser(member.getId(), TenantContext.tenantId());

        audit.record("MEMBER_DISABLED", actorUserId(), Map.of("email", member.getEmail(), "sessionsRevoked", revoked));
        return toResponse(member);
    }

    @Transactional
    public MemberResponse enable(UUID id) {
        roleGuard.requireOwner("only an owner may enable a member");
        // Cannot breach the owner invariant (it only ever adds an active owner), but takes
        // the lock anyway: uniform is cheaper to reason about than per-path, and enable is
        // rare enough that the extra row lock costs nothing.
        lockTenant();
        User member = requireMember(id);

        member.enable(); // ConflictException if already active
        users.save(member);

        audit.record("MEMBER_ENABLED", actorUserId(), Map.of("email", member.getEmail()));
        return toResponse(member);
    }

    /**
     * A member who cannot log in cannot action their work, so disabling them while they hold
     * any would strand it. Refuses with a 409 naming every blocker at once — an owner who
     * clears customers, retries, then discovers follow-ups has been made to do the job twice.
     *
     * <p>Sorted by label so the message and the field order are deterministic: the injected
     * List's order is Spring's bean-definition order, which is not a contract.
     */
    private void requireNoOpenWork(User member) {
        Map<String, Object> blockers = new LinkedHashMap<>();
        workloads.stream().sorted(Comparator.comparing(AssignedWorkload::label)).forEach(w -> {
            long open = w.countOpenFor(member.getId());
            if (open > 0) blockers.put(w.label(), open);
        });
        if (blockers.isEmpty()) return;

        String detail = blockers.entrySet().stream()
                .map(e -> e.getValue() + " " + e.getKey())
                .collect(Collectors.joining(", "));
        throw new ConflictException(
                "member still holds open work and cannot be disabled: " + detail + "; reassign it first", blockers);
    }

    /**
     * Serializes member-admin writes within one tenant. MUST be called before any invariant
     * count, or the count is check-then-act again. See TenantRepository.findForUpdate.
     */
    void lockTenant() {
        tenants.findForUpdate(TenantContext.tenantId())
                .orElseThrow(() -> new IllegalStateException("no tenant row for the authenticated tenant"));
    }

    /**
     * A workspace with no active owner can never invite, promote or re-enable anyone again,
     * and this product has no support surface — recovery would be a manual production UPDATE.
     *
     * <p>Skipped when the member is already disabled: they are not holding the workspace up,
     * so changing their role cannot reduce the active-owner count.
     */
    void requireAnotherActiveOwner(User member) {
        if (member.getStatus() != UserStatus.ACTIVE) return;
        if (users.countByRoleAndStatus(Role.OWNER, UserStatus.ACTIVE) <= 1) {
            throw new ConflictException("a workspace must keep at least one active owner");
        }
    }

    /**
     * No hand-written tenant filter: app_user is @TenantId + RLS, so another tenant's id
     * simply does not resolve. This is the deliberate contrast with
     * InvitationService.revoke, whose filter is load-bearing only because invitation is a
     * global table (challenge #54).
     */
    User requireMember(UUID id) {
        return users.findById(id).orElseThrow(() -> new NotFoundException("member not found"));
    }

    static UUID actorUserId() {
        return TenantContext.get().map(TenantContext.TenantPrincipal::userId).orElse(null);
    }

    static MemberResponse toResponse(User u) {
        return new MemberResponse(
                u.getId(),
                u.getEmail(),
                u.getPhone(),
                u.getRole().name(),
                u.getStatus().name(),
                u.getCreatedAt());
    }
}
