package com.easycrm.sales;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.easycrm.iam.Role;
import com.easycrm.iam.User;
import com.easycrm.iam.UserRepository;
import com.easycrm.iam.UserStatus;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Closes the three side doors that reach a record without going through its aggregate's
 * front door: contacts (gated by their parent customer), the PDF render's version-first
 * entry point, and the share-link mint. See spec 2026-08-29-record-visibility-design.md
 * §5.2, §5.3.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NestedVisibilityTest extends IntegrationTest {

    private static final String AUTH = "Authorization";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestTokens tokens;

    @Autowired
    UserRepository users;

    @Autowired
    TransactionTemplate tx;

    private String execAToken;
    private String ownerToken;

    private UUID customerB;
    private UUID contactUnderB;
    private UUID quoteUnderB;
    private String tokenMintedByOwnerForQuoteUnderB;

    @BeforeEach
    void seed() throws Exception {
        TestTokens.ProvisionedOwner owner = tokens.provisionOwner("27");
        UUID tenantId = owner.tenantId();
        ownerToken = owner.token();
        UUID execAId = UUID.randomUUID();
        execAToken = tokens.as(tenantId, execAId, "SALES_EXEC");
        // Customer B's assignee must be a real ACTIVE user in this tenant now that
        // CustomerService validates assignedTo (task 7) -- a bare random UUID 422s.
        UUID assigneeOfB = seedUser(tenantId, UserStatus.ACTIVE);

        customerB = UUID.fromString(JsonPath.read(
                mvc.perform(post("/api/v1/customers")
                                .header(AUTH, bearer(ownerToken))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                    {"businessName":"Customer B","stateCode":"27","source":"MANUAL","assignedTo":"%s"}""".formatted(assigneeOfB)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id"));

        contactUnderB = UUID.fromString(JsonPath.read(
                mvc.perform(post("/api/v1/customers/" + customerB + "/contacts")
                                .header(AUTH, bearer(ownerToken))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                    {"name":"Ramesh","isPrimary":true}"""))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id"));

        String pId = JsonPath.read(
                mvc.perform(post("/api/v1/products")
                                .header(AUTH, bearer(ownerToken))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                    {"sku":"SKU-%s","name":"Widget","hsnCode":"84821011","uom":"PCS",
                     "gstRate":"18","baseRate":"100.00"}""".formatted(
                                                UUID.randomUUID().toString().substring(0, 8))))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");

        quoteUnderB = UUID.fromString(JsonPath.read(
                mvc.perform(post("/api/v1/quotations")
                                .header(AUTH, bearer(ownerToken))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"customerId\":\"%s\",\"items\":[{\"productId\":\"%s\",\"qty\":\"10\"}]}"
                                        .formatted(customerB, pId)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id"));

        mvc.perform(post("/api/v1/quotations/" + quoteUnderB + "/send").header(AUTH, bearer(ownerToken)))
                .andExpect(status().isOk());

        String publicUrl = JsonPath.read(
                mvc.perform(post("/api/v1/quotations/" + quoteUnderB + "/share").header(AUTH, bearer(ownerToken)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.publicUrl");
        tokenMintedByOwnerForQuoteUnderB = publicUrl.substring(publicUrl.lastIndexOf('/') + 1);
    }

    @Test
    void execCannotListContactsUnderAnInvisibleCustomer() throws Exception {
        mvc.perform(get("/api/v1/customers/" + customerB + "/contacts").header(AUTH, bearer(execAToken)))
                .andExpect(status().isNotFound());
    }

    /**
     * There is no GET-single-contact route (only list/PUT/DELETE by id) -- verified against
     * ContactController before writing this. PUT exercises the same ContactService.find gate
     * that a GET would, so it stands in for the brief's inferred
     * "execCannotGetAContactUnderAnInvisibleCustomer".
     */
    @Test
    void execCannotUpdateAContactUnderAnInvisibleCustomer() throws Exception {
        mvc.perform(put("/api/v1/customers/" + customerB + "/contacts/" + contactUnderB)
                        .header(AUTH, bearer(execAToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {"name":"Ramesh Updated"}"""))
                .andExpect(status().isNotFound());
    }

    @Test
    void execCannotRenderThePdfOfAnInvisibleQuotation() throws Exception {
        mvc.perform(get("/api/v1/quotations/" + quoteUnderB + "/pdf").header(AUTH, bearer(execAToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void execCannotMintAShareLinkForAnInvisibleQuotation() throws Exception {
        mvc.perform(post("/api/v1/quotations/" + quoteUnderB + "/share").header(AUTH, bearer(execAToken)))
                .andExpect(status().isNotFound());
    }

    /**
     * The public route is deliberately OUTSIDE this layer: it has no JWT, so there is no
     * principal to filter against. A share link minted by a manager must keep working for
     * the customer who received it. Spec §5.3.
     */
    @Test
    void thePublicShareRouteStaysUnfiltered() throws Exception {
        mvc.perform(get("/public/q/" + tokenMintedByOwnerForQuoteUnderB)).andExpect(status().isOk());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    /** Seeds a real User row in the given tenant so assignedTo can resolve against it. */
    private UUID seedUser(UUID tenantId, UserStatus status) {
        return TenantContext.runAs(
                new TenantContext.TenantPrincipal(tenantId, UUID.randomUUID(), "OWNER"),
                () -> tx.execute(s -> users.save(new User(
                                "user-" + UUID.randomUUID() + "@example.com", null, "hash", Role.SALES_EXEC, status))
                        .getId()));
    }
}
