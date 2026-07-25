package com.easycrm.crm.web;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.crm.CustomerSource;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class CustomerControllerTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;
    @Autowired CustomerRepository customers;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void createWithGstinDerivesStateCode() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String create = """
            {"businessName":"Acme","gstin":"27AAPFU0939F1ZV","source":"MANUAL"}""";
        mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(create))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.stateCode").value("27"));
    }

    @Test
    void badChecksumReturns422() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String create = """
            {"businessName":"Acme","gstin":"27AAPFU0939F1ZZ","source":"MANUAL"}""";
        mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(create))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.fields.gstin").exists());
    }

    @Test
    void checksumValidGstinWithInvalidStatePrefixReturns422() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        // 15-char GSTIN: checksum-valid (mod-36), but state prefix "88" is not a valid GST state code.
        String create = """
            {"businessName":"Acme","gstin":"88AAPFU0939F1ZN","source":"MANUAL"}""";
        mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(create))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.fields.stateCode").exists());
    }

    @Test
    void missingStateCodeWithoutGstinReturns422() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String create = """
            {"businessName":"Walk-in","source":"PHONE"}""";
        mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(create))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.fields.stateCode").exists());
    }

    @Test
    void crossTenantGetReturns404() throws Exception {
        UUID tenantA = UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(tenantA, UUID.randomUUID(), "OWNER"));
        Customer saved = customers.saveAndFlush(new Customer("Acme A", null, "27",
                                    null, null, 0, null, null, CustomerSource.MANUAL));
        TenantContext.clear();

        String otherTenantAuth = "Bearer " + tokens.owner(UUID.randomUUID());
        mvc.perform(get("/api/v1/customers/" + saved.getId()).header("Authorization", otherTenantAuth))
            .andExpect(status().isNotFound());
    }
}
