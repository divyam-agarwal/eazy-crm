package com.easycrm.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VisibilityScopingArchTest {

    /**
     * Each visibility-scoped repository and the ONE class permitted to read it.
     *
     * <p>This was a single package rule — "only a class in the platform module's visibility
     * package may read a guarded repository" — until Wave 1.6 deleted that package. The reads now
     * live with the data
     * they filter, so the rule is stated per repository instead. <b>That is a tightening, not a
     * weakening:</b> it previously said "some class in one package", and it now names exactly one
     * class per table. The invariant was never "one class for everything" — it was "no read
     * bypasses the policy" — and this states that per table.
     *
     * <p>Adding an entry here is a visibility decision and needs the same review as adding a table
     * to TenantScopingArchTest.GLOBAL_TABLES. See spec
     * 2026-09-13-wave-1.6-module-boundaries-design.md §4.4.
     */
    private static final Map<String, String> PERMITTED_READER = Map.of(
            "com.easycrm.crm.CustomerRepository", "com.easycrm.crm.CustomerVisibility",
            "com.easycrm.sales.EnquiryRepository", "com.easycrm.sales.SalesVisibility",
            "com.easycrm.sales.QuotationRepository", "com.easycrm.sales.SalesVisibility",
            "com.easycrm.sales.OrderRepository", "com.easycrm.sales.SalesVisibility",
            "com.easycrm.sales.FollowUpRepository", "com.easycrm.sales.SalesVisibility");

    /**
     * Methods any class may still call on a guarded repository. Everything else must go
     * through that repository's one permitted reader.
     *
     * <p>This is an ALLOWLIST on purpose. A blocklist of known read methods (findById,
     * findAll, ...) would silently pass a derived query added later -- the exact failure
     * this guard exists to prevent. Adding a name here is a visibility decision and needs
     * the same review as adding a table to TenantScopingArchTest.GLOBAL_TABLES.
     * See spec 2026-08-29-record-visibility-design.md §6, §6.1, §8.
     */
    private static final Set<String> ALLOWED_METHODS = Set.of(
            // Writes, not reads.
            "save",
            // Uniqueness pre-check: must see the whole tenant or the invariant breaks (§6).
            "findByGstin",
            // Dedupe pre-check: same reasoning (§6).
            "findByNormalizedPhone",
            // Reached only from an already-checked quotation; a quotation and its order derive
            // visibility from the SAME customer, so filtering it is a provable no-op (§6.1).
            "findByQuotationId",
            // Invariant checks, not user-facing reads: MemberService refuses to disable a
            // member who still holds open work, and a count that hid rows would let the
            // disable through while work remained assigned to them. Same "must see the whole
            // tenant" reasoning as findByGstin above. See AssignedWorkload and spec
            // 2026-09-01-members-management-design.md §4.
            "countByAssignedToAndActiveTrue",
            "countByAssignedToAndStageIn",
            "countByAssignedToAndStatus");

    @Test
    void onlyThePermittedReaderMayReadEachGuardedRepository() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.easycrm");

        ArchRule rule = noClasses()
                .should(callAGuardedRepositoryOutsideTheAllowlist())
                .because("intra-tenant visibility is applied by exactly one permitted reader per "
                        + "guarded repository (PERMITTED_READER); a read from any other class "
                        + "bypasses it and silently returns another user's records");

        rule.check(classes);
    }

    private static ArchCondition<JavaClass> callAGuardedRepositoryOutsideTheAllowlist() {
        return new ArchCondition<>("call a guarded repository outside the allowlist") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                // ArchUnit tracks a `repo::method` method reference separately from a
                // `repo.method()` call -- both are reads of the same repository and must
                // be checked, or a future `.map(customers::findByGstin)` (or any other
                // custom finder declared directly on a guarded repository) would bypass
                // this guard silently. NOTE: a reference to an INHERITED CrudRepository
                // method (`customers::findById`, `::findAll`, `::save`, ...) resolves its
                // target owner to the Spring Data supertype, not to the local repository
                // interface, so it is NOT caught by this or any owner-name check -- verified
                // empirically while writing this fix. Widening PERMITTED_READER's keys to
                // include CrudRepository/JpaRepository would catch it but would also flag
                // every unguarded repository's method references across the whole app.
                checkAccesses(item, events, item.getMethodCallsFromSelf());
                checkAccesses(item, events, item.getMethodReferencesFromSelf());
            }

            private void checkAccesses(JavaClass item, ConditionEvents events, Set<? extends JavaAccess<?>> accesses) {
                for (JavaAccess<?> call : accesses) {
                    String owner = call.getTargetOwner().getFullName();
                    String permitted = PERMITTED_READER.get(owner);
                    if (permitted == null) continue; // not a guarded repository
                    if (permitted.equals(item.getFullName())) continue; // the one permitted reader
                    if (ALLOWED_METHODS.contains(call.getName())) continue;
                    events.add(SimpleConditionEvent.satisfied(item, call.getDescription()));
                }
            }
        };
    }
}
