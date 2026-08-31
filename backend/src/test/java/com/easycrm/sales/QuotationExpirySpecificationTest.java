package com.easycrm.sales;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.platform.visibility.VisibleFinder;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the candidate query selects exactly the quotations that should auto-expire.
 * Integration rather than unit because the EXISTS subquery against QuotationVersion only
 * exists once Hibernate builds SQL for it. See spec 2026-08-31 §4.
 */
@SpringBootTest
class QuotationExpirySpecificationTest extends IntegrationTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Autowired QuotationRepository quotations;
    @Autowired QuotationVersionRepository versions;
    @Autowired VisibleFinder finder;
    @Autowired TransactionTemplate tx;

    private final UUID tenantId = UUID.randomUUID();
    private int seq = 0;

    private UUID lapsedSent, todaySent, openEndedSent, lapsedDraft, lapsedAccepted,
                 lapsedRejected, lapsedAlreadyExpired;
    private UUID supersededLapsed;

    @BeforeEach
    void seed() {
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, null, "SYSTEM"));
        tx.execute(s -> {
            lapsedSent           = seed(AS_OF.minusDays(1), QuotationStatus.SENT);
            todaySent            = seed(AS_OF,              QuotationStatus.SENT);
            openEndedSent        = seed(null,               QuotationStatus.SENT);
            lapsedDraft          = seed(AS_OF.minusDays(1), QuotationStatus.DRAFT);
            lapsedAccepted       = seed(AS_OF.minusDays(1), QuotationStatus.ACCEPTED);
            lapsedRejected       = seed(AS_OF.minusDays(1), QuotationStatus.REJECTED);
            lapsedAlreadyExpired = seed(AS_OF.minusDays(1), QuotationStatus.EXPIRED);
            supersededLapsed = seedTwoVersions(AS_OF.minusDays(10), AS_OF.plusDays(7));
            return null;
        });
        TenantContext.clear();
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void selectsOnlyTheSentQuotationWhoseValidUntilHasLapsed() {
        assertThat(idsOfCandidates()).containsExactly(lapsedSent);
    }

    @Test
    void doesNotSelectAQuotationValidThroughToday() {
        assertThat(idsOfCandidates()).doesNotContain(todaySent);
    }

    @Test
    void doesNotSelectAnOpenEndedQuotation() {
        assertThat(idsOfCandidates()).doesNotContain(openEndedSent);
    }

    @Test
    void doesNotSelectNonSentStatusesHoweverStaleTheirDate() {
        assertThat(idsOfCandidates())
            .doesNotContain(lapsedDraft, lapsedAccepted, lapsedRejected, lapsedAlreadyExpired);
    }

    @Test
    void doesNotSelectAQuotationWhoseLapsedVersionHasBeenSuperseded() {
        assertThat(idsOfCandidates()).doesNotContain(supersededLapsed);
    }

    private List<UUID> idsOfCandidates() {
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, null, "SYSTEM"));
        try {
            return tx.execute(s -> finder
                .listQuotations(QuotationSpecifications.expirableAsOf(AS_OF))
                .stream().map(Quotation::getId).toList());
        } finally {
            TenantContext.clear();
        }
    }

    /** A quotation with one current version carrying the given validUntil, in the given status. */
    private UUID seed(LocalDate validUntil, QuotationStatus status) {
        Quotation q = quotations.saveAndFlush(new Quotation(UUID.randomUUID(), null));
        QuotationVersion v = versions.saveAndFlush(new QuotationVersion(q.getId(), 1, "27"));
        v.setHeader(validUntil, null, null, null);
        v.setTotals(BigDecimal.TEN, BigDecimal.ONE, new BigDecimal("11"));
        versions.saveAndFlush(v);
        q.setCurrentVersionId(v.getId());
        q.assignQuoteNo("Q-EXP-" + (++seq));
        // Walk the real lifecycle rather than setting the field: markSent() is the only
        // door into SENT, and the terminal statuses are only reachable through it.
        q.markSent();
        switch (status) {
            case DRAFT    -> q.reviseToDraft();
            case ACCEPTED -> q.markAccepted();
            case REJECTED -> q.reject();
            case EXPIRED  -> q.expire();
            case SENT     -> { }
        }
        return quotations.saveAndFlush(q).getId();
    }

    /**
     * A SENT quotation whose CURRENT version is still open, but whose SUPERSEDED first
     * version lapsed long ago. This is the fixture that discriminates: it is selected only
     * by an implementation that correlates on the quotation rather than on
     * currentVersionId.
     */
    private UUID seedTwoVersions(LocalDate supersededValidUntil, LocalDate currentValidUntil) {
        Quotation q = quotations.saveAndFlush(new Quotation(UUID.randomUUID(), null));
        QuotationVersion v1 = versions.saveAndFlush(new QuotationVersion(q.getId(), 1, "27"));
        v1.setHeader(supersededValidUntil, null, null, null);
        v1.setTotals(BigDecimal.TEN, BigDecimal.ONE, new BigDecimal("11"));
        versions.saveAndFlush(v1);
        QuotationVersion v2 = versions.saveAndFlush(new QuotationVersion(q.getId(), 2, "27"));
        v2.setHeader(currentValidUntil, null, null, null);
        v2.setTotals(BigDecimal.TEN, BigDecimal.ONE, new BigDecimal("11"));
        versions.saveAndFlush(v2);
        q.setCurrentVersionId(v2.getId());
        q.assignQuoteNo("Q-EXP-" + (++seq));
        q.markSent();
        return quotations.saveAndFlush(q).getId();
    }
}
