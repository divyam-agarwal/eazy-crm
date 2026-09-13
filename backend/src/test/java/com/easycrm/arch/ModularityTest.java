package com.easycrm.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.easycrm.EasyCrmApplication;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Spring Modulith's structural verification (M1/M2).
 *
 * <p><b>Read this before trusting a green run.</b> {@code platform} is declared OPEN, and OPEN
 * suppresses every cycle that routes through the open module. All 12 cycles this repo had in
 * September 2026 routed through {@code platform}, so verify() passed with ZERO violations on the
 * broken graph — measured, not theorised, and isolated by planting a {@code catalog -> sales} cycle
 * which IS still caught. See challenge #75.
 *
 * <p>So what this test covers is: module DETECTION, dependency violations between the six
 * non-platform modules, and cycles NOT involving {@code platform}.
 * <b>{@link ModuleDirectionArchTest} is what covers {@code platform}'s own dependencies (H4), and
 * {@link CrossDomainRepositoryArchTest} is what covers cross-service data access (Layer 2), which
 * is not a cycle and which verify() reports only as a non-exposed-type finding that OPEN
 * silences.</b> A gate with a known blind spot is fine; an undocumented one is a trap.
 */
class ModularityTest {

    private static ApplicationModules modules() {
        return ApplicationModules.of(EasyCrmApplication.class);
    }

    @Test
    void theModuleStructureVerifies() {
        modules().verify();
    }

    /**
     * Non-vacuity, in the shape this repo already uses for ArchUnit. An ApplicationModules that
     * detected nothing would verify() cleanly, and the whole adoption would be theatre.
     *
     * <p>Seven modules is exact on purpose here, unlike the loose class-count floors elsewhere: a
     * module appearing or disappearing is a boundary event that should be looked at, and there are
     * only seven. {@code demo} is one of them — it belongs to no service, S9 says delete it, and
     * this assertion is what makes ignoring S9 cost something (MF4).
     */
    @Test
    void detectionIsNotVacuous() {
        List<String> names = new ArrayList<>();
        for (ApplicationModule module : modules()) {
            names.add(module.getIdentifier().toString());
        }

        assertThat(names)
                .as("a module appeared or disappeared; that is a boundary event, not a test to bump")
                .containsExactlyInAnyOrder("catalog", "crm", "demo", "iam", "platform", "sales", "tenant");
    }
}
