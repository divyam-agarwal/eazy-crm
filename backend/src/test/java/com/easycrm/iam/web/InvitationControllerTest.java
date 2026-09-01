package com.easycrm.iam.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.easycrm.iam.AuthService;
import com.easycrm.iam.web.dto.SignupRequest;
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

@SpringBootTest
@AutoConfigureMockMvc
class InvitationControllerTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestTokens tokens;

    @Autowired
    AuthService auth;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private String invite(String email, String role) {
        return "{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}";
    }

    @Test
    void ownerCanInvite() throws Exception {
        var owner = tokens.provisionOwner("27");
        mvc.perform(post("/api/v1/invitations")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invite("ravi@shop.in", "SALES_EXEC")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("ravi@shop.in"))
                .andExpect(jsonPath("$.role").value("SALES_EXEC"))
                .andExpect(jsonPath("$.expiresAt").exists())
                // The plaintext token is returned exactly once, embedded in the accept URL.
                .andExpect(jsonPath("$.acceptUrl").exists());
    }

    @Test
    void salesExecCannotInvite() throws Exception {
        var owner = tokens.provisionOwner("27");
        String exec = tokens.as(owner.tenantId(), UUID.randomUUID(), "SALES_EXEC");
        mvc.perform(post("/api/v1/invitations")
                        .header("Authorization", "Bearer " + exec)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invite("ravi@shop.in", "SALES_EXEC")))
                .andExpect(status().isForbidden());
    }

    @Test
    void salesManagerCannotInvite() throws Exception {
        var owner = tokens.provisionOwner("27");
        String mgr = tokens.as(owner.tenantId(), UUID.randomUUID(), "SALES_MANAGER");
        mvc.perform(post("/api/v1/invitations")
                        .header("Authorization", "Bearer " + mgr)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invite("ravi@shop.in", "SALES_EXEC")))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedIsRejected() throws Exception {
        mvc.perform(post("/api/v1/invitations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invite("ravi@shop.in", "SALES_EXEC")))
                .andExpect(status().isUnauthorized());
    }

    // The partial unique index is the backstop; the service pre-check produces the clean 409.
    @Test
    void aSecondPendingInviteToTheSameAddressIs409() throws Exception {
        var owner = tokens.provisionOwner("27");
        mvc.perform(post("/api/v1/invitations")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invite("dup@shop.in", "SALES_EXEC")))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/invitations")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invite("dup@shop.in", "SALES_EXEC")))
                .andExpect(status().isConflict());
    }

    // lower(email) in the index means a case variant is the same address.
    @Test
    void aCaseVariantOfAPendingAddressIs409() throws Exception {
        var owner = tokens.provisionOwner("27");
        mvc.perform(post("/api/v1/invitations")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invite("case@shop.in", "SALES_EXEC")))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/invitations")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invite("CASE@shop.in", "SALES_EXEC")))
                .andExpect(status().isConflict());
    }

    /**
     * An address that is already a member cannot be re-invited under a different
     * capitalisation. The member check folds case (findByEmailIgnoreCase) precisely
     * because the invitation side already did: an exact-match check here would have let
     * "Ravi@shop.in" be invited over an existing "ravi@shop.in", and the accept would then
     * have created a SECOND ACTIVE user — possibly with a different role — for one human,
     * because uq_user_tenant_email compares the raw column. V32's lower(email) index is
     * the structural half; this is the readable-error half.
     */
    @Test
    void invitingACaseVariantOfAnExistingMemberIs409() throws Exception {
        // A REAL owner row, not provisionOwner's phantom principal: the member check is a
        // query against app_user, so the user has to actually exist.
        var signed = auth.signup(new SignupRequest(
                "inv-case-" + UUID.randomUUID().toString().substring(0, 8),
                "Biz",
                "27",
                null,
                "ravi@shop.in",
                null,
                "correct-horse"));
        TenantContext.clear();
        String ownerToken = tokens.as(signed.tenantId(), signed.userId(), "OWNER");

        mvc.perform(post("/api/v1/invitations")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invite("Ravi@Shop.in", "SALES_EXEC")))
                .andExpect(status().isConflict());

        // And the exact spelling is still refused, which was never in doubt.
        mvc.perform(post("/api/v1/invitations")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invite("ravi@shop.in", "SALES_EXEC")))
                .andExpect(status().isConflict());
    }

    @Test
    void anOwnerMayInviteAnotherOwner() throws Exception {
        var owner = tokens.provisionOwner("27");
        mvc.perform(post("/api/v1/invitations")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invite("co@shop.in", "OWNER")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("OWNER"));
    }

    @Test
    void aMalformedEmailIs400() throws Exception {
        var owner = tokens.provisionOwner("27");
        mvc.perform(post("/api/v1/invitations")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invite("not-an-email", "SALES_EXEC")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownRoleIs400() throws Exception {
        var owner = tokens.provisionOwner("27");
        mvc.perform(post("/api/v1/invitations")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invite("who@shop.in", "ADMIN")))
                .andExpect(status().isBadRequest());
    }
}
