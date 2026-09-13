package com.easycrm.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.crm.CustomerSource;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moved from {@code VisibilityPolicyIntegrationTest} (formerly in the platform module's visibility
 * package) in Wave 1.6, when the four
 * sales aggregates' visibility specifications moved out of {@code VisibilityPolicy} (deleted) into
 * {@code SalesVisibility}. The assertions and comments are unchanged.
 *
 * <p>The original reached the raw specifications — {@code quotations.findAll(policy.quotations())}
 * — because {@code VisibilityPolicy} exposed them publicly. They are private implementation detail
 * of {@code SalesVisibility} now (as on {@code crm.CustomerVisibility}), so each read goes through
 * the public {@code pageX(null, Pageable.unpaged())} instead. Same rows, same assertions, one more
 * layer of the real call path exercised.
 */
class SalesVisibilityPolicyTest extends IntegrationTest {

    @Autowired
    SalesVisibility visibility;

    @Autowired
    CustomerRepository customers;

    @Autowired
    QuotationRepository quotations;

    @Autowired
    OrderRepository orders;

    @Autowired
    EnquiryRepository enquiries;

    @Autowired
    TransactionTemplate tx;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID execA = UUID.randomUUID();
    private final UUID execB = UUID.randomUUID();

    private UUID customerA, customerB, customerUnassigned;
    private UUID quoteA, quoteB, quoteUnassigned;
    private UUID myEnquiry, theirEnquiry;

    /**
     * Only distinguishes the two phone numbers written within ONE seed() call (0, then 1); it is
     * NOT what isolates one test from another — JUnit builds a fresh instance per test method, so
     * this is always 0 then 1 and cannot collide across tests regardless. Cross-test isolation
     * comes from {@code tenantId} being freshly randomised per instance.
     */
    private int seq = 0;

    @BeforeEach
    void seed() {
        asPrincipal(execA, "OWNER", () -> {
            customerA = save(newCustomer("A Traders", execA)).getId();
            customerB = save(newCustomer("B Traders", execB)).getId();
            customerUnassigned = save(newCustomer("Pool Traders", null)).getId();
            quoteA = saveQuote(customerA);
            quoteB = saveQuote(customerB);
            quoteUnassigned = saveQuote(customerUnassigned);
            myEnquiry = saveEnquiry("98765" + (10000 + seq++), execA);
            theirEnquiry = saveEnquiry("98765" + (10000 + seq++), execB);
        });
    }

    @Test
    void salesExecSeesQuotationsThroughTheirCustomer() {
        asPrincipal(
                execA,
                "SALES_EXEC",
                () -> assertThat(ids(visibility
                                .pageQuotations(null, Pageable.unpaged())
                                .getContent()))
                        .containsExactlyInAnyOrder(quoteA, quoteUnassigned)
                        .doesNotContain(quoteB));
    }

    @Test
    void salesExecSeesOrdersThroughTheirCustomer() {
        UUID orderA = asPrincipalGet(execA, "OWNER", () -> saveOrder(customerA, quoteA));
        UUID orderB = asPrincipalGet(execA, "OWNER", () -> saveOrder(customerB, quoteB));

        asPrincipal(
                execA,
                "SALES_EXEC",
                () -> assertThat(ids(
                                visibility.pageOrders(null, Pageable.unpaged()).getContent()))
                        .contains(orderA)
                        .doesNotContain(orderB));
    }

    /**
     * Replaces the original {@code absentPrincipalIsUnrestricted}, which asserted {@code
     * policy.unrestricted()} directly. {@code SalesVisibility.unrestricted()} is private — it is
     * implementation detail behind the specs, not part of the class's contract — so the fail-OPEN
     * branch is proved through an observable read instead. The technique is
     * CustomerVisibilityDirectTest's; the reasoning below is the same on this side of the split.
     *
     * <p>This is the ONLY test of {@code .orElse(true)}: that branch runs only when {@code
     * TenantContext.get()} is truly empty, which a bound principal of ANY role never produces. An
     * absent principal is what internal flows with no user to attribute run under — async
     * listeners, {@code TenantJobRunner}'s synthetic {@code "SYSTEM"} principal, tenant
     * provisioning. None carries a role to restrict, so defaulting to unrestricted is correct;
     * failing CLOSED would silently hide rows from code that never opted into any restriction.
     *
     * <p><b>It reads an ENQUIRY, not a quotation, and that choice is load-bearing.</b> A quotation
     * read cannot detect this branch at all: {@code viaCustomer} delegates the ownership predicate
     * to {@code crm.CustomerVisibility.spec()}, which has its OWN {@code .orElse(true)}, so with no
     * principal bound the subquery comes back permissive no matter what this class decided. An
     * Enquiry's spec is intrinsic — built here, from {@code currentUserId()} — so it is the only
     * shape whose result depends on THIS class's role decision. Verified by mutation: flipping
     * {@code SalesVisibility.unrestricted()}'s {@code .orElse(true)} to {@code false} leaves the
     * quotation form green and fails this one.
     *
     * <p>Clearing {@code TenantContext} INSIDE an already-open transaction, rather than before one
     * starts, is deliberate: {@code TenantAwareTransactionManager} reads {@code
     * TenantContext.tenantId()} once at {@code doBegin} to set the RLS GUC, and Hibernate resolves
     * a session's tenant once at session-open — neither is re-read afterward. So the query below
     * still runs against execA's tenant (RLS still applies), while the ROLE decision it makes is
     * based on no principal at all, isolating the fail-open branch from the tenant wall it is not
     * supposed to touch.
     */
    @Test
    void absentPrincipalIsUnrestrictedEvenThoughRlsStillScopesTheQuery() {
        TenantContext.runAs(
                new TenantContext.TenantPrincipal(tenantId, execA, "SALES_EXEC"),
                () -> tx.executeWithoutResult(status -> {
                    TenantContext.clear();
                    // theirEnquiry is assigned to execB -- invisible to execA under SALES_EXEC
                    // whenever a principal IS bound. With none bound, it must be visible.
                    assertThat(visibility.findEnquiry(theirEnquiry)).isPresent();
                }));
    }

