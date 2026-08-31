package com.easycrm.sales;

import com.easycrm.iam.AuditLog;
import com.easycrm.iam.AuditLogRepository;
import com.easycrm.platform.format.IndianFormats;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.platform.visibility.SubjectType;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sweep's behaviour for one tenant: what it flips, what it leaves alone, what trace it
 * leaves, and that running it twice changes nothing the second time. See spec 2026-08-31 §8.
 */
@SpringBootTest
class QuotationExpirySweepTest extends IntegrationTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Autowired QuotationExpirySweep sweep;
    @Autowired QuotationRepository quotations;
    @Autowired QuotationVersionRepository versions;
    @Autowired ActivityRepository activities;
    @Autowired AuditLogRepository auditLogs;
    @Autowired TransactionTemplate tx;

    private final UUID tenantId = UUID.randomUUID();
    private int seq = 0;

    private UUID lapsed, stillValid;
    private String lapsedQuoteNo;

    @BeforeEach
    void seed() {
        inTenant(() -> {
            lapsed = seedSent(AS_OF.minusDays(1));
            lapsedQuoteNo = quotations.findById(lapsed).orElseThrow().getQuoteNo();
            stillValid = seedSent(AS_OF.plusDays(7));
            return null;
        });
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void expiresTheLapsedQuotationAndReportsTheCount() {
        int expired = inTenant(() -> sweep.run(AS_OF));

        assertThat(expired).isEqualTo(1);
        assertThat(statusOf(lapsed)).isEqualTo(QuotationStatus.EXPIRED);
    }

    @Test
    void leavesAStillValidQuotationAlone() {
        inTenant(() -> sweep.run(AS_OF));
        assertThat(statusOf(stillValid)).isEqualTo(QuotationStatus.SENT);
    }

    @Test
    void writesAnAuditRowWithNoHumanActor() {
        inTenant(() -> sweep.run(AS_OF));

        Optional<AuditLog> row = inTenant(() -> auditLogs.findFirstByAction("QUOTATION_EXPIRED"));
        assertThat(row).isPresent();
        assertThat(row.get().getActorUserId()).isNull();
        UUID lapsedVersionId = inTenant(
            () -> quotations.findById(lapsed).orElseThrow().getCurrentVersionId());
        assertThat(row.get().getDetail())
            .containsEntry("quotationId", lapsed.toString())
            .containsEntry("quoteNo", lapsedQuoteNo)
            .containsEntry("quotationVersionId", lapsedVersionId.toString())
            .containsEntry("validUntil", AS_OF.minusDays(1).toString());
    }

    @Test
    void writesASystemActivityOnTheQuotationTimeline() {
        inTenant(() -> sweep.run(AS_OF));

        var page = inTenant(() -> activities.findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(
            SubjectType.QUOTATION, lapsed, PageRequest.of(0, 10)));

        assertThat(page.getContent()).hasSize(1);
        Activity activity = page.getContent().get(0);
        assertThat(activity.getSource()).isEqualTo(ActivitySource.SYSTEM);
        assertThat(activity.getLoggedBy()).isNull();
        assertThat(activity.getBody()).contains("expired");
        // The quote number and the formatted date are what make the timeline entry useful --
        // a listener that dropped either would still pass a bare "contains expired" check.
        assertThat(activity.getBody()).contains(lapsedQuoteNo);
        assertThat(activity.getBody()).contains(IndianFormats.date(AS_OF.minusDays(1)));
    }

    @Test
    void isIdempotentAcrossTwoRuns() {
        inTenant(() -> sweep.run(AS_OF));
        int secondRun = inTenant(() -> sweep.run(AS_OF));

        assertThat(secondRun).isZero();
        assertThat(inTenant(() -> auditLogs.countByAction("QUOTATION_EXPIRED"))).isEqualTo(1L);

        var page = inTenant(() -> activities.findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(
            SubjectType.QUOTATION, lapsed, PageRequest.of(0, 10)));
        assertThat(page.getContent()).hasSize(1);
    }

    // --- helpers ---------------------------------------------------------------------

    /** Runs body with this test's tenant bound and a transaction open, in that order. */
    private <T> T inTenant(java.util.function.Supplier<T> body) {
        java.util.function.Supplier<T> work = () -> tx.execute(s -> body.get());
        try {
            return TenantContext.runAs(
                new TenantContext.TenantPrincipal(tenantId, null, "SYSTEM"), work);
        } finally {
            TenantContext.clear();
        }
    }

    private QuotationStatus statusOf(UUID id) {
        return inTenant(() -> quotations.findById(id).orElseThrow().getStatus());
    }

    private UUID seedSent(LocalDate validUntil) {
        Quotation q = quotations.saveAndFlush(new Quotation(UUID.randomUUID(), null));
        QuotationVersion v = versions.saveAndFlush(new QuotationVersion(q.getId(), 1, "27"));
        v.setHeader(validUntil, null, null, null);
        v.setTotals(BigDecimal.TEN, BigDecimal.ONE, new BigDecimal("11"));
        versions.saveAndFlush(v);
        q.setCurrentVersionId(v.getId());
        q.assignQuoteNo("Q-SWEEP-" + (++seq));
        q.markSent();
        return quotations.saveAndFlush(q).getId();
    }
}
