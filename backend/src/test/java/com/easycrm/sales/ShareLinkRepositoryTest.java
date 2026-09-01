package com.easycrm.sales;

import static org.junit.jupiter.api.Assertions.*;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class ShareLinkRepositoryTest extends IntegrationTest {
    @Autowired
    ShareLinkRepository links;

    @Autowired
    TransactionTemplate tx;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void isReadableWithNoTenantContextAtAll() {
        UUID tenantId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String token = "tok-" + UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, null, "OWNER"));
        tx.executeWithoutResult(s -> links.save(new ShareLink(token, tenantId, versionId)));
        TenantContext.clear();

        // The whole point: a public request has no tenant, and this row must still be
        // found. A tenant-scoped table would return empty here (RLS fails safe).
        ShareLink found = links.findByToken(token).orElseThrow();

        assertEquals(tenantId, found.getTenantId());
        assertEquals(versionId, found.getQuotationVersionId());
    }

    @Test
    void anUnknownTokenResolvesToNothing() {
        assertTrue(links.findByToken("tok-" + UUID.randomUUID()).isEmpty());
    }

    @Test
    void aVersionCannotHaveTwoLinks() {
        UUID tenantId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, null, "OWNER"));
        tx.executeWithoutResult(s -> links.save(new ShareLink("tok-" + UUID.randomUUID(), tenantId, versionId)));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> tx.executeWithoutResult(
                        s -> links.saveAndFlush(new ShareLink("tok-" + UUID.randomUUID(), tenantId, versionId))));
    }
}
