package com.easycrm.sales.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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

    /** Same approach as QuotationPdfEndpointTest: assert on text, not on raw PDF bytes. */
    private String textOf(byte[] pdf) throws Exception {
        try (PDDocument doc = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void sendFreezesTheBuyerAndDraftHasNone() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String cId = createCustomer(auth, "27");
        String qId = draftFor(auth, cId);

        // A DRAFT has nothing frozen: the PDF route is the only reader, and it refuses a
        // draft outright, so the observable proof is that rendering is still rejected.
        mvc.perform(get("/api/v1/quotations/" + qId + "/pdf").header("Authorization", auth))
                .andExpect(status().isUnprocessableEntity());

        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth))
                .andExpect(status().isOk());

        // The frozen values survive the customer being renamed out from under them.
        updateCustomer(auth, cId, "Renamed Entirely", "27CCCCC2222C1Z8", "Somewhere Else", "27");
        String text = textOf(pdfOf(auth, qId));
        org.junit.jupiter.api.Assertions.assertTrue(
                text.contains("Bharat Industries"), "the PDF must show the buyer frozen at send, not the live one");
        org.junit.jupiter.api.Assertions.assertFalse(
                text.contains("Renamed Entirely"), "the PDF must not show the edited customer");
    }

    @Test
    void sendRejectsAQuotationWhoseCustomerChangedState() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String cId = createCustomer(auth, "27");
        String qId = draftFor(auth, cId);

        // The customer moves to Karnataka. The draft's per-line tax was computed as
        // intra-state Maharashtra and is now wrong — sending it would print a Karnataka
        // address beside a CGST/SGST breakup that no longer applies.
        updateCustomer(auth, cId, "Bharat Industries", "29DDDDD3333D1ZP", "5 Residency Road, Bengaluru", "29");

        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.placeOfSupply").exists());

        // Nothing was frozen and nothing was sent: the quotation is still a usable DRAFT.
        mvc.perform(get("/api/v1/quotations/" + qId).header("Authorization", auth))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }
}
