package com.easycrm.iam.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.crm.CustomerSource;
import com.easycrm.iam.Role;
import com.easycrm.iam.User;
import com.easycrm.iam.UserRepository;
import com.easycrm.iam.UserStatus;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class MemberControllerTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestTokens tokens;

    @Autowired
    UserRepository users;

    @Autowired
    CustomerRepository customers;

    @Autowired
    TransactionTemplate tx;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private UUID addUser(UUID tenantId, String email, Role role, UserStatus status) {
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, null, "SYSTEM"));
        try {
            return tx.execute(
                    s -> users.save(new User(email, null, "hash", role, status)).getId());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void ownerCanListMembers() throws Exception {
        var owner = tokens.provisionOwner("27");
        addUser(owner.tenantId(), "a@x.test", Role.OWNER, UserStatus.ACTIVE);

        mvc.perform(get("/api/v1/members").header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value("a@x.test"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
    }

    @Test
    void salesExecCannotListMembers() throws Exception {
        var owner = tokens.provisionOwner("27");
        String exec = tokens.as(owner.tenantId(), UUID.randomUUID(), "SALES_EXEC");

        mvc.perform(get("/api/v1/members").header("Authorization", "Bearer " + exec))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void anUnknownMemberIs404() throws Exception {
        var owner = tokens.provisionOwner("27");

        mvc.perform(post("/api/v1/members/" + UUID.randomUUID() + "/disable")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void anUnknownRoleIs400FromBeanValidation() throws Exception {
        var owner = tokens.provisionOwner("27");
        UUID member = addUser(owner.tenantId(), "a@x.test", Role.SALES_EXEC, UserStatus.ACTIVE);

        mvc.perform(post("/api/v1/members/" + member + "/role")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void changeRoleReturnsTheUpdatedMember() throws Exception {
        var owner = tokens.provisionOwner("27");
        addUser(owner.tenantId(), "keeper@x.test", Role.OWNER, UserStatus.ACTIVE);
        UUID member = addUser(owner.tenantId(), "a@x.test", Role.SALES_EXEC, UserStatus.ACTIVE);

        mvc.perform(post("/api/v1/members/" + member + "/role")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"SALES_MANAGER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SALES_MANAGER"));
    }

    @Test
    void disableThenEnableRoundTrips() throws Exception {
        var owner = tokens.provisionOwner("27");
        addUser(owner.tenantId(), "keeper@x.test", Role.OWNER, UserStatus.ACTIVE);
        UUID member = addUser(owner.tenantId(), "a@x.test", Role.SALES_EXEC, UserStatus.ACTIVE);

        mvc.perform(post("/api/v1/members/" + member + "/disable").header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        mvc.perform(post("/api/v1/members/" + member + "/enable").header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void anUnauthenticatedRequestIs401() throws Exception {
        mvc.perform(get("/api/v1/members")).andExpect(status().isUnauthorized());
    }

    /**
     * No test anywhere else in this slice proves the reassign-first gate's blocker counts
     * actually reach the client over HTTP — only that the service-layer ConflictException
     * carries them. The design spec requires the counts reach the client so a frontend can
     * route straight to the right reassign screen instead of parsing prose out of the message.
     * A second OWNER is added so the last-owner invariant does not interfere with the
     * open-work one being exercised here.
     */
    @Test
    void disableWithOpenWorkReturns409WithMachineReadableCounts() throws Exception {
        var owner = tokens.provisionOwner("27");
        addUser(owner.tenantId(), "keeper@x.test", Role.OWNER, UserStatus.ACTIVE);
        UUID member = addUser(owner.tenantId(), "exec@x.test", Role.SALES_EXEC, UserStatus.ACTIVE);

        TenantContext.set(new TenantContext.TenantPrincipal(owner.tenantId(), null, "SYSTEM"));
        try {
            tx.executeWithoutResult(s -> customers.save(
                    new Customer("Shop A", null, "27", null, null, 0, member, null, CustomerSource.MANUAL)));
        } finally {
            TenantContext.clear();
        }

        mvc.perform(post("/api/v1/members/" + member + "/disable").header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"))
                .andExpect(jsonPath("$.error.fields.customers").value(1));
    }
}
