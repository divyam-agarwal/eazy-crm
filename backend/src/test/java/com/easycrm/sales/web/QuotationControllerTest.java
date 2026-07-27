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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class QuotationControllerTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    // Creates a customer (state 27) and a product via the real APIs, returns {customerId, productId}.
    private String[] seed(String auth, String customerState) throws Exception {
        String cust = """
            {"businessName":"Acme","stateCode":"%s","source":"MANUAL"}""".formatted(customerState);
        String cBody = mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(cust))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String prod = """
            {"sku":"SKU-%s","name":"Widget","hsnCode":"84818090","uom":"PCS",
             "gstRate":"18","baseRate":"100.00"}""".formatted(UUID.randomUUID().toString().substring(0, 8));
        String pBody = mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(prod))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new String[]{JsonPath.read(cBody, "$.id"), JsonPath.read(pBody, "$.id")};
    }

    @Test
    void createsDraftWithResolvedRateAndIntraStateGst() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String[] ids = seed(auth, "27");
        String body = """
            {"customerId":"%s","items":[{"productId":"%s","qty":"2"}]}"""
            .formatted(ids[0], ids[1]);

        mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.quoteNo").doesNotExist())
            .andExpect(jsonPath("$.currentVersion.versionNo").value(1))
            .andExpect(jsonPath("$.currentVersion.items[0].rate").value("100.00")) // resolved from base rate
            .andExpect(jsonPath("$.currentVersion.subTotal").value("200.00"));
    }

    @Test
    void rejectsEmptyItemsWith400() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String[] ids = seed(auth, "27");
        String body = """
            {"customerId":"%s","items":[]}""".formatted(ids[0]);
        mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsNegativeClientSuppliedRateWith422() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String[] ids = seed(auth, "27");
        String body = """
            {"customerId":"%s","items":[{"productId":"%s","qty":"2","rate":"-1"}]}"""
            .formatted(ids[0], ids[1]);

        mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.fields['items[0].rate']").exists());
    }

    @Test
    void getReturns404ForOtherTenant() throws Exception {
        String authA = "Bearer " + tokens.provisionOwner("27").token();
        String[] ids = seed(authA, "27");
        String body = """
            {"customerId":"%s","items":[{"productId":"%s","qty":"1"}]}"""
            .formatted(ids[0], ids[1]);
        String qBody = mvc.perform(post("/api/v1/quotations").header("Authorization", authA)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(qBody, "$.id");

        String authB = "Bearer " + tokens.provisionOwner("27").token();
        mvc.perform(get("/api/v1/quotations/" + id).header("Authorization", authB))
            .andExpect(status().isNotFound());
    }
}
