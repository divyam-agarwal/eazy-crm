package com.easycrm.sales;

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

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The disjointness property of §9, asserted directly: three follow-ups placed on either
 * side of the two boundaries, each scope returning exactly one, and the three counts
 * summing to the pending total. This is what catches a regression to the overlapping
 * definitions the spec rejects.
 */
@SpringBootTest
class FollowUpSpecificationsTest extends IntegrationTest {

    @Autowired FollowUpRepository followUps;
    @Autowired TransactionTemplate tx;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID me = UUID.randomUUID();
    private final UUID subject = UUID.randomUUID();

    // Fixed clock values, not Instant.now(): the boundaries are what is under test.
    private static final Instant NOW = Instant.parse("2026-08-30T06:30:00Z");        // 12:00 IST
    private static final Instant END_OF_TODAY = Instant.parse("2026-08-30T18:30:00Z"); // 00:00 IST +1

    @BeforeEach
    void seed() {
        asTenant(() -> {
            followUps.saveAndFlush(f(NOW.minusSeconds(7200), "overdue"));    // 10:00 IST
            followUps.saveAndFlush(f(NOW.plusSeconds(7200), "due today"));   // 14:00 IST
            followUps.saveAndFlush(f(END_OF_TODAY.plusSeconds(3600), "upcoming"));
        });
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void overdueReturnsOnlyThePastOne() {
        assertThat(notesFor(FollowUpScope.OVERDUE)).containsExactly("overdue");
    }

    @Test
    void dueTodayReturnsOnlyTheRestOfTodayOne() {
        assertThat(notesFor(FollowUpScope.DUE_TODAY)).containsExactly("due today");
    }

    @Test
    void upcomingReturnsOnlyTheLaterOne() {
        assertThat(notesFor(FollowUpScope.UPCOMING)).containsExactly("upcoming");
    }

    @Test
    void theThreeScopesPartitionThePendingSet() {
        int overdue = notesFor(FollowUpScope.OVERDUE).size();
        int dueToday = notesFor(FollowUpScope.DUE_TODAY).size();
        int upcoming = notesFor(FollowUpScope.UPCOMING).size();

        assertThat(overdue + dueToday + upcoming)
            .as("the three scopes must partition PENDING exactly — see FollowUpScope")
            .isEqualTo(notesFor(FollowUpScope.ALL).size());
    }

    @Test
    void aCompletedFollowUpFallsOutOfEveryDueScope() {
        asTenant(() -> {
            FollowUp done = followUps.saveAndFlush(f(NOW.minusSeconds(7200), "finished"));
            done.complete("rang them", NOW);
            followUps.saveAndFlush(done);
        });

        assertThat(notesFor(FollowUpScope.OVERDUE)).containsExactly("overdue");
    }

    private java.util.List<String> notesFor(FollowUpScope scope) {
        return TenantContext.runAs(
            new TenantContext.TenantPrincipal(tenantId, me, "OWNER"),
            () -> tx.execute(s -> followUps.findAll(
                    FollowUpSpecifications.filter(scope, null, null, null, null,
                        NOW, END_OF_TODAY),
                    PageRequest.of(0, 50))
                .getContent().stream().map(FollowUp::getNote).toList()));
    }

    private FollowUp f(Instant dueAt, String note) {
        return new FollowUp(SubjectType.ENQUIRY, subject, dueAt, me, note, me);
    }

    private void asTenant(Runnable body) {
        TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, me, "OWNER"),
            () -> tx.executeWithoutResult(s -> body.run()));
    }
}
