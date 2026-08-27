package com.easycrm.arch;

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
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

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
 * {@code noClasses().should(condition)} evaluates by inverting every {@link ConditionEvent} the
 * condition emits (confirmed against the 1.4.1 jar: {@code SimpleConditionEvent.invert()} flips
 * {@code conditionSatisfied}) before deciding what counts as a rule violation. A condition that
 * emits {@code violated(...)} for the offending class is therefore inverted into a passing event
 * and the rule never fails — vacuously green in exactly the way this task exists to prevent. Emitting
 * {@code satisfied(...)} for the offending call is what survives that inversion as a reported
 * violation. This was verified empirically (Step 6 below) before being trusted.
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
        assertThat(appClasses()).as("imported classes").isNotEmpty();
    }

    @Test
    void noOneOutsidePlatformPrimitivesConstructsAJsonMapper() {
        ArchRule rule = noClasses()
            .that().resideOutsideOfPackage("com.easycrm.platform.money..")
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
                            events.add(SimpleConditionEvent.satisfied(item, call.getDescription()));
                        }
                    }
                }
            }
        };
    }
}
