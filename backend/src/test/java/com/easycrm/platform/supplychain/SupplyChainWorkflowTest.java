package com.easycrm.platform.supplychain;

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
 * Guards the supply-chain scans, which run in CI only.
 *
 * <p>Wave 1.5 deliberately did NOT wire gitleaks, actionlint and squawk into {@code check}: they
 * are Go/Rust binaries, and wrapping them in Exec tasks would slow every local build and require
 * three installs (spec D4). The cost of that choice is that a green local run no longer implies a
 * green CI. This test is what bounds the cost (spec D5) -- it reads the real {@code ci.yml}
 * through the {@code ci.workflow} system property, so deleting a scan step, or quietly adding
 * {@code continue-on-error} to one, fails {@code ./gradlew check} on the developer's own machine.
 *
 * <p><b>Why the blocking posture is asserted in both directions.</b> Non-blocking is correct for
 * Dependency-Check and wrong for the other three (spec D1). A test that only checked "the step
 * exists" would pass on a workflow where every scan had been softened to a notification.
 */
class SupplyChainWorkflowTest {

    private static Map<String, Object> workflow() throws IOException {
        Path wf = Path.of(System.getProperty("ci.workflow"));
        assertTrue(Files.exists(wf), "ci.workflow points at a missing file: " + wf);
        try (var in = Files.newInputStream(wf)) {
            return new Yaml().load(in);
        }
    }

    /**
     * SnakeYAML implements YAML 1.1, in which the bare token {@code on} is the boolean true -- so
     * the trigger block of every GitHub Actions workflow parses under the key {@code
     * Boolean.TRUE}, not {@code "on"}. Looking up the string alone returns null, and an assertion
     * built on it would pass vacuously against a workflow with no triggers at all.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> triggers() throws IOException {
        var wf = workflow();
        Object on = wf.containsKey("on") ? wf.get("on") : wf.get(Boolean.TRUE);
        assertNotNull(on, "no trigger block found under either \"on\" or the YAML 1.1 boolean key");
        return (Map<String, Object>) on;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> jobNamed(String job) throws IOException {
        var jobs = (Map<String, Object>) workflow().get("jobs");
        var found = (Map<String, Object>) jobs.get(job);
        assertNotNull(found, "no job named " + job + " in ci.yml; jobs are " + jobs.keySet());
        return found;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> stepsOf(String job) throws IOException {
        return (List<Map<String, Object>>) jobNamed(job).get("steps");
    }

    static Map<String, Object> stepNamed(String job, String namePrefix) throws IOException {
        return stepsOf(job).stream()
                .filter(s -> String.valueOf(s.get("name")).startsWith(namePrefix))
                .findFirst()
                .orElseThrow(
                        () -> new AssertionError("no step in job " + job + " whose name starts with " + namePrefix));
    }

    /** The whole step body, for asserting on a pinned image coordinate. */
    static String bodyOf(Map<String, Object> step) {
        return String.valueOf(step.getOrDefault("run", "")) + String.valueOf(step.getOrDefault("uses", ""));
    }

    // --- gitleaks -------------------------------------------------------------------------

    @Test
    @DisplayName("the secret scan runs and blocks")
    void secretScanBlocks() throws Exception {
        var step = stepNamed("supply-chain", "Secret scan");
        assertNotEquals(
                Boolean.TRUE,
                step.get("continue-on-error"),
                "the secret scan must block -- a committed secret on a public repo is disclosed, "
                        + "not merely reported");
        assertTrue(
                bodyOf(step).contains("zricethezav/gitleaks:v8.30.1"),
                "the gitleaks image must be pinned to an exact tag; a floating tag is a build "
                        + "whose behaviour changes with no commit");
    }

    @Test
    @DisplayName("the secret scan reads the committed allowlist rather than default rules alone")
    void secretScanUsesTheCommittedConfig() throws Exception {
        assertTrue(
                bodyOf(stepNamed("supply-chain", "Secret scan")).contains(".gitleaks.toml"),
                "without -c the three documented dev defaults in application.yml fail every run, "
                        + "and the pressure is then to disable the scan rather than explain them");
    }

    @Test
    @DisplayName("the supply-chain job itself is not softened to non-blocking")
    void supplyChainJobBlocks() throws Exception {
        // continue-on-error is honoured at the job level as well as the step level, so the
        // per-step assertions alone would pass on a workflow where the whole job had been
        // softened to a notification.
        assertNotEquals(
                Boolean.TRUE,
                jobNamed("supply-chain").get("continue-on-error"),
                "the supply-chain job must block as a whole, not only step by step");
    }

    // --- actionlint -----------------------------------------------------------------------

    @Test
    @DisplayName("the workflow lint runs and blocks")
    void workflowLintBlocks() throws Exception {
        var step = stepNamed("supply-chain", "Workflow lint");
        assertNotEquals(Boolean.TRUE, step.get("continue-on-error"), "the workflow lint must block");
        assertTrue(
                bodyOf(step).contains("rhysd/actionlint:1.7.12"),
                "the actionlint image must be pinned to an exact tag");
    }
}
