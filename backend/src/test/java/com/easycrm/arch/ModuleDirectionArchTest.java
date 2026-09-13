package com.easycrm.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * H4 — {@code platform} is the ONE shared library every future service takes, so it must depend
 * on no domain package. Today {@code platform -> sales(12) tenant(3) crm(3)}, which means
 * extracting any service would drag {@code sales.Quotation} and {@code sales.Order} along with
 * the shared library (MF1). See spec 2026-09-13-wave-1.6-module-boundaries-design.md §3.4.
 *
 * <p><b>This rule exists because Spring Modulith's {@code verify()} cannot replace it.</b>
 * {@code platform} is declared an OPEN module (M4), and OPEN suppresses every cycle that routes
 * through the open module — all 12 of them, measured. With {@code platform} OPEN, {@code verify()}
 * reports ZERO violations on the broken graph. Isolated empirically: with {@code platform} still
 * OPEN, a planted {@code catalog -> sales} cycle IS caught, so the suppression is specific to the
 * open module rather than general. See challenge #75 and ModularityTest, which names this class as
 * what covers its blind spot.
 *
 * <p>{@code dependOnClassesThat} is deliberately broader than imports: it also counts field,
 * parameter and return types, annotations and generic parameters. An import-only rule would miss
 * a dependency introduced by constructor injection alone.
 */
class ModuleDirectionArchTest {

    /**
     * The five domain packages. {@code demo} is deliberately absent: it is not a service and S9
     * says to delete it, so forbidding it here would invent a rule about code that should not
     * exist. {@code platform} does not currently depend on it either way.
     */
    private static final String[] DOMAIN_PACKAGES = {
        "com.easycrm.crm..", "com.easycrm.sales..", "com.easycrm.catalog..", "com.easycrm.tenant..", "com.easycrm.iam.."
    };

    private static JavaClasses importedClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.easycrm");
    }

    @Test
    void platformDependsOnNoDomainPackage() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("com.easycrm.platform..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(DOMAIN_PACKAGES)
                .because("platform is the single shared library every service takes, so a "
                        + "dependency on a domain package makes that domain inseparable from all "
                        + "five services (H4/MF1)");

        rule.check(importedClasses());
    }

    /**
     * Non-vacuity. A rule that imported nothing would pass, and this repo has been bitten by
     * exactly that twice — ArchUnit 1.3.0 skipping Java 25 bytecode, and challenge #72's CI check
     * keyed on a YAML value that parsed as a boolean. The floor is far below the ~209 main classes
     * the app has, because pinning the exact number would just get bumped without being read.
     */
    @Test
    void theImportIsNotVacuous() {
        assertThat(importedClasses().size())
                .as("ArchUnit imported almost nothing; every rule in this class would pass vacuously")
                .isGreaterThan(150);
    }
}
