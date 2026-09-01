package com.easycrm.platform;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * R2 — platform-primitives is the bottom of the DAG. A dependency edge out of it means something
 * has been placed wrong.
 *
 * <p>This test runs in the subproject, so the classpath it imports contains only this module's own
 * classes. That is deliberate: com.easycrm.platform.error is a split package — ApiExceptionHandler
 * stays in the application until platform-web exists — so a rule scoped by package name would try
 * to judge a class that is not part of this module. Scoping by classpath cannot be widened by
 * editing a filter.
 */
class PrimitivesModuleArchTest {

    private static JavaClasses moduleClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.easycrm");
    }

    @Test
    void theImportIsNotVacuous() {
        // ArchUnit 1.3.0 silently imported zero classes on Java 25 bytecode and passed every rule.
        // Never trust a green rule without this assertion.
        assertThat(moduleClasses()).as("imported classes").isNotEmpty();
    }

    @Test
    void onlyDependsOnItsOwnPackagesJacksonAndSpring() {
        // A closure (allowlist), not an enumeration of forbidden packages: an enumeration only
        // catches the specific edges someone thought to list (a future com.easycrm.reporting..,
        // or a Hibernate edge, would pass silently), while a closure fails on anything not
        // explicitly permitted. The allowed set below was derived empirically — evaluated against
        // this module's actual classes, not guessed — and is exactly what this module touches
        // today:
        //   java..                                  - JDK types throughout
        //   tools.jackson..                         - the mapper and its serializer (EventJson,
        //                                             BigDecimalStringModule)
        //   com.fasterxml.jackson.annotation..       - JsonInclude, used by EventJson directly
        //                                             (Jackson 3 kept this annotation package
        //                                             under the old com.fasterxml coordinates)
        //   com.easycrm.platform.error..             - this module's own exception vocabulary
        //   com.easycrm.platform.money..             - this module's own money wire format
        //   com.easycrm.platform.gst..               - this module's own GST value types
        //   org.springframework..                    - MoneyAutoConfiguration only; the separate
        //                                             carriesNoRuntimeSpringDependency test below
        //                                             is what actually confines Spring usage to
        //                                             the auto-configuration, so nothing is lost
        //                                             by allowing it here too
        // Confirmed by evaluating this rule with each entry removed one at a time: all six
        // entries other than gst.. are load-bearing today (removing any one produces real,
        // named violations). gst.. is kept regardless because it is this module's own package —
        // Gstin and StateCode currently don't reference each other or get referenced
        // cross-package from within this module, so it is not load-bearing *today*, but the rule
        // exists to police dependencies leaving the module, not incidental gaps in its current
        // internal call graph.
        ArchRule rule = classes()
                .should()
                .onlyDependOnClassesThat()
                .resideInAnyPackage(
                        "java..",
                        "tools.jackson..",
                        "com.fasterxml.jackson.annotation..",
                        "com.easycrm.platform.error..",
                        "com.easycrm.platform.money..",
                        "com.easycrm.platform.gst..",
                        "org.springframework..")
                .because("platform-primitives is the bottom of the DAG. An edge out of the allowed "
                        + "set is how P4's error happened — Gstin importing ValidationException out of "
                        + "platform-web would have dragged the servlet stack into notification-svc — "
                        + "and an enumeration of forbidden packages can't catch an edge nobody thought "
                        + "to list, only a closure can");

        rule.check(moduleClasses());
    }

    @Test
    void carriesNoRuntimeSpringDependency() {
        // spring-context and spring-boot-autoconfigure are compileOnly so notification-svc can
        // take this jar without inheriting a servlet stack. Only the auto-configuration may name
        // Spring at all, and it is inert when Spring is absent.
        ArchRule rule = noClasses()
                .that()
                .haveSimpleNameNotEndingWith("AutoConfiguration")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework..")
                .because("every type here must work with no Spring on the classpath");

        rule.check(moduleClasses());
    }
}
