package com.easycrm.sales;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.platform.visibility.SubjectType;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §6.3. The parent design spec's claim about the event seam is that new behaviour
 * arrives as a new SUBSCRIBER rather than an edit to QuotationService — this is the first
 * time that claim is tested by someone other than its author.
 */
@SpringBootTest
class QuotationAcceptedActivityTest extends IntegrationTest {

    @Autowired ApplicationEventPublisher events;
    @Autowired ActivityRepository activities;
    @Autowired TransactionTemplate tx;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID quotationId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void acceptingAQuotationLogsExactlyOneSystemActivityAgainstIt() {
        TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, actorId, "OWNER"),
            () -> tx.executeWithoutResult(s -> events.publishEvent(new QuotationAcceptedEvent(
                quotationId, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("11800.00"), "SO/25-26/0007", actorId))));

        var logged = TenantContext.runAs(
            new TenantContext.TenantPrincipal(tenantId, actorId, "OWNER"),
            () -> tx.execute(s -> activities
                .findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(
                    SubjectType.QUOTATION, quotationId, PageRequest.of(0, 10))
                .getContent()));

        assertThat(logged).hasSize(1);
        assertThat(logged.get(0).getSource()).isEqualTo(ActivitySource.SYSTEM);
        assertThat(logged.get(0).getType()).isEqualTo(ActivityType.NOTE);
        assertThat(logged.get(0).getLoggedBy()).isEqualTo(actorId);
        assertThat(logged.get(0).getBody()).contains("SO/25-26/0007");
    }
}
