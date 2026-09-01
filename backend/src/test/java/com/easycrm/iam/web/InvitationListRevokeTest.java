package com.easycrm.iam.web;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class InvitationListRevokeTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    private String inviteAs(String bearer, String email) throws Exception {
        return mvc.perform(post("/api/v1/invitations")
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"role\":\"SALES_EXEC\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
    }

    @Test
    void pendingListShowsTheInviteAndNeverTheToken() throws Exception {
        var owner = tokens.provisionOwner("27");
        inviteAs(owner.token(), "list1@shop.in");

        mvc.perform(get("/api/v1/invitations")
                .header("Authorization", "Bearer " + owner.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].email").value("list1@shop.in"))
            .andExpect(jsonPath("$[0].role").value("SALES_EXEC"))
            .andExpect(jsonPath("$[0].expired").value(false))
            // The token is hashed at rest and must never come back out.
            .andExpect(jsonPath("$[0].acceptUrl").doesNotExist())
            .andExpect(jsonPath("$[0].token").doesNotExist());
    }

    @Test
    void revokeRemovesItFromThePendingList() throws Exception {
        var owner = tokens.provisionOwner("27");
        String body = inviteAs(owner.token(), "revoke1@shop.in");
        String id = JsonPath.read(body, "$.id");

        mvc.perform(delete("/api/v1/invitations/" + id)
                .header("Authorization", "Bearer " + owner.token()))
            .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/invitations")
                .header("Authorization", "Bearer " + owner.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    // Revoking frees the address: the partial unique index only covers PENDING rows, so a
    // re-invite after a revoke must succeed. This is the "resend" path (spec §3).
    @Test
    void revokeThenReinviteSucceeds() throws Exception {
        var owner = tokens.provisionOwner("27");
        String body = inviteAs(owner.token(), "resend@shop.in");
        String id = JsonPath.read(body, "$.id");

        mvc.perform(delete("/api/v1/invitations/" + id)
                .header("Authorization", "Bearer " + owner.token()))
            .andExpect(status().isNoContent());

        inviteAs(owner.token(), "resend@shop.in");   // 201 asserted inside the helper
    }

    @Test
    void revokingTwiceIs409() throws Exception {
        var owner = tokens.provisionOwner("27");
        String body = inviteAs(owner.token(), "twice@shop.in");
        String id = JsonPath.read(body, "$.id");

        mvc.perform(delete("/api/v1/invitations/" + id)
                .header("Authorization", "Bearer " + owner.token()))
            .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/invitations/" + id)
                .header("Authorization", "Bearer " + owner.token()))
            .andExpect(status().isConflict());
    }

    @Test
    void revokingAnUnknownIdIs404() throws Exception {
        var owner = tokens.provisionOwner("27");
        mvc.perform(delete("/api/v1/invitations/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + owner.token()))
            .andExpect(status().isNotFound());
    }

    @Test
    void salesExecCanNeitherListNorRevoke() throws Exception {
        var owner = tokens.provisionOwner("27");
        String body = inviteAs(owner.token(), "guard@shop.in");
        String id = JsonPath.read(body, "$.id");
        String exec = tokens.as(owner.tenantId(), UUID.randomUUID(), "SALES_EXEC");

        mvc.perform(get("/api/v1/invitations")
                .header("Authorization", "Bearer " + exec))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/invitations/" + id)
                .header("Authorization", "Bearer " + exec))
            .andExpect(status().isForbidden());
    }

    // invitation is a GLOBAL table with no RLS, so tenant scoping here is the service's
    // job and has to be proven rather than assumed.
    @Test
    void anotherTenantsOwnerSeesNothingAndCannotRevoke() throws Exception {
        var a = tokens.provisionOwner("27");
        var b = tokens.provisionOwner("29");
        String body = inviteAs(a.token(), "tenanta@shop.in");
        String id = JsonPath.read(body, "$.id");

        mvc.perform(get("/api/v1/invitations")
                .header("Authorization", "Bearer " + b.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(delete("/api/v1/invitations/" + id)
                .header("Authorization", "Bearer " + b.token()))
            .andExpect(status().isNotFound());
    }
}
