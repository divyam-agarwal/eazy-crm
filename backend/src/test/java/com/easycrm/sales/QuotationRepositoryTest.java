package com.easycrm.sales;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QuotationRepositoryTest extends IntegrationTest {
    @Autowired QuotationRepository quotations;
    @Autowired QuotationVersionRepository versions;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;

    @AfterEach void clear() { TenantContext.clear(); }

    private void asTenant(UUID t) {
        TenantContext.set(new TenantContext.TenantPrincipal(t, UUID.randomUUID(), "OWNER"));
    }

    @Test
    void savesQuotationAndVersionWithinTenant() {
        asTenant(UUID.randomUUID());
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Quotation q = quotations.save(new Quotation(UUID.randomUUID(), null));
            QuotationVersion v = versions.save(new QuotationVersion(q.getId(), 1, "27"));
            q.setCurrentVersionId(v.getId());
            assertThat(quotations.findById(q.getId())).isPresent();
            assertThat(versions.findByQuotationIdAndVersionNo(q.getId(), 1)).isPresent();
        });
    }

    @Test
    void findIsTenantScoped() {
        UUID a = UUID.randomUUID();
        asTenant(a);
        UUID savedId = new TransactionTemplate(txManager).execute(s ->
            quotations.save(new Quotation(UUID.randomUUID(), null)).getId());
        asTenant(UUID.randomUUID()); // different tenant
        assertThat(quotations.findById(savedId)).isEmpty();
    }

    @Test
    void rlsReturnsZeroRowsWithNoTenantSet() {
        UUID tenant = UUID.randomUUID();
        asTenant(tenant);
        new TransactionTemplate(txManager).executeWithoutResult(s ->
            quotations.save(new Quotation(UUID.randomUUID(), null)));
        TenantContext.clear();
        // Raw query with no app.current_tenant GUC set → RLS filters everything out.
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Number count = (Number) em.createNativeQuery("select count(*) from quotation").getSingleResult();
            assertThat(count.longValue()).isZero();
        });
    }
}
