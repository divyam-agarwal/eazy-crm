package com.easycrm.platform.frontend;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Guards the frontend and E2E CI jobs (spec 2026-09-14-f0 §6.5). SupplyChainWorkflowTest iterates only
 * the supply-chain job, so without this class a new job's gate could be deleted or softened and every
 * local build would stay green.
 *
 * <p>Shaped around how a gate actually gets neutered — an {@code if:}, a {@code continue-on-error}, an
 * {@code || true}, dropping {@code --frozen-lockfile}, or a floating image tag — rather than around a
 * step's mere absence. Each assertion was seen failing by making that change (F0b Task 16).
 */
class FrontendWorkflowTest {

    private static final Map<String, List<String>> GATES = Map.of(
            "frontend",
            List.of(
                    "Install",
                    "Generated client matches the contract",
                    "Typecheck",
                    "Lint",
                    "Unit and component tests",
                    "Build",
                    "Route JS budget"),
            "e2e",
            List.of("Build the backend jar", "Install", "Install Chromium", "Build the frontend", "End-to-end tests"));

    @SuppressWarnings("unchecked")
    private static Map<String, Object> job(String name) throws IOException {
        Path wf = Path.of(System.getProperty("ci.workflow"));
        assertTrue(Files.exists(wf), "ci.workflow points at a missing file: " + wf);
        try (var in = Files.newInputStream(wf)) {
            Map<String, Object> workflow = new Yaml().load(in);
            var jobs = (Map<String, Object>) workflow.get("jobs");
            var found = (Map<String, Object>) jobs.get(name);
            assertNotNull(found, "no job named " + name + " in ci.yml; jobs are " + jobs.keySet());
            return found;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> steps(String jobName) throws IOException {
        return (List<Map<String, Object>>) job(jobName).get("steps");
    }

    private static Map<String, Object> step(String jobName, String stepName) throws IOException {
        return steps(jobName).stream()
                .filter(s -> stepName.equals(s.get("name")))
                .findFirst()
                .orElseThrow(
                        () -> new AssertionError("job " + jobName + " has no step named exactly '" + stepName + "'"));
    }

    private static String run(String jobName, String stepName) throws IOException {
        return String.valueOf(step(jobName, stepName).get("run"));
    }

    @Test
    @DisplayName("both jobs exist and neither is softened at job level")
    void jobsAreBlocking() throws IOException {
        for (String name : GATES.keySet()) {
            var job = job(name);
            assertNull(job.get("continue-on-error"), name + " must not carry continue-on-error");
            assertNull(job.get("if"), name + " must not be conditional");
        }
    }

    @Test
    @DisplayName("every gate step exists and runs unconditionally")
    void gateStepsAreUnconditional() throws IOException {
        int checked = 0;
        for (var entry : GATES.entrySet()) {
            for (String name : entry.getValue()) {
                var step = step(entry.getKey(), name);
                assertNull(step.get("if"), entry.getKey() + " / " + name + " must not carry if:");
                assertNull(
                        step.get("continue-on-error"),
                        entry.getKey() + " / " + name + " must not carry continue-on-error");
                assertFalse(
                        String.valueOf(step.get("run")).contains("|| true"),
                        entry.getKey() + " / " + name + " must not swallow failure");
                checked++;
            }
        }
        assertEquals(12, checked, "non-vacuity: every named gate was inspected");
    }

    @Test
    @DisplayName("only artifact uploads may be conditional, and only on failure")
    void onlyUploadsAreConditional() throws IOException {
        for (String name : GATES.keySet()) {
            for (var step : steps(name)) {
                Object condition = step.get("if");
                if (condition == null) continue;
                assertTrue(
                        String.valueOf(step.get("uses")).startsWith("actions/upload-artifact"),
                        name + " / " + step.get("name") + " is conditional but is not an artifact upload");
                // Performance-3: the bundle report is the ONE artifact that must also survive a
                // failing gate — a budget failure with no treemap forces whoever investigates to
                // rebuild at that exact commit to find which package grew. Every other upload
                // stays failure()-only so a green run does not accumulate artifacts.
                String allowed = "Upload bundle report".equals(step.get("name")) ? "!cancelled()" : "failure()";
                assertEquals(allowed, String.valueOf(condition), name + " / " + step.get("name"));
            }
        }
    }

    @Test
    @DisplayName("the gates run what they claim to run")
    void gateBodies() throws IOException {
        assertTrue(run("frontend", "Install").contains("--frozen-lockfile"));
        assertTrue(run("e2e", "Install").contains("--frozen-lockfile"));
        String drift = run("frontend", "Generated client matches the contract");
        assertTrue(drift.contains("pnpm gen:api") && drift.contains("git diff --exit-code"), drift);
        assertTrue(run("frontend", "Typecheck").contains("pnpm typecheck"));
        assertTrue(run("frontend", "Lint").contains("pnpm lint"));
        // Testing-6: `contains("pnpm test")` also accepts a bare `pnpm test`, which silently drops
        // Task 17's coverage floor. Require the script that enforces it.
        assertTrue(
                run("frontend", "Unit and component tests").contains("pnpm test:coverage"),
                "the unit-test gate must run the coverage script, or the coverage floor is not enforced");
        assertTrue(run("frontend", "Build").contains("pnpm build")); // the job's own build step
        assertTrue(run("e2e", "Build the frontend").contains("pnpm build"));
        assertTrue(run("frontend", "Route JS budget").contains("pnpm budget"));
        String e2e = run("e2e", "End-to-end tests");
        assertTrue(e2e.contains("pnpm e2e"), e2e);
        // Testing-6: a narrowed run would skip the refresh-race specs entirely and still be green.
        for (String narrowing : List.of("--project", "--grep", " -g ")) {
            assertFalse(e2e.contains(narrowing), "the E2E gate must run every project and test, but has " + narrowing);
        }
        if (e2e.contains("set +e")) {
            assertTrue(
                    e2e.strip().endsWith("exit $status"),
                    "set +e is only allowed when the step exits with the test status:\n" + e2e);
        }
    }

    @Test
    @DisplayName("no step carries continue-on-error unless it is a registered gate")
    void continueOnErrorIsOnlyOnRegisteredGates() throws IOException {
        // gateStepsAreUnconditional only inspects the steps named in GATES, and
        // onlyUploadsAreConditional only inspects steps carrying an `if:`. A brand-new step with
        // ONLY `continue-on-error: true` — no `if:`, not registered — is invisible to both: it
        // is a way to add a non-blocking step that nothing here notices (F0b Task 16 fix round 1).
        // Scanning every step in both jobs, rather than only the named gates, is what closes that.
        for (String jobName : GATES.keySet()) {
            List<String> registered = GATES.get(jobName);
            for (var step : steps(jobName)) {
                if (step.get("continue-on-error") == null) continue;
                String name = String.valueOf(step.get("name"));
                assertTrue(
                        registered.contains(name),
                        jobName + " / " + name + " carries continue-on-error but is not a gate registered in"
                                + " GATES — such a step is invisible to gateStepsAreUnconditional and can be added"
                                + " as a silent, permanently non-blocking step. Register it in GATES (and adjust"
                                + " the checked-count assertion) if it is meant to gate the build.");
            }
        }
    }

    @Test
    @DisplayName("the E2E Postgres image has an exact version tag")
    @SuppressWarnings("unchecked")
    void postgresImageIsPinned() throws IOException {
        var services = (Map<String, Object>) job("e2e").get("services");
        assertNotNull(services, "e2e must declare a postgres service");
        var postgres = (Map<String, Object>) services.get("postgres");
        String image = String.valueOf(postgres.get("image"));
        assertTrue(
                image.matches("postgres:\\d+\\.\\d+-alpine[0-9.]*"),
                "expected an exact tag like postgres:16.10-alpine, got " + image);
    }
}
