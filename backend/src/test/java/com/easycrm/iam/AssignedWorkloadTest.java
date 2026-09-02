package com.easycrm.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.crm.CustomerSource;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.sales.Enquiry;
import com.easycrm.sales.EnquiryRepository;
import com.easycrm.sales.EnquirySource;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class AssignedWorkloadTest extends IntegrationTest {

    @Autowired
    List<AssignedWorkload> workloads;

    @Autowired
    CustomerRepository customers;

    @Autowired
    EnquiryRepository enquiries;

    @Autowired
    TestTokens tokens;

    @Autowired
    TransactionTemplate tx;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private Map<String, AssignedWorkload> byLabel() {
        return workloads.stream().collect(Collectors.toMap(AssignedWorkload::label, Function.identity()));
    }

    @Test
    void everyAssignedAggregateIsRepresented() {
        assertEquals(
                java.util.Set.of("customers", "enquiries", "follow-ups"),
                byLabel().keySet(),
                "each aggregate carrying its own assigned_to must block a disable");
    }

    @Test
    void anActiveAssignedCustomerCounts() {
        var owner = tokens.provisionOwner("27");
        UUID member = UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(owner.tenantId(), null, "SYSTEM"));

        // customer.source is NOT NULL; assignedTo is the 7th argument.
        tx.executeWithoutResult(s ->
                customers.save(new Customer("Shop A", null, "27", null, null, 0, member, null, CustomerSource.MANUAL)));

        assertEquals(1L, byLabel().get("customers").countOpenFor(member));
        assertEquals(0L, byLabel().get("customers").countOpenFor(UUID.randomUUID()), "scoped to the member");
    }

    @Test
    void aTerminalEnquiryDoesNotCount() {
        var owner = tokens.provisionOwner("27");
        UUID member = UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(owner.tenantId(), null, "SYSTEM"));

        tx.executeWithoutResult(s -> {
            // (customerId, contactName, contactPhone, normalizedPhone, contactEmail,
            //  source, requirementText, assignedTo, expectedValue) — both phones are
            //  NOT NULL. A new Enquiry starts at stage NEW.
            Enquiry open = new Enquiry(
                    null, "Ravi", "9876543210", "9876543210", null, EnquirySource.MANUAL, "need pipes", member, null);
            Enquiry done = new Enquiry(
                    null, "Sita", "9876543211", "9876543211", null, EnquirySource.MANUAL, "need taps", member, null);
            // lose(reason), not advanceTo: advanceTo takes one argument and REFUSES a
            // terminal stage (`!target.isActive()` throws). LOST and CONVERTED have their
            // own methods, lose(String) and markConverted().
            done.lose("no budget");
            enquiries.save(open);
            enquiries.save(done);
        });

        assertEquals(
                1L, byLabel().get("enquiries").countOpenFor(member), "only non-terminal enquiries block a disable");
    }
}
