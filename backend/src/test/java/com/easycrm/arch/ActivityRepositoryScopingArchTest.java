package com.easycrm.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The structural half of the activity visibility gate. See spec
 * 2026-08-30-activity-follow-up-design.md §4.2 and §8.
 *
 * <p>Assertion 1 is the load-bearing one and assertion 2 is worthless without it: if the
 * interface extended JpaRepository, findById/findAll would be INHERITED rather than
 * declared, so a rule over declared methods would pass a service that reads an activity
 * with no subject in hand.
 */
class ActivityRepositoryScopingArchTest {

    private static final String REPOSITORY = "com.easycrm.sales.ActivityRepository";
    private static final String SUBJECT_TYPE = "com.easycrm.platform.visibility.SubjectType";

    /** Supertypes that would silently reintroduce unscoped reads by inheritance. */
    private static final Set<String> FORBIDDEN_SUPERTYPES = Set.of(
        "org.springframework.data.repository.CrudRepository",
        "org.springframework.data.repository.ListCrudRepository",
        "org.springframework.data.repository.PagingAndSortingRepository",
        "org.springframework.data.repository.ListPagingAndSortingRepository",
        "org.springframework.data.jpa.repository.JpaRepository",
        "org.springframework.data.jpa.repository.JpaSpecificationExecutor");

    private JavaClass activityRepository() {
        JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.easycrm");
        return classes.get(REPOSITORY);
    }

    @Test
    void inheritsNoUnscopedReadMethods() {
        List<String> supertypes = activityRepository().getAllRawInterfaces().stream()
            .map(JavaClass::getFullName)
            .toList();

        assertThat(supertypes)
            .as("ActivityRepository must extend the bare Repository marker. Extending "
              + "JpaRepository (or any of these) inherits findById/findAll, which are not "
              + "declared here and so escape the declared-method rule below — see spec §4.2")
            .doesNotContainAnyElementsOf(FORBIDDEN_SUPERTYPES)
            .contains("org.springframework.data.repository.Repository");
    }

    @Test
    void declaresNoReadThatIsNotScopedToASubject() {
        for (JavaMethod method : activityRepository().getMethods()) {
            if (method.getName().equals("save")) continue;

            List<String> params = method.getRawParameterTypes().stream()
                .map(JavaClass::getFullName)
                .toList();

            assertThat(params)
                .as("ActivityRepository.%s must take a SubjectType — an activity read that "
                  + "does not name a subject bypasses VisibleFinder.requireVisibleSubject "
                  + "entirely (spec §4.2)", method.getName())
                .contains(SUBJECT_TYPE);

            assertThat(params)
                .as("ActivityRepository.%s must also take a subject id", method.getName())
                .contains("java.util.UUID");
        }
    }
}
