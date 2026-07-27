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
class QuotationConversionTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    // Mirrors QuotationControllerTest.seed: creates a customer + product via the real APIs.
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

    // Creates an active enquiry, returns its id.
    private String seedEnquiry(String auth, String phone) throws Exception {
        String body = """
            {"contactName":"Ravi","contactPhone":"%s","source":"INDIAMART"}""".formatted(phone);
        String eBody = mvc.perform(post("/api/v1/enquiries").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(eBody, "$.id");
    }

    @Test
    void raisingQuoteFromEnquiryConvertsItAndStampsTheQuotation() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String[] ids = seed(auth, "27");
        String enquiryId = seedEnquiry(auth, "9876543210");

        String body = """
            {"customerId":"%s","enquiryId":"%s","items":[{"productId":"%s","qty":"2"}]}"""
            .formatted(ids[0], enquiryId, ids[1]);

        mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.enquiryId").value(enquiryId));

        // The lead is now CONVERTED (terminal).
        mvc.perform(get("/api/v1/enquiries/" + enquiryId).header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stage").value("CONVERTED"));
    }

    @Test
    void unknownEnquiryIdReturns404() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String[] ids = seed(auth, "27");
        String body = """
            {"customerId":"%s","enquiryId":"%s","items":[{"productId":"%s","qty":"1"}]}"""
            .formatted(ids[0], UUID.randomUUID(), ids[1]);

        mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isNotFound());
    }

    @Test
    void crossTenantEnquiryIdReturns404AndLeavesItUntouched() throws Exception {
        String authA = "Bearer " + tokens.provisionOwner("27").token();
        String enquiryId = seedEnquiry(authA, "9876543210");

        String authB = "Bearer " + tokens.provisionOwner("27").token();
        String[] idsB = seed(authB, "27");
        String body = """
            {"customerId":"%s","enquiryId":"%s","items":[{"productId":"%s","qty":"1"}]}"""
            .formatted(idsB[0], enquiryId, idsB[1]);

        // Tenant B cannot see tenant A's enquiry -> 404.
        mvc.perform(post("/api/v1/quotations").header("Authorization", authB)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isNotFound());

        // Tenant A's enquiry is untouched.
        mvc.perform(get("/api/v1/enquiries/" + enquiryId).header("Authorization", authA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stage").value("NEW"));
    }

    @Test
    void quotingAnAlreadyTerminalEnquiryReturns422() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String[] ids = seed(auth, "27");
        String enquiryId = seedEnquiry(auth, "9876543210");

        // Take the enquiry terminal via /lose.
        mvc.perform(post("/api/v1/enquiries/" + enquiryId + "/lose").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"lostReason\":\"bought elsewhere\"}"))
            .andExpect(status().isOk());

        String body = """
            {"customerId":"%s","enquiryId":"%s","items":[{"productId":"%s","qty":"1"}]}"""
            .formatted(ids[0], enquiryId, ids[1]);
        mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void aSecondQuoteFromTheSameEnquiryReturns422() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String[] ids = seed(auth, "27");
        String enquiryId = seedEnquiry(auth, "9876543210");
        String body = """
            {"customerId":"%s","enquiryId":"%s","items":[{"productId":"%s","qty":"1"}]}"""
            .formatted(ids[0], enquiryId, ids[1]);

        // First quote converts the enquiry.
        mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());

        // Second quote from the now-CONVERTED (terminal) enquiry -> 422.
        mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void reEnquiringOnTheSamePhoneAfterConversionSucceeds() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String[] ids = seed(auth, "27");
        String enquiryId = seedEnquiry(auth, "9876543210");
        String body = """
            {"customerId":"%s","enquiryId":"%s","items":[{"productId":"%s","qty":"1"}]}"""
            .formatted(ids[0], enquiryId, ids[1]);

        // Convert the lead.
        mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());

        // While the first was active a duplicate would 409; now it is CONVERTED it leaves the
        // partial unique index, so a fresh enquiry on the same phone is allowed.
        mvc.perform(post("/api/v1/enquiries").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"contactName":"Ravi","contactPhone":"9876543210","source":"PHONE"}"""))
            .andExpect(status().isCreated());
    }

    @Test
    void failedQuoteBuildRollsBackTheConversion() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String[] ids = seed(auth, "27");
        String enquiryId = seedEnquiry(auth, "9876543210");

        // qty 0 passes bean validation (@NotNull only) but fails in buildItems -> 422,
        // AFTER markConverted() flipped the managed enquiry in the same transaction.
        String body = """
            {"customerId":"%s","enquiryId":"%s","items":[{"productId":"%s","qty":"0"}]}"""
            .formatted(ids[0], enquiryId, ids[1]);
        mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnprocessableEntity());

        // The transaction rolled back, so the enquiry is still active (NEW), not CONVERTED.
        mvc.perform(get("/api/v1/enquiries/" + enquiryId).header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stage").value("NEW"));
    }

    @Test
    void creatingWithoutAnEnquiryIdStillWorks() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String[] ids = seed(auth, "27");
        String body = """
            {"customerId":"%s","items":[{"productId":"%s","qty":"1"}]}"""
            .formatted(ids[0], ids[1]);

        mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.enquiryId").doesNotExist());
    }
}
