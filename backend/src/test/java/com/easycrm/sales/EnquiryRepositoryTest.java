package com.easycrm.sales;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.platform.tenancy.TenantContext.TenantPrincipal;
import com.easycrm.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class EnquiryRepositoryTest extends IntegrationTest {

    @Autowired
    EnquiryRepository enquiries;

    @Autowired
    TransactionTemplate tx;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private Enquiry active(String phone) {
        return new Enquiry(null, "Ravi", phone, phone, null, EnquirySource.PHONE, null, null, null);
    }

    @Test
    void partialIndexBlocksSecondActiveEnquiryForSamePhone() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(new TenantPrincipal(tenant, UUID.randomUUID(), "OWNER"));
        tx.executeWithoutResult(s -> enquiries.save(active("9876543210")));

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> enquiries.saveAndFlush(active("9876543210"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void terminalEnquiryFreesThePhone() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(new TenantPrincipal(tenant, UUID.randomUUID(), "OWNER"));
        tx.executeWithoutResult(s -> {
            Enquiry first = active("9998887776");
            first.lose("gone"); // -> LOST, leaves the partial index
            enquiries.save(first);
        });
        assertThatCode(() -> tx.executeWithoutResult(s -> enquiries.saveAndFlush(active("9998887776"))))
                .doesNotThrowAnyException();

        // CONVERTED also frees the phone
        UUID tenant2 = UUID.randomUUID();
        TenantContext.set(new TenantPrincipal(tenant2, UUID.randomUUID(), "OWNER"));
        tx.executeWithoutResult(s -> {
            Enquiry c = active("7776665554");
            c.markConverted();
            enquiries.save(c);
        });
        assertThatCode(() -> tx.executeWithoutResult(s -> enquiries.saveAndFlush(active("7776665554"))))
                .doesNotThrowAnyException();
    }

    @Test
    void staleUpdateThrowsOptimisticLockingFailure() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(new TenantPrincipal(tenant, UUID.randomUUID(), "OWNER"));

        // Create at version 0.
        UUID id = tx.execute(s -> enquiries.save(active("9876500000")).getId());

        // A detached copy still at version 0.
        Enquiry stale = tx.execute(s -> enquiries.findById(id).orElseThrow());

        // Advance the DB row to version 1 in a separate transaction.
        tx.executeWithoutResult(s -> {
            Enquiry fresh = enquiries.findById(id).orElseThrow();
            fresh.advanceTo(EnquiryStage.CONTACTED);
            enquiries.saveAndFlush(fresh);
        });

        // Saving the stale (v0) copy over the now-v1 row loses the optimistic-lock race.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                    stale.advanceTo(EnquiryStage.QUALIFIED);
                    enquiries.saveAndFlush(stale);
                }))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }
}
