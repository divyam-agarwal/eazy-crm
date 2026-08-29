package com.easycrm.crm;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Endpoint-level: proves the HTTP contract 404s for an invisible customer, on both reads
 * and writes, and that OWNER/unassigned remain fully visible. See spec
 * 2026-08-29-record-visibility-design.md §4, §5.2, §6.1.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerVisibilityTest extends IntegrationTest {

    private static final String AUTH = "Authorization";

    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;
    @Autowired CustomerRepository customers;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID execAId = UUID.randomUUID();
    private final UUID execBId = UUID.randomUUID();

    private String execAToken;
    private String ownerToken;
    private UUID mine, theirs, pool;

    @BeforeEach
    void seed() {
        execAToken = tokens.as(tenantId, execAId, "SALES_EXEC");
        ownerToken = tokens.as(tenantId, UUID.randomUUID(), "OWNER");

        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, execAId, "OWNER"));
        mine = customers.saveAndFlush(newCustomer("Mine Traders", execAId)).getId();
        theirs = customers.saveAndFlush(newCustomer("Theirs Traders", execBId)).getId();
        pool = customers.saveAndFlush(newCustomer("Pool Traders", null)).getId();
        TenantContext.clear();
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void execCannotGetAnotherExecsCustomer() throws Exception {
        mvc.perform(get("/api/v1/customers/" + theirs).header(AUTH, bearer(execAToken)))
            .andExpect(status().isNotFound());
    }

    @Test
    void execCanGetTheirOwnCustomer() throws Exception {
        mvc.perform(get("/api/v1/customers/" + mine).header(AUTH, bearer(execAToken)))
            .andExpect(status().isOk());
    }

    @Test
    void execCanGetAnUnassignedCustomer() throws Exception {
        mvc.perform(get("/api/v1/customers/" + pool).header(AUTH, bearer(execAToken)))
            .andExpect(status().isOk());
    }

    @Test
    void ownerCanGetAnyCustomer() throws Exception {
        mvc.perform(get("/api/v1/customers/" + theirs).header(AUTH, bearer(ownerToken)))
            .andExpect(status().isOk());
    }

    @Test
    void execListOmitsAnotherExecsCustomer() throws Exception {
        mvc.perform(get("/api/v1/customers").header(AUTH, bearer(execAToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[*].id").value(not(hasItem(theirs.toString()))));
    }

    /** WRITE coverage. Without this the layer is cosmetic: a read filter alone still lets
     *  an exec who knows an id mutate a record they cannot see. */
    @Test
    void execCannotPatchAnotherExecsCustomer() throws Exception {
        mvc.perform(put("/api/v1/customers/" + theirs)
                .header(AUTH, bearer(execAToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCustomerJson()))
            .andExpect(status().isNotFound());
    }

    @Test
    void execCannotDeactivateAnotherExecsCustomer() throws Exception {
        mvc.perform(post("/api/v1/customers/" + theirs + "/deactivate")
                .header(AUTH, bearer(execAToken)))
            .andExpect(status().isNotFound());
    }

    /** The active filter must still work after findByActive is deleted. */
    @Test
    void activeFilterStillWorksForAnOwner() throws Exception {
        mvc.perform(get("/api/v1/customers?active=false").header(AUTH, bearer(ownerToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[*].id").value(not(hasItem(mine.toString()))));
    }

    // --- helpers -------------------------------------------------------------

    private Customer newCustomer(String name, UUID assignedTo) {
        return new Customer(name, null, "27", null, null, 0, assignedTo, null, CustomerSource.MANUAL);
    }

    private String bearer(String token) { return "Bearer " + token; }

    private String validCustomerJson() {
        return """
            {"businessName":"Theirs Traders Updated","stateCode":"27","source":"MANUAL"}""";
    }
}
