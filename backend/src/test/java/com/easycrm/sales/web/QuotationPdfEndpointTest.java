package com.easycrm.sales.web;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import com.jayway.jsonpath.JsonPath;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class QuotationPdfEndpointTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    /** Creates a DRAFT quotation for a customer in `customerState` and returns its id. */
    private String draftQuotation(String auth, String customerState) throws Exception {
        String cId = JsonPath.read(mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"businessName":"Bharat Industries","stateCode":"%s","source":"MANUAL"}"""
                    .formatted(customerState)))
            .andReturn().getResponse().getContentAsString(), "$.id");
        String pId = JsonPath.read(mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"sku":"SKU-%s","name":"Ball Bearing 6203","hsnCode":"84821011",
                     "uom":"PCS","gstRate":"18","baseRate":"100.00"}"""
                    .formatted(UUID.randomUUID().toString().substring(0, 8))))
            .andReturn().getResponse().getContentAsString(), "$.id");
        return JsonPath.read(mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(
                    "{\"customerId\":\"%s\",\"items\":[{\"productId\":\"%s\",\"qty\":\"10\"}]}"
                        .formatted(cId, pId)))
            .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static String textOf(byte[] pdf) throws Exception {
        try (PDDocument doc = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void rendersASentQuotationWithBothPartiesAndTheHsnCode() throws Exception {
        var owner = tokens.provisionOwner("27");
        String auth = "Bearer " + owner.token();
        mvc.perform(patch("/api/v1/tenant").header("Authorization", auth)
            .contentType(MediaType.APPLICATION_JSON).content("""
                {"address":"12 MG Road, Pune 411001","phone":"+919876543210","email":null}"""));
        String qId = draftQuotation(auth, "27");
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth));

        byte[] pdf = mvc.perform(get("/api/v1/quotations/" + qId + "/pdf").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/pdf"))
            .andReturn().getResponse().getContentAsByteArray();

        String text = textOf(pdf);
        assertTrue(text.contains("Bharat Industries"), text);
        assertTrue(text.contains("12 MG Road, Pune 411001"), text);
        assertTrue(text.contains("84821011"), text);
    }

    @Test
    void intraStateShowsCgstSgstAndInterStateShowsIgst() throws Exception {
        String authIntra = "Bearer " + tokens.provisionOwner("27").token();
        String intra = draftQuotation(authIntra, "27");        // seller 27, buyer 27
        mvc.perform(post("/api/v1/quotations/" + intra + "/send").header("Authorization", authIntra));
        String intraText = textOf(mvc.perform(get("/api/v1/quotations/" + intra + "/pdf")
            .header("Authorization", authIntra)).andReturn().getResponse().getContentAsByteArray());
        assertTrue(intraText.contains("CGST") && !intraText.contains("IGST"), intraText);

        String authInter = "Bearer " + tokens.provisionOwner("27").token();
        String inter = draftQuotation(authInter, "29");        // seller 27, buyer 29
        mvc.perform(post("/api/v1/quotations/" + inter + "/send").header("Authorization", authInter));
        String interText = textOf(mvc.perform(get("/api/v1/quotations/" + inter + "/pdf")
            .header("Authorization", authInter)).andReturn().getResponse().getContentAsByteArray());
        assertTrue(interText.contains("IGST") && !interText.contains("CGST"), interText);
    }

    @Test
    void aDraftHasNoDocumentToRender() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String qId = draftQuotation(auth, "27");   // never sent

        mvc.perform(get("/api/v1/quotations/" + qId + "/pdf").header("Authorization", auth))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void anEarlierVersionStillRendersAfterARevision() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String qId = draftQuotation(auth, "27");
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth));
        mvc.perform(post("/api/v1/quotations/" + qId + "/revise").header("Authorization", auth));
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth));

        // v2 is the default; v1 is still reachable and still says "v1".
        String v2 = textOf(mvc.perform(get("/api/v1/quotations/" + qId + "/pdf")
            .header("Authorization", auth)).andReturn().getResponse().getContentAsByteArray());
        assertTrue(v2.contains("(v2)"), v2);

        String v1 = textOf(mvc.perform(get("/api/v1/quotations/" + qId + "/pdf?version=1")
                .header("Authorization", auth))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertTrue(v1.contains("(v1)"), v1);
    }

    @Test
    void crossTenantReturns404() throws Exception {
        String authA = "Bearer " + tokens.provisionOwner("27").token();
        String qId = draftQuotation(authA, "27");
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", authA));

        String authB = "Bearer " + tokens.provisionOwner("29").token();
        mvc.perform(get("/api/v1/quotations/" + qId + "/pdf").header("Authorization", authB))
            .andExpect(status().isNotFound());
    }
}
