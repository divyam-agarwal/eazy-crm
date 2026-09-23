package com.easycrm.crm.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.crm.CustomerSource;
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
class CustomerControllerTest extends IntegrationTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    TestTokens tokens;

    @Autowired
    CustomerRepository customers;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void createWithGstinDerivesStateCode() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String create = """
            {"businessName":"Acme","gstin":"27AAPFU0939F1ZV","source":"MANUAL"}""";
        mvc.perform(post("/api/v1/customers")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(create))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stateCode").value("27"));
    }

    @Test
    void badChecksumReturns422() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String create = """
            {"businessName":"Acme","gstin":"27AAPFU0939F1ZZ","source":"MANUAL"}""";
        mvc.perform(post("/api/v1/customers")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(create))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.gstin").exists());
    }

    @Test
    void checksumValidGstinWithInvalidStatePrefixReturns422() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        // 15-char GSTIN: checksum-valid (mod-36), but state prefix "88" is not a valid GST state code.
        String create = """
            {"businessName":"Acme","gstin":"88AAPFU0939F1ZN","source":"MANUAL"}""";
        mvc.perform(post("/api/v1/customers")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(create))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.stateCode").exists());
    }

    @Test
    void missingStateCodeWithoutGstinReturns422() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String create = """
            {"businessName":"Walk-in","source":"PHONE"}""";
        mvc.perform(post("/api/v1/customers")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(create))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.stateCode").exists());
    }

    @Test
    void crossTenantGetReturns404() throws Exception {
        UUID tenantA = UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(tenantA, UUID.randomUUID(), "OWNER"));
        Customer saved = customers.saveAndFlush(
                new Customer("Acme A", null, "27", null, null, 0, null, null, CustomerSource.MANUAL));
        TenantContext.clear();

        String otherTenantAuth = "Bearer " + tokens.owner(UUID.randomUUID());
        mvc.perform(get("/api/v1/customers/" + saved.getId()).header("Authorization", otherTenantAuth))
                .andExpect(status().isNotFound());
    }

    @Test
    void searchMatchesBusinessNameSubstringCaseInsensitively() throws Exception {
        UUID tenant = UUID.randomUUID();
        String auth = "Bearer " + tokens.owner(tenant);
        createCustomer(auth, "Shri Ram Traders", "27AAPFU0939F1ZV");
        createCustomer(auth, "Gupta Hardware", null);

        // "ram" is in the MIDDLE of the name: a prefix-only implementation passes every other
        // assertion in this test and fails only this one, which is why the needle is not "shri".
        mvc.perform(get("/api/v1/customers?q=ram").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].businessName").value("Shri Ram Traders"));
    }

    @Test
    void searchMatchesGstin() throws Exception {
        UUID tenant = UUID.randomUUID();
        String auth = "Bearer " + tokens.owner(tenant);
        createCustomer(auth, "Shri Ram Traders", "27AAPFU0939F1ZV");

        mvc.perform(get("/api/v1/customers?q=AAPFU").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void searchReturnsEmptyRatherThanEverythingWhenNothingMatches() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        createCustomer(auth, "Gupta Hardware", null);

        // A predicate accidentally dropped from the conjunction shows all rows instead of none.
        mvc.perform(get("/api/v1/customers?q=zzzznomatch").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void blankSearchIsTreatedAsAbsent() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        createCustomer(auth, "Gupta Hardware", null);

        mvc.perform(get("/api/v1/customers").param("q", "  ").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void searchComposesWithTheActiveFilter() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String id = createCustomer(auth, "Shri Ram Traders", null);
        mvc.perform(post("/api/v1/customers/" + id + "/deactivate").header("Authorization", auth))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/customers?q=ram&active=true").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/customers?q=ram&active=false").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void rejectsAnUnknownSortFieldWith422() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());

        mvc.perform(get("/api/v1/customers?sort=creditDays,asc").header("Authorization", auth))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fieldCodes.sort").value("SORT_INVALID"));
    }

    @Test
    void acceptsAnAllowedSortField() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());

        mvc.perform(get("/api/v1/customers?sort=businessName,asc").header("Authorization", auth))
                .andExpect(status().isOk());
    }

    /** Task 2 caps page size via spring.data.web.pageable.max-page-size: 100 in application.yml --
     *  YAML nesting alone enforces it, and a typo there would silently no-op the cap. */
    @Test
    void pageSizeIsCappedAtOneHundred() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());

        mvc.perform(get("/api/v1/customers?size=1000").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    /** Returns the created customer's id. */
    private String createCustomer(String auth, String businessName, String gstin) throws Exception {
        String gstinJson = gstin == null ? "null" : "\"" + gstin + "\"";
        String body = """
                {"businessName":"%s","gstin":%s,"stateCode":%s,"source":"MANUAL"}""".formatted(businessName, gstinJson, gstin == null ? "\"27\"" : "null");
        String response = mvc.perform(post("/api/v1/customers")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.id");
    }
}
