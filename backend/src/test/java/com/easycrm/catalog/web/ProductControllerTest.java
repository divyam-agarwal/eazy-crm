package com.easycrm.catalog.web;

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
class ProductControllerTest extends IntegrationTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    TestTokens tokens;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void createThenGet() throws Exception {
        UUID tenant = UUID.randomUUID();
        String auth = "Bearer " + tokens.owner(tenant);
        String create = """
            {"sku":"SKU-9","name":"Bolt","hsnCode":"7318","uom":"PCS",
             "gstRate":"18","baseRate":"12.50"}""";

        String body = mvc.perform(post("/api/v1/products")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(create))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("SKU-9"))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String id = JsonPath.read(body, "$.id");
        mvc.perform(get("/api/v1/products/" + id).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Bolt"));
    }

    @Test
    void rejectsDisallowedGstRateWith422() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String create = """
            {"sku":"SKU-BAD","name":"X","hsnCode":"7318","uom":"PCS",
             "gstRate":"7","baseRate":"1.00"}""";
        mvc.perform(post("/api/v1/products")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(create))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.gstRate").exists())
                .andExpect(jsonPath("$.error.fieldCodes.gstRate").value("GST_RATE_INVALID"));
    }

    @Test
    void multiFieldValidationCarriesACodePerField() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String body = """
            {"sku":"SKU-1","name":"Bolt","hsnCode":"12","uom":"PCS",
             "gstRate":"7","baseRate":"-1"}""";

        // All three rules fail at once: ProductService.validate accumulates into one map, so a
        // per-field codes map is the only shape that can describe this response.
        mvc.perform(post("/api/v1/products")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fieldCodes.hsnCode").value("HSN_CODE_INVALID"))
                .andExpect(jsonPath("$.error.fieldCodes.gstRate").value("GST_RATE_INVALID"))
                .andExpect(jsonPath("$.error.fieldCodes.baseRate").value("BASE_RATE_NEGATIVE"));
    }

    @Test
    void duplicateSkuConflictCarriesAFieldAndACode() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        createProduct(auth, "SKU-DUP", "Bolt");

        String body = """
            {"sku":"SKU-DUP","name":"Other","hsnCode":"7318","uom":"PCS",
             "gstRate":"18","baseRate":"1"}""";

        mvc.perform(post("/api/v1/products")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.fieldCodes.sku").value("SKU_DUPLICATE"));
    }

    @Test
    void duplicateSkuReturns409() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String create = """
            {"sku":"SKU-DUP2","name":"X","hsnCode":"7318","uom":"PCS",
             "gstRate":"18","baseRate":"1.00"}""";
        mvc.perform(post("/api/v1/products")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(create))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/products")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(create))
                .andExpect(status().isConflict());
    }

    @Test
    void searchMatchesNameSubstring() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        createProduct(auth, "SKU-100", "Hex Bolt M8");
        createProduct(auth, "SKU-200", "Washer");

        mvc.perform(get("/api/v1/products?q=bolt").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("SKU-100"));
    }

    @Test
    void searchMatchesSku() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        createProduct(auth, "SKU-100", "Hex Bolt M8");
        // Matches neither by name nor sku. Without this row, a single-row tenant would make
        // totalElements == 1 true whether or not the sku predicate exists at all.
        createProduct(auth, "SKU-200", "Washer");

        mvc.perform(get("/api/v1/products?q=100").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void searchReturnsEmptyWhenNothingMatches() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        createProduct(auth, "SKU-100", "Hex Bolt M8");

        mvc.perform(get("/api/v1/products?q=zzzznomatch").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void searchComposesWithActive() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        // Matches "bolt" AND is inactive: visible only under active=false&q=bolt.
        String matchingInactive = createProduct(auth, "SKU-100", "Hex Bolt M8");
        mvc.perform(post("/api/v1/products/" + matchingInactive + "/deactivate").header("Authorization", auth))
                .andExpect(status().isOk());
        // Matches "bolt" AND stays active: visible only under active=true&q=bolt. Without this
        // row, "active=false&q=bolt" returning 1 would be reachable by the q predicate alone,
        // with the active predicate silently ignored.
        createProduct(auth, "SKU-101", "Carriage Bolt");
        // Active but does NOT match "bolt": if q were ignored, active=true&q=bolt would wrongly
        // return 2 instead of 1.
        createProduct(auth, "SKU-200", "Washer");
        // Inactive but does NOT match "bolt": if q were ignored, active=false&q=bolt would
        // wrongly return 2 instead of 1.
        String nonMatchingInactive = createProduct(auth, "SKU-201", "Steel Nut");
        mvc.perform(post("/api/v1/products/" + nonMatchingInactive + "/deactivate")
                        .header("Authorization", auth))
                .andExpect(status().isOk());

        // Neither the active filter alone (active=true -> {Carriage Bolt, Washer} = 2) nor the q
        // filter alone (q=bolt, ignoring active -> {Hex Bolt M8, Carriage Bolt} = 2) reproduces
        // this count of 1.
        mvc.perform(get("/api/v1/products?q=bolt&active=true").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("SKU-101"));
        // Neither the active filter alone (active=false -> {Hex Bolt M8, Steel Nut} = 2) nor the
        // q filter alone (q=bolt, ignoring active -> {Hex Bolt M8, Carriage Bolt} = 2) reproduces
        // this count of 1.
        mvc.perform(get("/api/v1/products?q=bolt&active=false").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("SKU-100"));
    }

    @Test
    void rejectsAnUnknownSortFieldWith422() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());

        mvc.perform(get("/api/v1/products?sort=baseRate,asc").header("Authorization", auth))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fieldCodes.sort").value("SORT_INVALID"));
    }

    /** The generated OpenAPI schema documents a maxLength on q regardless of whether the
     *  controller carries @Validated (springdoc reads the @Size annotation directly), so that
     *  schema alone cannot prove the constraint is enforced at runtime. Only an actual request
     *  can. */
    @Test
    void overLongQReturns400WithTheStandardEnvelope() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String tooLong = "a".repeat(101);

        mvc.perform(get("/api/v1/products").param("q", tooLong).header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fieldCodes.q").value("SIZE"));
    }

    /** Returns the created product's id. */
    private String createProduct(String auth, String sku, String name) throws Exception {
        String body = """
                {"sku":"%s","name":"%s","hsnCode":"7318","uom":"PCS",
                 "gstRate":"18","baseRate":"12.50"}""".formatted(sku, name);
        String response = mvc.perform(post("/api/v1/products")
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
