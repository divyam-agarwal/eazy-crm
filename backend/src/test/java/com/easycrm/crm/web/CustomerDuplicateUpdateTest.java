package com.easycrm.crm.web;

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
class CustomerDuplicateUpdateTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void updatingGstinToAnExistingOneReturns409() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());

        // Customer A with GSTIN #1
        mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"businessName\":\"A\",\"gstin\":\"27AAPFU0939F1ZV\",\"source\":\"MANUAL\"}"))
            .andExpect(status().isCreated());

        // Customer B with GSTIN #2
        String bodyB = mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"businessName\":\"B\",\"gstin\":\"29AAACW1234H1ZG\",\"source\":\"MANUAL\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String idB = JsonPath.read(bodyB, "$.id");

        // Update B's GSTIN to A's -> DB unique violation -> must be 409, not 500
        mvc.perform(put("/api/v1/customers/" + idB).header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"businessName\":\"B\",\"gstin\":\"27AAPFU0939F1ZV\",\"source\":\"MANUAL\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }
}
