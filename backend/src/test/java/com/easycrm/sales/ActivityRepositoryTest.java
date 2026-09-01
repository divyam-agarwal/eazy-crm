package com.easycrm.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.platform.visibility.SubjectType;
import com.easycrm.support.IntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class ActivityRepositoryTest extends IntegrationTest {

    @Autowired
    ActivityRepository activities;

    @Autowired
    TransactionTemplate tx;

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final UUID subject = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void readsBackTheSubjectTimelineNewestFirst() {
        Instant now = Instant.now();
        asTenant(tenantA, () -> {
            activities.save(Activity.manual(
                    SubjectType.ENQUIRY, subject, ActivityType.CALL, "older", null, now.minusSeconds(7200), user, now));
            activities.save(Activity.manual(
                    SubjectType.ENQUIRY, subject, ActivityType.CALL, "newer", null, now.minusSeconds(60), user, now));
        });

        asTenant(
                tenantA,
                () -> assertThat(activities
                                .findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(
                                        SubjectType.ENQUIRY, subject, PageRequest.of(0, 10))
                                .getContent())
                        .extracting(Activity::getBody)
                        .containsExactly("newer", "older"));
    }

    @Test
    void anotherTenantSeesNothing() {
        Instant now = Instant.now();
        asTenant(
                tenantA,
                () -> activities.save(Activity.manual(
                        SubjectType.ENQUIRY, subject, ActivityType.CALL, "tenant A only", null, now, user, now)));

        asTenant(
                tenantB,
                () -> assertThat(activities
                                .findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(
                                        SubjectType.ENQUIRY, subject, PageRequest.of(0, 10))
                                .getContent())
                        .isEmpty());
    }

    @Test
    void findByIdIsScopedToTheSubjectItWasFiledUnder() {
        Instant now = Instant.now();
        UUID id = asTenantReturning(
                tenantA,
                () -> activities
                        .save(Activity.manual(
                                SubjectType.ENQUIRY, subject, ActivityType.NOTE, "note", null, now, user, now))
                        .getId());

        asTenant(tenantA, () -> {
            assertThat(activities.findByIdAndSubjectTypeAndSubjectId(id, SubjectType.ENQUIRY, subject))
                    .isPresent();
            // Right id, wrong subject -> nothing. This is what makes a by-id-alone
            // lookup unnecessary (spec §9).
            assertThat(activities.findByIdAndSubjectTypeAndSubjectId(id, SubjectType.ENQUIRY, UUID.randomUUID()))
                    .isEmpty();
            assertThat(activities.findByIdAndSubjectTypeAndSubjectId(id, SubjectType.CUSTOMER, subject))
                    .isEmpty();
        });
    }

    private void asTenant(UUID tenantId, Runnable body) {
        TenantContext.runAs(
                new TenantContext.TenantPrincipal(tenantId, user, "OWNER"),
                () -> tx.executeWithoutResult(s -> body.run()));
    }

    private <T> T asTenantReturning(UUID tenantId, java.util.function.Supplier<T> body) {
        return TenantContext.runAs(
                new TenantContext.TenantPrincipal(tenantId, user, "OWNER"), () -> tx.execute(s -> body.get()));
    }
}
