package com.easycrm.sales.web;

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
class QuotationTransitionTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    private String createDraft(String auth) throws Exception {
        String cust = """
            {"businessName":"Acme","stateCode":"27","source":"MANUAL"}""";
        String cId = JsonPath.read(mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(cust))
            .andReturn().getResponse().getContentAsString(), "$.id");
        String prod = """
            {"sku":"SKU-%s","name":"W","hsnCode":"84818090","uom":"PCS","gstRate":"18","baseRate":"100.00"}"""
            .formatted(UUID.randomUUID().toString().substring(0, 8));
        String pId = JsonPath.read(mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(prod))
            .andReturn().getResponse().getContentAsString(), "$.id");
        String body = """
            {"customerId":"%s","items":[{"productId":"%s","qty":"1"}]}""".formatted(cId, pId);
        return JsonPath.read(mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void rejectMovesSentToRejected() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String id = createDraft(auth);
        mvc.perform(post("/api/v1/quotations/" + id + "/send").header("Authorization", auth));
        mvc.perform(post("/api/v1/quotations/" + id + "/reject").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void expireOnDraftReturns422() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String id = createDraft(auth);
        mvc.perform(post("/api/v1/quotations/" + id + "/expire").header("Authorization", auth))
            .andExpect(status().isUnprocessableEntity());
    }
}