    /** The bound-principal half of the pair above: with a SALES_EXEC bound, the row IS hidden. */
    @Test
    void salesExecDoesNotSeeAnotherExecsEnquiry() {
        asPrincipal(execA, "SALES_EXEC", () -> {
            assertThat(visibility.findEnquiry(theirEnquiry)).isEmpty();
            assertThat(visibility.findEnquiry(myEnquiry)).isPresent();
        });
    }

    /**
     * Pins the unrestricted early return in {@code viaCustomer}. This schema declares no foreign
     * keys and {@code quotation.customer_id} is a bare {@code UUID NOT NULL}, so a quotation whose
     * customer row does not exist is representable. Building the EXISTS subquery unconditionally
     * would additionally require the customer to EXIST and would hide this row from an OWNER — a
     * behaviour change wearing a refactor's clothes. See challenge log #77.
     */
    @Test
    void ownerSeesAQuotationWhoseCustomerRowIsMissing() {
        UUID orphan = asPrincipalGet(execA, "OWNER", () -> saveQuote(UUID.randomUUID()));
        asPrincipal(
                execA,
                "OWNER",
                () -> assertThat(visibility.findQuotation(orphan)).isPresent());
    }

    /**
     * The other half of the asymmetry above, recorded rather than left to javadoc: a restricted
     * read goes through {@code viaCustomer}'s EXISTS subquery, which requires the customer row to
     * EXIST, so the very same orphan that an OWNER sees is invisible to a SALES_EXEC. This is not a
     * bug to fix — a restricted read legitimately requires the customer row to exist, while an
     * unrestricted one must not (see {@code viaCustomer}'s javadoc).
     */
    @Test
    void salesExecDoesNotSeeAQuotationWhoseCustomerRowIsMissing() {
        UUID orphan = asPrincipalGet(execA, "OWNER", () -> saveQuote(UUID.randomUUID()));
        asPrincipal(
                execA,
                "SALES_EXEC",
                () -> assertThat(visibility.findQuotation(orphan)).isEmpty());
    }

    // --- helpers -------------------------------------------------------------

    private void asPrincipal(UUID userId, String role, Runnable body) {
        TenantContext.runAs(
                new TenantContext.TenantPrincipal(tenantId, userId, role),
                () -> tx.executeWithoutResult(s -> body.run()));
    }

    private <T> T asPrincipalGet(UUID userId, String role, java.util.function.Supplier<T> body) {
        return TenantContext.runAs(
                new TenantContext.TenantPrincipal(tenantId, userId, role), () -> tx.execute(s -> body.get()));
    }

    private Customer newCustomer(String name, UUID assignedTo) {
        return new Customer(name, null, "27", "addr", "addr", 0, assignedTo, null, CustomerSource.MANUAL);
    }

    private Customer save(Customer c) {
        return customers.save(c);
    }

    /** Copied verbatim from EnquiryVisibilityTest: Enquiry is constructor-only. */
    private UUID saveEnquiry(String phone, UUID assignedTo) {
        return enquiries
                .save(new Enquiry(
                        null, "Test Contact", phone, phone, null, EnquirySource.MANUAL, null, assignedTo, null))
                .getId();
    }

    private UUID saveQuote(UUID customerId) {
        return quotations.save(new Quotation(customerId, null)).getId();
    }

    private UUID saveOrder(UUID customerId, UUID quotationId) {
        return orders.save(new Order(
                        quotationId,
                        UUID.randomUUID(),
                        customerId,
                        "SO-" + UUID.randomUUID().toString().substring(0, 8),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null,
                        LocalDate.now()))
                .getId();
    }

    private static List<UUID> ids(List<? extends com.easycrm.platform.persistence.BaseEntity> rows) {
        return rows.stream()
                .map(com.easycrm.platform.persistence.BaseEntity::getId)
                .toList();
    }
}
