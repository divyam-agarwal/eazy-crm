package com.easycrm.iam.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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

@SpringBootTest
@AutoConfigureMockMvc
class InvitationPreviewTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestTokens tokens;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private String inviteAndExtractToken(String bearer, String email) throws Exception {
        String body = mvc.perform(post("/api/v1/invitations")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"role\":\"SALES_EXEC\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String acceptUrl = JsonPath.read(body, "$.acceptUrl");
        return acceptUrl.substring(acceptUrl.lastIndexOf('/') + 1);
    }

    @Test
    void previewNamesTheWorkspaceAndTheInvitedRole() throws Exception {
        var owner = tokens.provisionOwner("27");
        String token = inviteAndExtractToken(owner.token(), "prev@shop.in");

        mvc.perform(get("/api/v1/auth/invitations/" + token))
                .andExpect(status().isOk())
                // TestTokens.provisionOwner names every tenant "Test Biz".
                .andExpect(jsonPath("$.businessName").value("Test Biz"))
                .andExpect(jsonPath("$.email").value("prev@shop.in"))
                .andExpect(jsonPath("$.role").value("SALES_EXEC"));
    }

    @Test
    void previewNeedsNoAuthentication() throws Exception {
        var owner = tokens.provisionOwner("27");
        String token = inviteAndExtractToken(owner.token(), "noauth@shop.in");
        mvc.perform(get("/api/v1/auth/invitations/" + token)).andExpect(status().isOk());
    }

    @Test
    void previewOfAnUnknownTokenIs404() throws Exception {
        mvc.perform(get("/api/v1/auth/invitations/nope-not-real")).andExpect(status().isNotFound());
    }

    /**
     * The preview must not be usable as an oracle against the POST: a consumed token and a
     * token that never existed have to look identical here too, or a prober could learn
     * from GET what the POST refuses to tell them.
     */
    @Test
    void previewCannotDistinguishAConsumedTokenFromAnUnknownOne() throws Exception {
        var owner = tokens.provisionOwner("27");
        String token = inviteAndExtractToken(owner.token(), "oracle@shop.in");
        mvc.perform(post("/api/v1/auth/invitations/" + token + "/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"correct-horse\"}"))
                .andExpect(status().isCreated());

        String consumed = mvc.perform(get("/api/v1/auth/invitations/" + token))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String unknown = mvc.perform(get("/api/v1/auth/invitations/never-existed"))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertEquals(unknown, consumed, "preview must not reveal that a token once existed");
    }

    /** The 404 body a preview produces for the given token. */
    private String rejectedPreviewBody(String token) throws Exception {
        return mvc.perform(get("/api/v1/auth/invitations/" + token))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /**
     * Asserted as BYTES, not merely as a status: a status-only assertion would stay green
     * if someone added a helpful "this invitation was revoked" message, which is exactly
     * the oracle challenge #55 exists to close.
     */
    @Test
    void previewOfARevokedInvitationIs404() throws Exception {
        var owner = tokens.provisionOwner("27");
        String body = mvc.perform(post("/api/v1/invitations")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"prevrev@shop.in\",\"role\":\"SALES_EXEC\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String acceptUrl = JsonPath.read(body, "$.acceptUrl");
        String token = acceptUrl.substring(acceptUrl.lastIndexOf('/') + 1);
        String id = JsonPath.read(body, "$.id");

        mvc.perform(delete("/api/v1/invitations/" + id).header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isNoContent());

        assertEquals(
                rejectedPreviewBody("never-existed"),
                rejectedPreviewBody(token),
                "preview must not reveal that a token was revoked rather than unknown");
    }

    /**
     * The preview must refuse a SUSPENDED tenant too — not because previewing itself mints
     * anything, but because a preview that succeeded where the accept fails would name the
     * workspace and confirm the token, turning the GET back into the oracle the POST
     * refuses to be.
     */
    @Test
    void previewOfASuspendedTenantsInvitationIs404() throws Exception {
        var owner = tokens.provisionOwner("27");
        String token = inviteAndExtractToken(owner.token(), "prevsusp@shop.in");

        tokens.suspend(owner.tenantId());

        assertEquals(
                rejectedPreviewBody("never-existed"),
                rejectedPreviewBody(token),
                "a suspended tenant's invitation must look exactly like an unknown token");
    }
}
