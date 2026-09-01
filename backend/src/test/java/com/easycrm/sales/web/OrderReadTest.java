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
class OrderReadTest extends IntegrationTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    TestTokens tokens;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private String createAcceptedOrderId(String auth) throws Exception {
        String cId = JsonPath.read(
                mvc.perform(post("/api/v1/customers")
                                .header("Authorization", auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                    {"businessName":"Acme","stateCode":"27","source":"MANUAL"}"""))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
        String pId = JsonPath.read(
                mvc.perform(post("/api/v1/products")
                                .header("Authorization", auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                    {"sku":"SKU-%s","name":"W","hsnCode":"84818090","uom":"PCS","gstRate":"18","baseRate":"100.00"}""".formatted(
                                                UUID.randomUUID().toString().substring(0, 8))))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
        String qId = JsonPath.read(
                mvc.perform(post("/api/v1/quotations")
                                .header("Authorization", auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"customerId\":\"%s\",\"items\":[{\"productId\":\"%s\",\"qty\":\"1\"}]}"
                                        .formatted(cId, pId)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth));
        return JsonPath.read(
                mvc.perform(post("/api/v1/quotations/" + qId + "/accept")
                                .header("Authorization", auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
    }

    @Test
    void getByIdAndListReturnTheOrder() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String orderId = createAcceptedOrderId(auth);

        mvc.perform(get("/api/v1/orders/" + orderId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        mvc.perform(get("/api/v1/orders").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(orderId));
    }

    @Test
    void crossTenantGetReturns404() throws Exception {
        String authA = "Bearer " + tokens.provisionOwner("27").token();
        String orderId = createAcceptedOrderId(authA);

        String authB = "Bearer " + tokens.provisionOwner("29").token();
        mvc.perform(get("/api/v1/orders/" + orderId).header("Authorization", authB))
                .andExpect(status().isNotFound());
    }
}
