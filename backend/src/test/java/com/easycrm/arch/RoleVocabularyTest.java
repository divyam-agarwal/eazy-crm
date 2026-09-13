package com.easycrm.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.easycrm.iam.Role;
import org.junit.jupiter.api.Test;

/**
 * Fails when a role is added or removed, which is the point. H6's SALES_MANAGER tier means editing
 * the restriction logic in BOTH interpreters (crm.CustomerVisibility and sales.SalesVisibility) and
 * flipping SALES_MANAGER's case in VisibilityContract. A new enum constant landing silently, with
 * the two interpreters left treating it as unrestricted, is exactly how that gets missed — and it
 * would fail OPEN.
 */
class RoleVocabularyTest {

    @Test
    void theRoleVocabularyIsTheExpectedThree() {
        assertThat(Role.values())
                .as("a role was added or removed; revisit CustomerVisibility, SalesVisibility and "
                        + "VisibilityContract before updating this assertion")
                .containsExactly(Role.OWNER, Role.SALES_MANAGER, Role.SALES_EXEC);
    }
}
