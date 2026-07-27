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
class QuotationAcceptTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    /** Builds a customer + product + quotation, sends it, returns the quotation id. */
    private String createSent(String auth) throws Exception {
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
        String qId = JsonPath.read(mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth))
            .andExpect(status().isOk());
        return qId;
    }

    @Test
    void acceptingSentQuotationCreatesConfirmedOrder() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String qId = createSent(auth);

        // Read the sent quotation's grand total to compare against the order snapshot.
        String qJson = mvc.perform(get("/api/v1/quotations/" + qId).header("Authorization", auth))
            .andReturn().getResponse().getContentAsString();
        String grandTotal = JsonPath.read(qJson, "$.currentVersion.grandTotal");

        mvc.perform(post("/api/v1/quotations/" + qId + "/accept").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"poReference\":\"PO-99\",\"poDate\":\"2026-07-27\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.orderNo").value(matchesPattern("ORD/\\d{2}-\\d{2}/0001")))
            .andExpect(jsonPath("$.quotationId").value(qId))
            .andExpect(jsonPath("$.poReference").value("PO-99"))
            .andExpect(jsonPath("$.poDate").value("2026-07-27"))
            .andExpect(jsonPath("$.grandTotal").value(grandTotal));

        // The quotation is now ACCEPTED.
        mvc.perform(get("/api/v1/quotations/" + qId).header("Authorization", auth))
            .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void emptyBodyIsAccepted() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String qId = createSent(auth);
        mvc.perform(post("/api/v1/quotations/" + qId + "/accept").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.poReference").doesNotExist());
    }
}
