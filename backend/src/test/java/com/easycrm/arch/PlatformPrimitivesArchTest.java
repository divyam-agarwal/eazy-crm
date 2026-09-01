package com.easycrm.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvent;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * R1 — a JSON mapper built anywhere but platform-primitives loses BigDecimalStringModule, and money
 * reaches SNS as an IEEE-754 double with nothing thrown and nothing logged (TB3). Use
 * EventJson.mapper() for anything persisted or published, or inject Boot's for HTTP.
 *
 * <p>The condition is hand-written rather than expressed with callMethod(Class, String, Class...):
 * JsonMapper.builder() is overloaded, so a signature-based rule would silently cover only one
 * overload — the exact shape of vacuous pass this codebase has already been bitten by once.
 *
 * <p><b>Why the condition reports the offending call as {@code satisfied}, not {@code violated}:</b>
 * {@code noClasses().should(condition)} wraps the given condition in a {@code NeverCondition}
 * (see {@code ArchRuleDefinition.negateCondition()} in the 1.4.1 sources), whose {@code check}
 * delegates through an {@code InvertingConditionEvents} that calls {@link ConditionEvent#invert()}
 * on every event the wrapped condition adds — flipping {@code SimpleConditionEvent}'s
 * {@code conditionSatisfied} flag before deciding what counts as a rule violation. A condition
 * that emits {@code violated(...)} for the offending class is therefore inverted into a passing
 * event and the rule never fails — vacuously green in exactly the way this task exists to prevent.
 * Emitting {@code satisfied(...)} for the offending call is what survives that inversion as a
 * reported violation. This was verified empirically — evaluating the rule directly with both
 * event polarities against a deliberately introduced violation, before trusting either one — and
 * is re-verified by the deliberate-violation step recorded in the task's own report, not by
 * anything in this file.
 */
class PlatformPrimitivesArchTest {

    /** Types whose construction re-introduces TB3. */
    private static final Set<String> MAPPER_TYPES = Set.of(
            "tools.jackson.databind.ObjectMapper",
            "tools.jackson.databind.json.JsonMapper",
            "tools.jackson.databind.json.JsonMapper$Builder");

    private static JavaClasses appClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.easycrm");
    }

    @Test
    void theImportIsNotVacuous() {
        JavaClasses classes = appClasses();
        assertThat(classes).as("imported classes").isNotEmpty();
        // isNotEmpty() alone is not sufficient here: this test's classpath also carries the
        // platform-primitives jar (backend/build.gradle.kts), whose classes live under
        // com.easycrm.. too. If the root project's own bytecode stopped being imported - the
        // exact ArchUnit 1.3.0/Java 25 failure this codebase already suffered once - this
        // assertion would still pass on the jar's ten classes alone, and the rule below would
        // go vacuously green while checking nothing that matters. EasyCrmApplication only
        // exists in this project's own source, never in the primitives jar, so its presence is
        // proof this project's bytecode specifically was imported.
        assertThat(classes.contain("com.easycrm.EasyCrmApplication"))
                .as("root project's own bytecode (not just the platform-primitives jar also on "
                        + "this classpath) was imported")
                .isTrue();
    }

    @Test
    void noOneOutsidePlatformPrimitivesConstructsAJsonMapper() {
        ArchRule rule = noClasses()
                .that()
                .resideOutsideOfPackage("com.easycrm.platform.money..")
                .should(constructAJsonMapper())
                .because("a mapper built elsewhere loses BigDecimalStringModule and sends money as a "
                        + "JSON number (TB3). Use EventJson.mapper(), or inject Boot's ObjectMapper");

        rule.check(appClasses());
    }

    private static ArchCondition<JavaClass> constructAJsonMapper() {
        return new ArchCondition<>("construct a JSON mapper") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaCodeUnit unit : item.getCodeUnits()) {
                    for (JavaMethodCall call : unit.getMethodCallsFromSelf()) {
                        if (MAPPER_TYPES.contains(call.getTargetOwner().getFullName())
                                && call.getName().equals("builder")) {
                            // satisfied(), not violated() — see the class comment: noClasses()
                            // inverts every event, so this is what surfaces as a violation.
                            events.add(SimpleConditionEvent.satisfied(item, call.getDescription()));
                        }
                    }
                    for (JavaConstructorCall call : unit.getConstructorCallsFromSelf()) {
                        if (MAPPER_TYPES.contains(call.getTargetOwner().getFullName())) {
                            // satisfied(), not violated() — same reason as the method-call branch
                            // above: noClasses() inverts every event this condition emits, so this
                            // is what surfaces as a reported violation. Do not "tidy" this to
                            // violated() without re-reading the class Javadoc.
                            events.add(SimpleConditionEvent.satisfied(item, call.getDescription()));
                        }
                    }
                }
            }
        };
    }
}
