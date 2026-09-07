package com.easycrm.sales.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

/**
 * F11: a SENT version must render identically no matter what happens to the customer
 * afterwards. Challenge 28 pinned the renderer deterministic; this pins its inputs.
 */
@SpringBootTest
@AutoConfigureMockMvc
class QuotationBuyerSnapshotTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestTokens tokens;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private String createCustomer(String auth, String state) throws Exception {
        String body = """
            {"businessName":"Bharat Industries","gstin":"27AAAAA0000A1Z2","stateCode":"%s",
             "billingAddress":"12 MG Road, Pune","source":"MANUAL"}""".formatted(state);
        return JsonPath.read(
                mvc.perform(post("/api/v1/customers")
                                .header("Authorization", auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
    }

    /** A full-body PUT: CustomerRequest has no PATCH form, so unsent fields would be nulled. */
    private void updateCustomer(String auth, String customerId, String name, String gstin, String address, String state)
            throws Exception {
        String body = """
            {"businessName":"%s","gstin":"%s","stateCode":"%s",
             "billingAddress":"%s","source":"MANUAL"}""".formatted(name, gstin, state, address);
        mvc.perform(put("/api/v1/customers/" + customerId)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private String draftFor(String auth, String customerId) throws Exception {
        String prod = """
            {"sku":"SKU-%s","name":"Ball Bearing 6203","hsnCode":"84821011",
             "uom":"PCS","gstRate":"18","baseRate":"100.00"}""".formatted(UUID.randomUUID().toString().substring(0, 8));
        String pId = JsonPath.read(
                mvc.perform(post("/api/v1/products")
                                .header("Authorization", auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(prod))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
        String body = """
            {"customerId":"%s","items":[{"productId":"%s","qty":"10"}]}""".formatted(customerId, pId);
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

    private byte[] pdfOf(String auth, String quotationId) throws Exception {
        return mvc.perform(get("/api/v1/quotations/" + quotationId + "/pdf").header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
    }

    @Test
    void sentQuotationRendersIdenticallyAfterTheCustomerIsEdited() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String cId = createCustomer(auth, "27");
        String qId = draftFor(auth, cId);
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth))
                .andExpect(status().isOk());

        byte[] before = pdfOf(auth, qId);

        // An ordinary, correct edit. The state code is deliberately unchanged: this test is
        // about buyer identity, and moving the state is Task 5's separate concern.
        updateCustomer(auth, cId, "Bharat Industries Pvt Ltd", "27BBBBB1111B1ZN", "99 FC Road, Pune", "27");

        assertArrayEquals(before, pdfOf(auth, qId), "a SENT version must not re-render after a customer edit");
    }
}
