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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class InvitationAcceptTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;
    @Autowired com.easycrm.platform.ratelimit.RateLimitProperties rateLimits;

    @AfterEach void clear() { TenantContext.clear(); }

    private static final String ACCEPT = "{\"password\":\"correct-horse\"}";

    /** Invite, and return the raw token pulled out of acceptUrl's last path segment. */
    private String inviteAndExtractToken(String bearer, String email, String role)
            throws Exception {
        String body = mvc.perform(post("/api/v1/invitations")
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String acceptUrl = JsonPath.read(body, "$.acceptUrl");
        return acceptUrl.substring(acceptUrl.lastIndexOf('/') + 1);
    }

    @Test
    void acceptCreatesAnActiveUserWithTheInvitedRoleAndReturnsTokens() throws Exception {
        var owner = tokens.provisionOwner("27");
        String token = inviteAndExtractToken(owner.token(), "new@shop.in", "SALES_EXEC");

        mvc.perform(post("/api/v1/auth/invitations/" + token + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content(ACCEPT))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accessToken").exists())
            .andExpect(jsonPath("$.refreshToken").exists())
            .andExpect(jsonPath("$.userId").exists())
            // The user lands in the INVITING tenant, with the INVITED role.
            .andExpect(jsonPath("$.tenantId").value(owner.tenantId().toString()))
            .andExpect(jsonPath("$.role").value("SALES_EXEC"));
    }

    /**
     * D7's justification, made executable: login requires a tenant SLUG the invitee has
     * never seen, so the tokens returned by accept are the only way in. This asserts the
     * returned access token actually authenticates.
     */
    @Test
    void theReturnedAccessTokenWorksImmediately() throws Exception {
        var owner = tokens.provisionOwner("27");
        String token = inviteAndExtractToken(owner.token(), "works@shop.in", "SALES_EXEC");

        String body = mvc.perform(post("/api/v1/auth/invitations/" + token + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content(ACCEPT))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String access = JsonPath.read(body, "$.accessToken");

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("works@shop.in"))
            .andExpect(jsonPath("$.role").value("SALES_EXEC"));
    }

    @Test
    void theInvitationIsSingleUse() throws Exception {
        var owner = tokens.provisionOwner("27");
        String token = inviteAndExtractToken(owner.token(), "once@shop.in", "SALES_EXEC");

        mvc.perform(post("/api/v1/auth/invitations/" + token + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content(ACCEPT))
            .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/auth/invitations/" + token + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content(ACCEPT))
            .andExpect(status().isNotFound());
    }

    @Test
    void anAcceptedInviteLeavesThePendingList() throws Exception {
        var owner = tokens.provisionOwner("27");
        String token = inviteAndExtractToken(owner.token(), "gone@shop.in", "SALES_EXEC");

        mvc.perform(post("/api/v1/auth/invitations/" + token + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content(ACCEPT))
            .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/invitations")
                .header("Authorization", "Bearer " + owner.token()))
            .andExpect(jsonPath("$.length()").value(0));
    }

    /** The 404 body an accept produces for the given token. */
    private String rejectedAcceptBody(String token) throws Exception {
        return mvc.perform(post("/api/v1/auth/invitations/" + token + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content(ACCEPT))
            .andExpect(status().isNotFound())
            .andReturn().getResponse().getContentAsString();
    }

    /**
     * Revoked must be indistinguishable from never-existed, as BYTES and not merely as a
     * status. Asserting only isNotFound() would stay green if someone later added a
     * helpful "this invitation was revoked" message — which is precisely the enumeration
     * oracle challenge #55 exists to close.
     */
    @Test
    void aRevokedInvitationCannotBeAccepted() throws Exception {
        var owner = tokens.provisionOwner("27");
        String body = mvc.perform(post("/api/v1/invitations")
                .header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"rev@shop.in\",\"role\":\"SALES_EXEC\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String acceptUrl = JsonPath.read(body, "$.acceptUrl");
        String token = acceptUrl.substring(acceptUrl.lastIndexOf('/') + 1);
        String id = JsonPath.read(body, "$.id");

        mvc.perform(delete("/api/v1/invitations/" + id)
                .header("Authorization", "Bearer " + owner.token()))
            .andExpect(status().isNoContent());

        assertEquals(rejectedAcceptBody("never-existed"), rejectedAcceptBody(token),
            "a revoked token must be indistinguishable from one that never existed");
    }

    /**
     * A SUSPENDED tenant must not mint credentials. AuthService.login refuses one
     * explicitly; accept is the only other entry point that resolves a tenant from
     * something other than an existing JWT, so a tenant suspended for non-payment could
     * otherwise keep onboarding staff on links issued before suspension — each new user
     * holding an indefinitely-refreshable credential.
     *
     * <p>Asserted as bytes for the same reason as the revoked case: a distinct status or
     * message here would reopen the enumeration oracle.
     */
    @Test
    void aSuspendedTenantCannotAcceptAnInvitation() throws Exception {
        var owner = tokens.provisionOwner("27");
        String token = inviteAndExtractToken(owner.token(), "susp@shop.in", "SALES_EXEC");

        tokens.suspend(owner.tenantId());

        assertEquals(rejectedAcceptBody("never-existed"), rejectedAcceptBody(token),
            "a suspended tenant's invitation must look exactly like an unknown token");
    }

    @Test
    void anUnknownTokenIs404() throws Exception {
        mvc.perform(post("/api/v1/auth/invitations/not-a-real-token/accept")
                .contentType(MediaType.APPLICATION_JSON).content(ACCEPT))
            .andExpect(status().isNotFound());
    }

    // Enumeration safety: an unknown token and a consumed one must be indistinguishable —
    // same status AND same body. A different message would confirm a token had existed.
    @Test
    void aConsumedTokenIsIndistinguishableFromAnUnknownOne() throws Exception {
        var owner = tokens.provisionOwner("27");
        String token = inviteAndExtractToken(owner.token(), "enum@shop.in", "SALES_EXEC");
        mvc.perform(post("/api/v1/auth/invitations/" + token + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content(ACCEPT))
            .andExpect(status().isCreated());

        String consumed = mvc.perform(post("/api/v1/auth/invitations/" + token + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content(ACCEPT))
            .andExpect(status().isNotFound())
            .andReturn().getResponse().getContentAsString();

        String unknown = mvc.perform(post("/api/v1/auth/invitations/nonexistent-token/accept")
                .contentType(MediaType.APPLICATION_JSON).content(ACCEPT))
            .andExpect(status().isNotFound())
            .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertEquals(unknown, consumed,
            "a consumed token must be indistinguishable from one that never existed");
    }

    @Test
    void aShortPasswordIs400() throws Exception {
        var owner = tokens.provisionOwner("27");
        String token = inviteAndExtractToken(owner.token(), "short@shop.in", "SALES_EXEC");
        mvc.perform(post("/api/v1/auth/invitations/" + token + "/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"short\"}"))
            .andExpect(status().isBadRequest());
    }

    /**
     * The route's rate-limit protection comes ENTIRELY from living under /api/v1/auth/**:
     * RateLimitProperties.policyFor leaves an unmatched path UNLIMITED, so moving this
     * endpoint elsewhere would silently uncap it. The limiter itself is disabled for the
     * suite (see IntegrationTest), so assert the policy resolution rather than the 429.
     */
    @Test
    void theAcceptRouteResolvesToTheAuthRateLimitPolicy() {
        var policy = rateLimits.policyFor("/api/v1/auth/invitations/some-token/accept");
        org.junit.jupiter.api.Assertions.assertTrue(policy.isPresent(),
            "the accept route must match a rate-limit policy — an unmatched path is unlimited");
        assertEquals("auth", policy.get().name());
    }

    @Test
    void acceptingAnOwnerInvitationYieldsAnOwner() throws Exception {
        var owner = tokens.provisionOwner("27");
        String token = inviteAndExtractToken(owner.token(), "co@shop.in", "OWNER");
        mvc.perform(post("/api/v1/auth/invitations/" + token + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content(ACCEPT))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.role").value("OWNER"));
    }

    // The accepted user must be a real member of the tenant, not a phantom: they can
    // exercise a tenant-scoped read that RLS governs.
    @Test
    void theAcceptedUserCanReadTenantScopedData() throws Exception {
        var owner = tokens.provisionOwner("27");
        String token = inviteAndExtractToken(owner.token(), "reads@shop.in", "SALES_EXEC");
        String body = mvc.perform(post("/api/v1/auth/invitations/" + token + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content(ACCEPT))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String access = JsonPath.read(body, "$.accessToken");

        mvc.perform(get("/api/v1/customers").header("Authorization", "Bearer " + access))
            .andExpect(status().isOk());
    }
}
