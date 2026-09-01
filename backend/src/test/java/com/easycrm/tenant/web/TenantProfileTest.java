package com.easycrm.tenant.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.easycrm.platform.security.JwtService;
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
class TenantProfileTest extends IntegrationTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    TestTokens tokens;

    @Autowired
    JwtService jwt;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void ownerCanSetAndReadBackTheSellerProfile() throws Exception {
        var owner = tokens.provisionOwner("27");
        String auth = "Bearer " + owner.token();

        mvc.perform(patch("/api/v1/tenant")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {"address":"12 MG Road, Pune 411001","phone":"+919876543210",
                     "email":"sales@acme.example"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value("12 MG Road, Pune 411001"))
                .andExpect(jsonPath("$.phone").value("+919876543210"));

        mvc.perform(get("/api/v1/tenant").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("sales@acme.example"))
                .andExpect(jsonPath("$.stateCode").value("27"));
    }

    @Test
    void nonOwnerGets403() throws Exception {
        var owner = tokens.provisionOwner("27");
        String salesExec = "Bearer " + jwt.mint(owner.tenantId(), UUID.randomUUID(), "SALES_EXEC");

        mvc.perform(patch("/api/v1/tenant")
                        .header("Authorization", salesExec)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {"address":"nope","phone":null,"email":null}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    void oneTenantCannotSeeAnothersProfile() throws Exception {
        var a = tokens.provisionOwner("27");
        mvc.perform(patch("/api/v1/tenant")
                        .header("Authorization", "Bearer " + a.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {"address":"A's address","phone":null,"email":null}"""))
                .andExpect(status().isOk());

        var b = tokens.provisionOwner("29");
        mvc.perform(get("/api/v1/tenant").header("Authorization", "Bearer " + b.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").doesNotExist());
    }
}
