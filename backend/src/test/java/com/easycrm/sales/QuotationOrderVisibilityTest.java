package com.easycrm.sales;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.crm.CustomerSource;
import com.easycrm.iam.Role;
import com.easycrm.iam.User;
import com.easycrm.iam.UserRepository;
import com.easycrm.iam.UserStatus;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Endpoint-level: proves quotations and orders -- neither of which carries its own
 * assigned_to -- derive visibility from their customer via the EXISTS subquery in
 * {@code VisibilityPolicy.viaCustomer}, on both reads and writes, and that OWNER remains
 * fully visible. See spec 2026-08-29-record-visibility-design.md §4, §5.2, §6.1.
 */
@SpringBootTest
@AutoConfigureMockMvc
class QuotationOrderVisibilityTest extends IntegrationTest {

    private static final String AUTH = "Authorization";

    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;
    @Autowired CustomerRepository customers;
    @Autowired QuotationRepository quotations;
    @Autowired QuotationVersionRepository versions;
    @Autowired OrderRepository orders;
    @Autowired UserRepository users;
    @Autowired TransactionTemplate tx;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID execBId = UUID.randomUUID();

    private String execAToken;
    private String ownerToken;
    private UUID execAId;

    private UUID customerA, customerB, customerPool;
    private UUID quoteUnderA, quoteUnderB, quoteUnderPool;
    private UUID orderUnderA, orderUnderB, orderUnderPool;
    private int docSeq = 0;

