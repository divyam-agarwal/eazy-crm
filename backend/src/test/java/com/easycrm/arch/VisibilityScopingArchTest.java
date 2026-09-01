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
import java.util.Set;
import org.junit.jupiter.api.Test;

class VisibilityScopingArchTest {

    /** Repositories whose rows are subject to intra-tenant visibility filtering. */
    private static final Set<String> GUARDED_REPOSITORIES = Set.of(
            "com.easycrm.crm.CustomerRepository",
            "com.easycrm.sales.EnquiryRepository",
            "com.easycrm.sales.QuotationRepository",
            "com.easycrm.sales.OrderRepository",
            "com.easycrm.sales.FollowUpRepository");

    /**
     * Methods any class may still call on a guarded repository. Everything else must go
     * through VisibleFinder.
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
            "findByQuotationId");

    @Test
    void onlyTheVisibilityPackageMayReadAGuardedRepository() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.easycrm");

        ArchRule rule = noClasses()
                .that()
                .resideOutsideOfPackage("com.easycrm.platform.visibility..")
                .should(callAGuardedRepositoryOutsideTheAllowlist())
                .because("intra-tenant visibility is applied in VisibleFinder; a read that "
                        + "bypasses it silently returns another user's records");

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
                // empirically while writing this fix. Widening GUARDED_REPOSITORIES to
                // include CrudRepository/JpaRepository would catch it but would also flag
                // every unguarded repository's method references across the whole app.
                checkAccesses(item, events, item.getMethodCallsFromSelf());
                checkAccesses(item, events, item.getMethodReferencesFromSelf());
            }

            private void checkAccesses(JavaClass item, ConditionEvents events, Set<? extends JavaAccess<?>> accesses) {
                for (JavaAccess<?> call : accesses) {
                    String owner = call.getTargetOwner().getFullName();
                    if (!GUARDED_REPOSITORIES.contains(owner)) continue;
                    if (ALLOWED_METHODS.contains(call.getName())) continue;
                    events.add(SimpleConditionEvent.satisfied(item, call.getDescription()));
                }
            }
        };
    }
}
