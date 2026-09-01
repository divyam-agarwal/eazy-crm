package com.easycrm.iam;

import com.easycrm.iam.web.dto.MemberResponse;
import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.security.RoleGuard;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.tenant.TenantRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    public MemberService(UserRepository users, TenantRepository tenants, RoleGuard roleGuard, AuditService audit) {
        this.users = users;
        this.tenants = tenants;
        this.roleGuard = roleGuard;
        this.audit = audit;
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
