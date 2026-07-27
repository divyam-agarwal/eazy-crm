package com.easycrm.sales.web;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class EnquiryCreateTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void createsWithNormalizedPhoneAndMoneyString() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        mvc.perform(post("/api/v1/enquiries").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"contactName":"Ravi","contactPhone":"+91 98765 43210",
                     "source":"INDIAMART","requirementText":"10 bags",
                     "expectedValue":"50000.00"}"""))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.stage").value("NEW"))
            .andExpect(jsonPath("$.normalizedPhone").value("9876543210"))
            .andExpect(jsonPath("$.expectedValue").value("50000.00")); // JSON string, not number
    }

    @Test
    void rejectsInvalidPhoneWith422() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        mvc.perform(post("/api/v1/enquiries").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"contactName":"Ravi","contactPhone":"12345","source":"PHONE"}"""))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.fields.contactPhone").exists());
    }

    @Test
    void secondActiveEnquiryForSamePhoneConflicts() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String body = """
            {"contactName":"Ravi","contactPhone":"098765 43210","source":"WHATSAPP"}""";
        mvc.perform(post("/api/v1/enquiries").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/enquiries").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict());
    }
}
