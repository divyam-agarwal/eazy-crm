package com.easycrm.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.crm.CustomerSource;
import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class MemberDisableTest extends IntegrationTest {

    @Autowired
    MemberService members;

    @Autowired
    UserRepository users;

    @Autowired
    CustomerRepository customers;

    @Autowired
    RefreshTokenService refreshTokens;

    @Autowired
    TestTokens tokens;

    @Autowired
    TransactionTemplate tx;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private UUID tenantWithOwnerBound() {
        var owner = tokens.provisionOwner("27");
        TenantContext.set(new TenantContext.TenantPrincipal(owner.tenantId(), UUID.randomUUID(), "OWNER"));
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
    void disableFlipsStatusAndKillsLiveSessions() {
        UUID tenantId = tenantWithOwnerBound();
        addUser(tenantId, "owner@x.test", Role.OWNER, UserStatus.ACTIVE);
        UUID exec = addUser(tenantId, "exec@x.test", Role.SALES_EXEC, UserStatus.ACTIVE);
        String session = refreshTokens.issue(exec, tenantId);

        assertEquals("DISABLED", members.disable(exec).status());

        assertThrows(
                com.easycrm.platform.error.UnauthorizedException.class,
                () -> refreshTokens.rotate(session),
                "disable must revoke the member's live sessions");
    }

    @Test
    void openWorkBlocksDisableAndTheConflictNamesTheCounts() {
        UUID tenantId = tenantWithOwnerBound();
        addUser(tenantId, "owner@x.test", Role.OWNER, UserStatus.ACTIVE);
        UUID exec = addUser(tenantId, "exec@x.test", Role.SALES_EXEC, UserStatus.ACTIVE);

        TenantContext.TenantPrincipal caller = TenantContext.get().orElseThrow();
        tx.executeWithoutResult(s ->
                customers.save(new Customer("Shop A", null, "27", null, null, 0, exec, null, CustomerSource.MANUAL)));
        TenantContext.set(caller);

        ConflictException ex = assertThrows(ConflictException.class, () -> members.disable(exec));
        assertTrue(ex.getMessage().contains("1 customers"), "the message names what blocks it: " + ex.getMessage());
        assertEquals(1L, ex.getFields().get("customers"), "counts are machine-readable");
        assertFalse(ex.getFields().containsKey("enquiries"), "only non-zero blockers are reported");

        TenantContext.set(caller);
        assertEquals(UserStatus.ACTIVE, users.findById(exec).orElseThrow().getStatus(), "no partial mutation");
    }

    @Test
    void theLastActiveOwnerCannotBeDisabled() {
        UUID tenantId = tenantWithOwnerBound();
        UUID soleOwner = addUser(tenantId, "sole@x.test", Role.OWNER, UserStatus.ACTIVE);

        ConflictException ex = assertThrows(ConflictException.class, () -> members.disable(soleOwner));
        assertEquals("a workspace must keep at least one active owner", ex.getMessage());
    }

    @Test
    void enableRestoresADisabledMember() {
        UUID tenantId = tenantWithOwnerBound();
        addUser(tenantId, "owner@x.test", Role.OWNER, UserStatus.ACTIVE);
        UUID exec = addUser(tenantId, "exec@x.test", Role.SALES_EXEC, UserStatus.DISABLED);

        assertEquals("ACTIVE", members.enable(exec).status());
    }

    @Test
    void enablingAnActiveMemberConflicts() {
        UUID tenantId = tenantWithOwnerBound();
        addUser(tenantId, "owner@x.test", Role.OWNER, UserStatus.ACTIVE);
        UUID exec = addUser(tenantId, "exec@x.test", Role.SALES_EXEC, UserStatus.ACTIVE);

        assertThrows(ConflictException.class, () -> members.enable(exec));
    }
}
