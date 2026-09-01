package com.easycrm.sales.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import com.jayway.jsonpath.JsonPath;
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
class QuotationEditTest extends IntegrationTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    TestTokens tokens;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private String[] seedIds(String auth) throws Exception {
        String cust = """
            {"businessName":"Acme","stateCode":"27","source":"MANUAL"}""";
        String cBody = mvc.perform(post("/api/v1/customers")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cust))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String prod = """
            {"sku":"SKU-%s","name":"W","hsnCode":"84818090","uom":"PCS","gstRate":"18","baseRate":"100.00"}""".formatted(UUID.randomUUID().toString().substring(0, 8));
        String pBody = mvc.perform(post("/api/v1/products")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(prod))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new String[] {JsonPath.read(cBody, "$.id"), JsonPath.read(pBody, "$.id")};
    }

    private String createDraft(String auth, String[] ids) throws Exception {
        String body = """
            {"customerId":"%s","items":[{"productId":"%s","qty":"1"}]}""".formatted(ids[0], ids[1]);
        return JsonPath.read(
                mvc.perform(post("/api/v1/quotations")
                                .header("Authorization", auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
    }

    @Test
    void replacesItemsAndRecomputesTotals() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String[] ids = seedIds(auth);
        String id = createDraft(auth, ids);
        String items = """
            {"items":[{"productId":"%s","qty":"5"}]}""".formatted(ids[1]);
        mvc.perform(put("/api/v1/quotations/" + id + "/items")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(items))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion.subTotal").value("500.00"));
    }

    @Test
    void editingItemsOnSentVersionReturns422() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String[] ids = seedIds(auth);
        String id = createDraft(auth, ids);
        mvc.perform(post("/api/v1/quotations/" + id + "/send").header("Authorization", auth))
                .andExpect(status().isOk());
        String items = """
            {"items":[{"productId":"%s","qty":"9"}]}""".formatted(ids[1]);
        mvc.perform(put("/api/v1/quotations/" + id + "/items")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(items))
                .andExpect(status().isUnprocessableEntity());
    }
}
