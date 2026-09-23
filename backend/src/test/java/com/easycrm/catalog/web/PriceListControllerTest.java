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
class PriceListControllerTest extends IntegrationTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    TestTokens tokens;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void createThenRejectDuplicateName() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String body = "{\"name\":\"Dealer\"}";
        mvc.perform(post("/api/v1/price-lists")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Dealer"));
        mvc.perform(post("/api/v1/price-lists")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.fieldCodes.name").value("NAME_DUPLICATE"));
    }

    @Test
    void renameToAnExistingNameCarriesTheSameCode() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        createPriceList(auth, "Dealer");
        String otherId = createPriceListReturningId(auth, "Retail");

        String body = "{\"name\":\"Dealer\"}";
        mvc.perform(put("/api/v1/price-lists/" + otherId)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.fieldCodes.name").value("NAME_DUPLICATE"));
    }

    @Test
    void searchMatchesNameSubstring() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        createPriceList(auth, "Monsoon 2026");
        // Matches nothing. Without this row, a single-row tenant would make totalElements == 1
        // true whether or not the q predicate exists at all.
        createPriceList(auth, "Winter 2026");

        mvc.perform(get("/api/v1/price-lists?q=monsoon").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Monsoon 2026"));
    }

    @Test
    void searchReturnsEmptyWhenNothingMatches() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        createPriceList(auth, "Monsoon 2026");

        mvc.perform(get("/api/v1/price-lists?q=zzzznomatch").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void rejectsAnUnknownSortFieldWith422() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());

        mvc.perform(get("/api/v1/price-lists?sort=active,asc").header("Authorization", auth))
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

        mvc.perform(get("/api/v1/price-lists").param("q", tooLong).header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fieldCodes.q").value("SIZE"));
    }

    private void createPriceList(String auth, String name) throws Exception {
        createPriceListReturningId(auth, name);
    }

    private String createPriceListReturningId(String auth, String name) throws Exception {
        String body = "{\"name\":\"%s\"}".formatted(name);
        String response = mvc.perform(post("/api/v1/price-lists")
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
