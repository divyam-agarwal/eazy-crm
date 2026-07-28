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
class OrderTransitionTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    /** Customer + product + quotation -> send -> accept. Returns the new order's id. */
    private String createOrder(String auth) throws Exception {
        String cId = JsonPath.read(mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"businessName":"Acme","stateCode":"27","source":"MANUAL"}"""))
            .andReturn().getResponse().getContentAsString(), "$.id");
        String pId = JsonPath.read(mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"sku":"SKU-%s","name":"W","hsnCode":"84818090","uom":"PCS","gstRate":"18","baseRate":"100.00"}"""
                    .formatted(UUID.randomUUID().toString().substring(0, 8))))
            .andReturn().getResponse().getContentAsString(), "$.id");
        String qId = JsonPath.read(mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(
                    "{\"customerId\":\"%s\",\"items\":[{\"productId\":\"%s\",\"qty\":\"1\"}]}"
                        .formatted(cId, pId)))
            .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth))
            .andExpect(status().isOk());
        return JsonPath.read(mvc.perform(post("/api/v1/quotations/" + qId + "/accept")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void dispatchThenCloseWalksTheOrderToClosed() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String id = createOrder(auth);

        mvc.perform(post("/api/v1/orders/" + id + "/dispatch").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DISPATCHED"))
            .andExpect(jsonPath("$.cancelReason").doesNotExist());

        mvc.perform(post("/api/v1/orders/" + id + "/close").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CLOSED"));

        // terminal: no further transitions
        mvc.perform(post("/api/v1/orders/" + id + "/close").header("Authorization", auth))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/orders/" + id + "/dispatch").header("Authorization", auth))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void closeBeforeDispatchIsRejected() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String id = createOrder(auth);

        mvc.perform(post("/api/v1/orders/" + id + "/close").header("Authorization", auth))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void cancelStoresTheReasonAndBlocksFurtherTransitions() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String id = createOrder(auth);

        mvc.perform(post("/api/v1/orders/" + id + "/cancel").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"cancelReason":"customer withdrew PO"}"""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.cancelReason").value("customer withdrew PO"));

        mvc.perform(post("/api/v1/orders/" + id + "/dispatch").header("Authorization", auth))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/orders/" + id + "/cancel").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"cancelReason":"again"}"""))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void blankCancelReasonIsRejectedAtTheEdgeWith400() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String id = createOrder(auth);

        // @NotBlank fires before the controller body runs -> 400, not the entity's 422.
        mvc.perform(post("/api/v1/orders/" + id + "/cancel").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"cancelReason":"   "}"""))
            .andExpect(status().isBadRequest());
    }

    @Test
    void crossTenantTransitionReturns404() throws Exception {
        String authA = "Bearer " + tokens.provisionOwner("27").token();
        String id = createOrder(authA);

        String authB = "Bearer " + tokens.provisionOwner("29").token();
        mvc.perform(post("/api/v1/orders/" + id + "/dispatch").header("Authorization", authB))
            .andExpect(status().isNotFound());
    }
}
