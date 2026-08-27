package com.easycrm.platform;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

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
    void dependsOnNoOtherPlatformModuleAndNoServicePackage() {
        ArchRule rule = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.easycrm.platform.web..",
                "com.easycrm.platform.tenancy..",
                "com.easycrm.platform.security..",
                "com.easycrm.platform.persistence..",
                "com.easycrm.platform.pdf..",
                "com.easycrm.platform.format..",
                "com.easycrm.catalog..",
                "com.easycrm.crm..",
                "com.easycrm.sales..",
                "com.easycrm.iam..",
                "com.easycrm.tenant..",
                "com.easycrm.demo..")
            .because("platform-primitives is the bottom of the DAG. An edge out of it is how P4's "
                   + "error happened — Gstin importing ValidationException out of platform-web "
                   + "would have dragged the servlet stack into notification-svc");

        rule.check(moduleClasses());
    }

    @Test
    void carriesNoRuntimeSpringDependency() {
        // spring-context and spring-boot-autoconfigure are compileOnly so notification-svc can
        // take this jar without inheriting a servlet stack. Only the auto-configuration may name
        // Spring at all, and it is inert when Spring is absent.
        ArchRule rule = noClasses()
            .that().haveSimpleNameNotEndingWith("AutoConfiguration")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
            .because("every type here must work with no Spring on the classpath");

        rule.check(moduleClasses());
    }
}
