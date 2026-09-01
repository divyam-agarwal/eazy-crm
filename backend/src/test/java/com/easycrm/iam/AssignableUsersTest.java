package com.easycrm.iam;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easycrm.platform.error.ValidationException;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Extracted from the verbatim copies in EnquiryService and CustomerService. Spec §7.4.
 */
@SpringBootTest
class AssignableUsersTest extends IntegrationTest {

    @Autowired
    AssignableUsers assignableUsers;

    @Autowired
    UserRepository users;

    @Autowired
    TransactionTemplate tx;

    private final UUID tenantId = UUID.randomUUID();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aNullAssigneeIsAllowed() {
        asTenant(() -> assertThatCode(() -> assignableUsers.require(null)).doesNotThrowAnyException());
    }

    @Test
    void anUnknownUserIsRejected() {
        asTenant(() -> assertThatThrownBy(() -> assignableUsers.require(UUID.randomUUID()))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((ValidationException) e).getFields())
                        .containsKey("assignedTo")));
    }

    private void asTenant(Runnable body) {
        TenantContext.runAs(
                new TenantContext.TenantPrincipal(tenantId, UUID.randomUUID(), "OWNER"),
                () -> tx.executeWithoutResult(s -> body.run()));
    }
}