    @BeforeEach
    void seed() {
        // A real ACTIVE user, not a bare random id: reassignCustomer() below reassigns a
        // customer TO execAId through the real update endpoint, which now validates
        // assignedTo (task 7) -- a bare random UUID would 422 there.
        execAId = seedUser(UserStatus.ACTIVE);
        execAToken = tokens.as(tenantId, execAId, "SALES_EXEC");
        ownerToken = tokens.as(tenantId, UUID.randomUUID(), "OWNER");

        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, execAId, "OWNER"));
        customerA = customers.saveAndFlush(newCustomer("Customer A", execAId)).getId();
        customerB = customers.saveAndFlush(newCustomer("Customer B", execBId)).getId();
        customerPool = customers.saveAndFlush(newCustomer("Customer Pool", null)).getId();

        quoteUnderA = seedQuotationAndOrder(customerA);
        quoteUnderB = seedQuotationAndOrder(customerB);
        quoteUnderPool = seedQuotationAndOrder(customerPool);
        TenantContext.clear();
    }

    @AfterEach void clear() { TenantContext.clear(); }

    // --- quotation reads -------------------------------------------------------------

    @Test
    void execCanGetTheQuotationUnderTheirOwnCustomer() throws Exception {
        mvc.perform(get("/api/v1/quotations/" + quoteUnderA).header(AUTH, bearer(execAToken)))
            .andExpect(status().isOk());
    }

    @Test
    void execCannotGetTheQuotationUnderAnotherExecsCustomer() throws Exception {
        mvc.perform(get("/api/v1/quotations/" + quoteUnderB).header(AUTH, bearer(execAToken)))
            .andExpect(status().isNotFound());
    }

    @Test
    void execCanGetTheQuotationUnderAnUnassignedCustomer() throws Exception {
        mvc.perform(get("/api/v1/quotations/" + quoteUnderPool).header(AUTH, bearer(execAToken)))
            .andExpect(status().isOk());
    }

    @Test
    void ownerCanGetAnyQuotation() throws Exception {
        mvc.perform(get("/api/v1/quotations/" + quoteUnderA).header(AUTH, bearer(ownerToken)))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/quotations/" + quoteUnderB).header(AUTH, bearer(ownerToken)))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/quotations/" + quoteUnderPool).header(AUTH, bearer(ownerToken)))
            .andExpect(status().isOk());
    }

    @Test
    void execQuotationListOmitsAnotherExecsCustomersQuotation() throws Exception {
        mvc.perform(get("/api/v1/quotations").header(AUTH, bearer(execAToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[*].id").value(not(hasItem(quoteUnderB.toString()))))
            .andExpect(jsonPath("$.content[*].id").value(hasItem(quoteUnderA.toString())))
            .andExpect(jsonPath("$.content[*].id").value(hasItem(quoteUnderPool.toString())));
    }

    /** WRITE coverage. Findable-first ordering means the 404 fires before any status check. */
    @Test
    void execCannotAcceptTheQuotationUnderAnotherExecsCustomer() throws Exception {
        mvc.perform(post("/api/v1/quotations/" + quoteUnderB + "/accept")
                .header(AUTH, bearer(execAToken)))
            .andExpect(status().isNotFound());
    }

    // --- order reads -------------------------------------------------------------

    @Test
    void execCanGetTheOrderUnderTheirOwnCustomer() throws Exception {
        mvc.perform(get("/api/v1/orders/" + orderUnderA).header(AUTH, bearer(execAToken)))
            .andExpect(status().isOk());
    }

    @Test
    void execCannotGetTheOrderUnderAnotherExecsCustomer() throws Exception {
        mvc.perform(get("/api/v1/orders/" + orderUnderB).header(AUTH, bearer(execAToken)))
            .andExpect(status().isNotFound());
    }

    @Test
    void execCanGetTheOrderUnderAnUnassignedCustomer() throws Exception {
        mvc.perform(get("/api/v1/orders/" + orderUnderPool).header(AUTH, bearer(execAToken)))
            .andExpect(status().isOk());
    }

    @Test
    void ownerCanGetAnyOrder() throws Exception {
        mvc.perform(get("/api/v1/orders/" + orderUnderA).header(AUTH, bearer(ownerToken)))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/orders/" + orderUnderB).header(AUTH, bearer(ownerToken)))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/orders/" + orderUnderPool).header(AUTH, bearer(ownerToken)))
            .andExpect(status().isOk());
    }

    @Test
    void execOrderListOmitsAnotherExecsCustomersOrder() throws Exception {
        mvc.perform(get("/api/v1/orders").header(AUTH, bearer(execAToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[*].id").value(not(hasItem(orderUnderB.toString()))))
            .andExpect(jsonPath("$.content[*].id").value(hasItem(orderUnderA.toString())))
            .andExpect(jsonPath("$.content[*].id").value(hasItem(orderUnderPool.toString())));
    }

    /** WRITE coverage. Findable-first ordering means the 404 fires before any status check. */
    @Test
    void execCannotCancelTheOrderUnderAnotherExecsCustomer() throws Exception {
        mvc.perform(post("/api/v1/orders/" + orderUnderB + "/cancel")
                .header(AUTH, bearer(execAToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"cancelReason":"testing"}"""))
            .andExpect(status().isNotFound());
    }

    /**
     * Visibility derives from the customer, so reassigning the customer moves its whole
     * quotation and order history. Spec §4 accepts this consequence explicitly -- this
     * test is what makes it a decision rather than a surprise.
     */
    @Test
    void reassigningTheCustomerMovesItsQuotations() throws Exception {
        mvc.perform(get("/api/v1/quotations/" + quoteUnderB).header(AUTH, bearer(execAToken)))
            .andExpect(status().isNotFound());

        reassignCustomer(customerB, execAId);

        mvc.perform(get("/api/v1/quotations/" + quoteUnderB).header(AUTH, bearer(execAToken)))
            .andExpect(status().isOk());
    }

    // --- helpers -------------------------------------------------------------

    private Customer newCustomer(String name, UUID assignedTo) {
        return new Customer(name, null, "27", null, null, 0, assignedTo, null, CustomerSource.MANUAL);
    }

    /** Seeds a quotation with one version (so GET can resolve currentVersion) plus its order. */
    private UUID seedQuotationAndOrder(UUID customerId) {
        Quotation quotation = quotations.saveAndFlush(new Quotation(customerId, null));
        QuotationVersion version = versions.saveAndFlush(new QuotationVersion(quotation.getId(), 1, "27"));
        version.setTotals(BigDecimal.TEN, BigDecimal.ONE, new BigDecimal("11"));
        versions.saveAndFlush(version);
        int seq = ++docSeq;
        quotation.setCurrentVersionId(version.getId());
        quotation.assignQuoteNo("Q-SEED-" + seq);
        quotation.markSent();
        quotation.markAccepted();
        quotations.saveAndFlush(quotation);

        UUID orderId = orders.saveAndFlush(new Order(quotation.getId(), version.getId(), customerId,
            "O-SEED-" + seq, BigDecimal.TEN, BigDecimal.ONE,
            new BigDecimal("11"), null, null)).getId();

        if (customerId.equals(customerA)) orderUnderA = orderId;
        else if (customerId.equals(customerB)) orderUnderB = orderId;
        else orderUnderPool = orderId;

        return quotation.getId();
    }

    /** Owner-authenticated update of a customer's assignment through the real endpoint. */
    private void reassignCustomer(UUID customerId, UUID assignTo) throws Exception {
        mvc.perform(put("/api/v1/customers/" + customerId)
                .header(AUTH, bearer(ownerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"businessName":"Customer B","stateCode":"27","source":"MANUAL","assignedTo":"%s"}"""
                    .formatted(assignTo)))
            .andExpect(status().isOk());
    }

    private String bearer(String token) { return "Bearer " + token; }

    /** Seeds a real User row in this test's tenant so assignedTo can resolve against it. */
    private UUID seedUser(UserStatus status) {
        return TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, UUID.randomUUID(), "OWNER"),
            () -> tx.execute(s -> users.save(new User(
                "user-" + UUID.randomUUID() + "@example.com", null, "hash",
                Role.SALES_EXEC, status)).getId()));
    }
}
