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
class QuotationReviseTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    private String createAndSend(String auth) throws Exception {
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
            {"customerId":"%s","items":[{"productId":"%s","qty":"2"}]}""".formatted(cId, pId);
        String id = JsonPath.read(mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/v1/quotations/" + id + "/send").header("Authorization", auth))
            .andExpect(status().isOk());
        return id;
    }

    @Test
    void reviseSpawnsDraftV2CopyingItemsKeepingQuoteNo() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String id = createAndSend(auth);
        String sentBody = mvc.perform(get("/api/v1/quotations/" + id).header("Authorization", auth))
            .andReturn().getResponse().getContentAsString();
        String quoteNo = JsonPath.read(sentBody, "$.quoteNo");

        mvc.perform(post("/api/v1/quotations/" + id + "/revise").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.quoteNo").value(quoteNo)) // number retained
            .andExpect(jsonPath("$.currentVersion.versionNo").value(2))
            .andExpect(jsonPath("$.currentVersion.status").value("DRAFT"))
            .andExpect(jsonPath("$.currentVersion.items[0].qty").value("2.000")) // copied
            .andExpect(jsonPath("$.currentVersion.subTotal").value("200.00"));

        // v1 is preserved and still SENT.
        mvc.perform(get("/api/v1/quotations/" + id + "/versions/1").header("Authorization", auth))
            .andExpect(jsonPath("$.status").value("SENT"));
    }

    @Test
    void reviseThenResendKeepsSameQuoteNo() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String id = createAndSend(auth);
        String sentBody = mvc.perform(get("/api/v1/quotations/" + id).header("Authorization", auth))
            .andReturn().getResponse().getContentAsString();
        String quoteNo = JsonPath.read(sentBody, "$.quoteNo");

        mvc.perform(post("/api/v1/quotations/" + id + "/revise").header("Authorization", auth))
            .andExpect(status().isOk());

        mvc.perform(post("/api/v1/quotations/" + id + "/send").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SENT"))
            .andExpect(jsonPath("$.quoteNo").value(quoteNo)) // unchanged across resend
            .andExpect(jsonPath("$.currentVersion.versionNo").value(2));
    }

    @Test
    void revisingADraftReturns422() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String id = createAndSend(auth);
        mvc.perform(post("/api/v1/quotations/" + id + "/revise").header("Authorization", auth))
            .andExpect(status().isOk()); // now DRAFT (v2)
        mvc.perform(post("/api/v1/quotations/" + id + "/revise").header("Authorization", auth))
            .andExpect(status().isUnprocessableEntity()); // can't revise a draft
    }
}
