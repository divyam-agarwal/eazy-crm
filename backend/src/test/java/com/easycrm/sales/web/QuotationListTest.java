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
class QuotationListTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    private String createQuotation(String auth) throws Exception {
        String cust = """
            {"businessName":"Acme","stateCode":"27","source":"MANUAL"}""";
        String cBody = mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(cust))
            .andReturn().getResponse().getContentAsString();
        String prod = """
            {"sku":"SKU-%s","name":"W","hsnCode":"84818090","uom":"PCS","gstRate":"18","baseRate":"100.00"}"""
            .formatted(UUID.randomUUID().toString().substring(0, 8));
        String pBody = mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(prod))
            .andReturn().getResponse().getContentAsString();
        String body = """
            {"customerId":"%s","items":[{"productId":"%s","qty":"1"}]}"""
            .formatted((String) JsonPath.read(cBody, "$.id"), (String) JsonPath.read(pBody, "$.id"));
        return mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse().getContentAsString();
    }

    private String customer(String auth, String businessName) throws Exception {
        String cust = """
            {"businessName":"%s","stateCode":"27","source":"MANUAL"}""".formatted(businessName);
        return JsonPath.read(mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(cust))
            .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String quotation(String auth, String customerId) throws Exception {
        String prod = """
            {"sku":"SKU-%s","name":"W","hsnCode":"84818090","uom":"PCS","gstRate":"18","baseRate":"100.00"}"""
            .formatted(UUID.randomUUID().toString().substring(0, 8));
        String pBody = mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(prod))
            .andReturn().getResponse().getContentAsString();
        String body = """
            {"customerId":"%s","items":[{"productId":"%s","qty":"1"}]}"""
            .formatted(customerId, (String) JsonPath.read(pBody, "$.id"));
        return JsonPath.read(mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void statusAndCustomerFiltersApplyTogether() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String wanted = customer(auth, "Wanted Buyer");
        String other = customer(auth, "Other Buyer");

        String sentForWanted = quotation(auth, wanted);
        mvc.perform(post("/api/v1/quotations/" + sentForWanted + "/send").header("Authorization", auth));
        String sentForOther = quotation(auth, other);
        mvc.perform(post("/api/v1/quotations/" + sentForOther + "/send").header("Authorization", auth));
        quotation(auth, wanted);   // stays DRAFT

        // Both filters must AND. Before the fix this returned both SENT quotations,
        // because customerId was silently dropped when status was present.
        mvc.perform(get("/api/v1/quotations?status=SENT&customerId=" + wanted)
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].id").value(sentForWanted));
    }

    @Test
    void listsQuotationsForTenant() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        createQuotation(auth);
        mvc.perform(get("/api/v1/quotations").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray())
            .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getsVersionByNumber() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String qBody = createQuotation(auth);
        String id = JsonPath.read(qBody, "$.id");
        mvc.perform(get("/api/v1/quotations/" + id + "/versions/1").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.versionNo").value(1))
            .andExpect(jsonPath("$.items[0].qty").value("1.000"));
        mvc.perform(get("/api/v1/quotations/" + id + "/versions").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].versionNo").value(1));
    }
}
