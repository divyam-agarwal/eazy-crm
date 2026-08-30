package com.easycrm.platform.visibility;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.crm.CustomerSource;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.sales.Enquiry;
import com.easycrm.sales.EnquiryRepository;
import com.easycrm.sales.EnquirySource;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The gate protecting the activity table. See spec
 * 2026-08-30-activity-follow-up-design.md §4.2.
 */
@SpringBootTest
class RequireVisibleSubjectTest extends IntegrationTest {

    @Autowired VisibleFinder finder;
    @Autowired CustomerRepository customers;
    @Autowired EnquiryRepository enquiries;
    @Autowired TransactionTemplate tx;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID execAId = UUID.randomUUID();
    private final UUID execBId = UUID.randomUUID();

    private UUID myEnquiry, execBEnquiry, myCustomer;

    @BeforeEach
    void seed() {
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, execAId, "OWNER"));
        tx.executeWithoutResult(s -> {
            myCustomer = customers.saveAndFlush(
                new Customer("Mine Traders", null, "MH", null, null, 0,
                    execAId, null, CustomerSource.MANUAL)).getId();
            myEnquiry = enquiries.saveAndFlush(newEnquiry("9876500011", execAId)).getId();
            execBEnquiry = enquiries.saveAndFlush(newEnquiry("9876500012", execBId)).getId();
        });
        TenantContext.clear();
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void returnsTheIdWhenTheSubjectIsVisible() {
        asExecA(() -> assertThat(
            finder.requireVisibleSubject(SubjectType.ENQUIRY, myEnquiry)).isEqualTo(myEnquiry));
    }

    @Test
    void throwsNotFoundForAnotherExecsSubject() {
        asExecA(() -> assertThatThrownBy(
            () -> finder.requireVisibleSubject(SubjectType.ENQUIRY, execBEnquiry))
            .isInstanceOf(NotFoundException.class));
    }

    @Test
    void throwsNotFoundForAnIdThatDoesNotExist() {
        asExecA(() -> assertThatThrownBy(
            () -> finder.requireVisibleSubject(SubjectType.ENQUIRY, UUID.randomUUID()))
            .isInstanceOf(NotFoundException.class));
    }

    @Test
    void resolvesCustomerSubjectsToo() {
        asExecA(() -> assertThat(
            finder.requireVisibleSubject(SubjectType.CUSTOMER, myCustomer)).isEqualTo(myCustomer));
    }

    @Test
    void anUnrestrictedRoleSeesAnotherExecsSubject() {
        TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, execAId, "OWNER"),
            () -> tx.executeWithoutResult(s -> assertThat(
                finder.requireVisibleSubject(SubjectType.ENQUIRY, execBEnquiry))
                .isEqualTo(execBEnquiry)));
    }

    private void asExecA(Runnable body) {
        TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, execAId, "SALES_EXEC"),
            () -> tx.executeWithoutResult(s -> body.run()));
    }

    private Enquiry newEnquiry(String phone, UUID assignedTo) {
        return new Enquiry(null, "Contact", phone, phone, null,
            EnquirySource.MANUAL, "need goods", assignedTo, null);
    }
}
