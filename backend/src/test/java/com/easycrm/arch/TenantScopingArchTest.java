package com.easycrm.arch;

import com.easycrm.platform.persistence.TenantScopedEntity;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

class TenantScopingArchTest {

    /** Global tables are intentionally NOT tenant-scoped. Add here only with review. */
    private static final Set<String> GLOBAL_TABLES = Set.of(
        "com.easycrm.tenant.Tenant"
    );

    @Test
    void everyEntityIsTenantScopedUnlessAllowlisted() {
        JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.easycrm");

        ArchRule rule = classes()
            .that().areAnnotatedWith(Entity.class)
            .and().haveNameNotMatching(escaped(GLOBAL_TABLES))
            .should().beAssignableTo(TenantScopedEntity.class)
            .because("every tenant-scoped @Entity must declare @TenantId via TenantScopedEntity; "
                   + "global tables must be added to GLOBAL_TABLES with explicit review");

        rule.check(classes);
    }

    private static String escaped(Set<String> names) {
        return names.stream().map(java.util.regex.Pattern::quote)
            .reduce((a, b) -> a + "|" + b).map(s -> "(" + s + ")").orElse("(?!)");
    }
}
