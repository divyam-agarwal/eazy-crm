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

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class QuotationSendTest extends IntegrationTest {
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
    void sendAssignsGaplessNumberAndFreezes() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String id1 = createDraft(auth);
        String id2 = createDraft(auth);

        mvc.perform(post("/api/v1/quotations/" + id1 + "/send").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SENT"))
            .andExpect(jsonPath("$.quoteNo").value(matchesPattern("QT/\\d{2}-\\d{2}/0001")))
            .andExpect(jsonPath("$.currentVersion.status").value("SENT"))
            .andExpect(jsonPath("$.currentVersion.sentAt").exists());

        mvc.perform(post("/api/v1/quotations/" + id2 + "/send").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.quoteNo").value(matchesPattern("QT/\\d{2}-\\d{2}/0002")));
    }

    @Test
    void sendingAlreadySentReturns422() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String id = createDraft(auth);
        mvc.perform(post("/api/v1/quotations/" + id + "/send").header("Authorization", auth))
            .andExpect(status().isOk());
        mvc.perform(post("/api/v1/quotations/" + id + "/send").header("Authorization", auth))
            .andExpect(status().isUnprocessableEntity());
    }
}
