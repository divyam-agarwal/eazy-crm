package com.easycrm.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

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
            "org.springframework.data.jpa.repository.JpaSpecificationExecutor",
            "org.springframework.data.repository.query.QueryByExampleExecutor",
            "org.springframework.data.querydsl.QuerydslPredicateExecutor");

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
                        + "declared here and so escape the declared-method rule below — see spec §4.2. "
                        + "This also covers mixing in QueryByExampleExecutor or QuerydslPredicateExecutor "
                        + "alongside the bare marker: each brings its own unscoped read "
                        + "(findAll(Example)/findOne(Example) or findAll(Predicate)) that the "
                        + "declared-method rule never sees, because the read is inherited, not declared, "
                        + "and the primary supertype can still be the bare marker while this is true")
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
                    .as(
                            "ActivityRepository.%s must take a SubjectType — an activity read that "
                                    + "does not name a subject bypasses VisibleFinder.requireVisibleSubject "
                                    + "entirely (spec §4.2)",
                            method.getName())
                    .contains(SUBJECT_TYPE);

            assertThat(params)
                    .as("ActivityRepository.%s must also take a subject id", method.getName())
                    .contains("java.util.UUID");
        }
    }

    /**
     * Classes allowed to call ActivityRepository directly. This is an ALLOWLIST on
     * purpose, mirroring VisibilityScopingArchTest.ALLOWED_METHODS: a blocklist could never
     * anticipate the next service that decides to inject the repository itself. The other
     * two tests in this class prove ActivityRepository cannot expose an unscoped read
     * METHOD; this one closes the remaining gap, which is a caller bypassing
     * VisibleFinder.requireVisibleSubject entirely by injecting the repository and calling
     * a properly-subject-scoped method with a request-supplied (unchecked) id. Adding a
     * name here is a visibility decision and needs the same review as adding a table to
     * TenantScopingArchTest.GLOBAL_TABLES or a repository to
     * VisibilityScopingArchTest.GUARDED_REPOSITORIES. See spec
     * 2026-08-30-activity-follow-up-design.md §4.2.
     */
    private static final Set<String> ALLOWED_CALLERS = Set.of("com.easycrm.sales.ActivityService");

    @Test
    void onlyActivityServiceMayCallActivityRepository() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.easycrm");

        ArchRule rule = noClasses()
                .that(DescribedPredicate.describe(
                        "not in the ActivityRepository caller allowlist",
                        clazz -> !ALLOWED_CALLERS.contains(clazz.getFullName())))
                .should(callActivityRepository())
                .because("ActivityRepository's declared methods are subject-scoped, but only "
                        + "because every call site is trusted to have already resolved that "
                        + "subject through VisibleFinder.requireVisibleSubject; a caller outside "
                        + "ActivityService could pass a request-supplied id straight through and "
                        + "read another user's activity log unchecked");

        rule.check(classes);
    }

    private static ArchCondition<JavaClass> callActivityRepository() {
        return new ArchCondition<>("call ActivityRepository") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                // As in VisibilityScopingArchTest: a `repo::method` reference is tracked
                // separately from a `repo.method()` call, and both must be checked, or a
                // future `.map(activities::findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc)`
                // would bypass this guard silently.
                checkAccesses(item, events, item.getMethodCallsFromSelf());
                checkAccesses(item, events, item.getMethodReferencesFromSelf());
            }

            private void checkAccesses(JavaClass item, ConditionEvents events, Set<? extends JavaAccess<?>> accesses) {
                for (JavaAccess<?> call : accesses) {
                    if (!call.getTargetOwner().getFullName().equals(REPOSITORY)) continue;
                    events.add(SimpleConditionEvent.satisfied(item, call.getDescription()));
                }
            }
        };
    }
}
