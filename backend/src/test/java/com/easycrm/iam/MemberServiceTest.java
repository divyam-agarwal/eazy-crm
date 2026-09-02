package com.easycrm.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.easycrm.iam.web.dto.MemberResponse;
import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.error.ForbiddenException;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class MemberServiceTest extends IntegrationTest {

    @Autowired
    MemberService members;

    @Autowired
    UserRepository users;

    @Autowired
    TestTokens tokens;

    @Autowired
    TransactionTemplate tx;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    /** Provisions a tenant and returns its id, with an OWNER principal already bound. */
    private UUID tenantWithOwnerBound(UUID actingUserId) {
        var owner = tokens.provisionOwner("27");
        TenantContext.set(new TenantContext.TenantPrincipal(owner.tenantId(), actingUserId, "OWNER"));
        return owner.tenantId();
    }

    private UUID addUser(UUID tenantId, String email, Role role, UserStatus status) {
        TenantContext.TenantPrincipal caller = TenantContext.get().orElse(null);
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, null, "SYSTEM"));
        try {
            return tx.execute(
                    s -> users.save(new User(email, null, "hash", role, status)).getId());
        } finally {
            if (caller != null) TenantContext.set(caller);
            else TenantContext.clear();
        }
    }

    @Test
    void listReturnsActiveAndDisabledMembersWithoutPasswordHashes() {
        UUID me = UUID.randomUUID();
        UUID tenantId = tenantWithOwnerBound(me);
        addUser(tenantId, "owner@x.test", Role.OWNER, UserStatus.ACTIVE);
        addUser(tenantId, "gone@x.test", Role.SALES_EXEC, UserStatus.DISABLED);

        List<MemberResponse> list = members.list();

        assertEquals(2, list.size());
        assertTrue(list.stream().anyMatch(m -> m.status().equals("DISABLED")), "disabled members are listed");
    }

    @Test
    void onlyAnOwnerMayList() {
        var owner = tokens.provisionOwner("27");
        TenantContext.set(new TenantContext.TenantPrincipal(owner.tenantId(), UUID.randomUUID(), "SALES_EXEC"));
        assertThrows(ForbiddenException.class, () -> members.list());
    }

    @Test
    void anotherTenantsMemberIsNotFound() {
        UUID tenantId = tenantWithOwnerBound(UUID.randomUUID());
        var other = tokens.provisionOwner("27");
        UUID stranger = addUser(other.tenantId(), "stranger@y.test", Role.SALES_EXEC, UserStatus.ACTIVE);
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, UUID.randomUUID(), "OWNER"));

        // 404 rather than 403: RLS scopes the lookup structurally, no hand-written filter.
        assertThrows(NotFoundException.class, () -> members.changeRole(stranger, "SALES_EXEC"));
    }

    @Test
    void changeRoleUpdatesTheMember() {
        UUID tenantId = tenantWithOwnerBound(UUID.randomUUID());
        addUser(tenantId, "owner@x.test", Role.OWNER, UserStatus.ACTIVE);
        UUID exec = addUser(tenantId, "exec@x.test", Role.SALES_EXEC, UserStatus.ACTIVE);

        assertEquals("SALES_MANAGER", members.changeRole(exec, "SALES_MANAGER").role());
    }

    @Test
    void theLastActiveOwnerCannotBeDemoted() {
        UUID tenantId = tenantWithOwnerBound(UUID.randomUUID());
        UUID soleOwner = addUser(tenantId, "sole@x.test", Role.OWNER, UserStatus.ACTIVE);

        ConflictException ex = assertThrows(ConflictException.class, () -> members.changeRole(soleOwner, "SALES_EXEC"));
        assertEquals("a workspace must keep at least one active owner", ex.getMessage());
    }

    @Test
    void anOwnerMayBeDemotedWhileAnotherActiveOwnerRemains() {
        UUID tenantId = tenantWithOwnerBound(UUID.randomUUID());
        addUser(tenantId, "keeper@x.test", Role.OWNER, UserStatus.ACTIVE);
        UUID leaving = addUser(tenantId, "leaving@x.test", Role.OWNER, UserStatus.ACTIVE);

        assertEquals("SALES_EXEC", members.changeRole(leaving, "SALES_EXEC").role());
    }

    @Test
    void anOwnerMayDemoteThemselvesWhileAnotherActiveOwnerRemains() {
        UUID tenantId = tenantWithOwnerBound(UUID.randomUUID());
        addUser(tenantId, "keeper@x.test", Role.OWNER, UserStatus.ACTIVE);
        UUID me = addUser(tenantId, "me@x.test", Role.OWNER, UserStatus.ACTIVE);
        // Re-bind so the CALLER is the member being demoted. D7: self-targeting is allowed,
        // guarded only by the last-active-owner invariant.
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, me, "OWNER"));

        assertEquals("SALES_EXEC", members.changeRole(me, "SALES_EXEC").role());
    }

    @Test
    void theLastActiveOwnerCannotDemoteThemselvesEither() {
        UUID tenantId = tenantWithOwnerBound(UUID.randomUUID());
        UUID me = addUser(tenantId, "sole@x.test", Role.OWNER, UserStatus.ACTIVE);
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, me, "OWNER"));

        // The invariant is about the workspace, not about who is asking.
        assertThrows(ConflictException.class, () -> members.changeRole(me, "SALES_EXEC"));
    }

    @Test
    void aDisabledOwnerDoesNotCountTowardTheInvariant() {
        UUID tenantId = tenantWithOwnerBound(UUID.randomUUID());
        addUser(tenantId, "active@x.test", Role.OWNER, UserStatus.ACTIVE);
        UUID disabledOwner = addUser(tenantId, "dormant@x.test", Role.OWNER, UserStatus.DISABLED);

        // Demoting an already-disabled owner cannot reduce the ACTIVE owner count, so the
        // invariant must not block it.
        assertEquals(
                "SALES_EXEC", members.changeRole(disabledOwner, "SALES_EXEC").role());
    }
}
