package com.easycrm.iam.web;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class InvitationControllerTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

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
