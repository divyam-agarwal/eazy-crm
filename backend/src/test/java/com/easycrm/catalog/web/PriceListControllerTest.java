package com.easycrm.catalog.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    /**
     * Spec §1.2's default sort exists so paging is stable: with no ORDER BY, Postgres is free to
     * return rows in a different order between two separate queries, and OFFSET/LIMIT paging over
     * an unstable order can return a row twice or skip it. 25 rows spans two pages at the
     * configured default page size (20).
     *
     * <p>A plain heap scan on a small, freshly seeded, otherwise-untouched table tends to replay
     * insertion order even with no ORDER BY, which would let this test pass for the wrong reason.
     * Toggling every row's active flag between the two fetches, then {@code VACUUM FULL}ing the
     * table (physically rewriting it, which a real deployment's autovacuum does too, just not on
     * this schedule), forces an actual change in physical row order between the two page reads —
     * confirmed by temporarily removing {@code SortAllowlist.withDefault} from
     * {@code PriceListService.list}: with no default sort, this exact test fails with a row
     * returned on both pages; with it, it passes.
     */
    @Test
    void pagingIsStableAcrossPagesWithNoExplicitSort() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            ids.add(createPriceListReturningId(auth, "Stability List %02d".formatted(i)));
        }
        for (String id : ids) {
            mvc.perform(post("/api/v1/price-lists/" + id + "/deactivate").header("Authorization", auth))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/v1/price-lists/" + id + "/activate").header("Authorization", auth))
                    .andExpect(status().isOk());
        }

        List<String> page0 = pageOfIds(auth, 0);
        try (var conn = ownerConnection();
                var st = conn.createStatement()) {
            st.execute("VACUUM FULL price_list");
        }
        List<String> page1 = pageOfIds(auth, 1);

        Set<String> overlap = new HashSet<>(page0);
        overlap.retainAll(page1);
        assertTrue(overlap.isEmpty(), "same row returned on both page 0 and page 1: " + overlap);

        Set<String> union = new HashSet<>(page0);
        union.addAll(page1);
        assertEquals(new HashSet<>(ids), union, "some seeded price list missing from page 0 + page 1");
    }

    private List<String> pageOfIds(String auth, int page) throws Exception {
        String response = mvc.perform(get("/api/v1/price-lists?page=" + page).header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.content[*].id");
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
