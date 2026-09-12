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

    // --- squawk -----------------------------------------------------------------------

    @Test
    @DisplayName("the migration lint runs and blocks")
    void migrationLintBlocks() throws Exception {
        var step = stepNamed("supply-chain", "Migration lint");
        assertNotEquals(Boolean.TRUE, step.get("continue-on-error"), "the migration lint must block");
        assertTrue(
                bodyOf(step).contains("squawk-cli@2.65.0"),
                "squawk must be installed at an exact version, not floating");
    }

    @Test
    @DisplayName("the migration lint scopes itself to migrations changed in this push or PR")
    void migrationLintScopesToChangedFiles() throws Exception {
        String body = bodyOf(stepNamed("supply-chain", "Migration lint"));
        // Flyway checksums make the 34 existing migrations immutable: editing one breaks every
        // database that has applied it. Linting them could only produce findings nobody is
        // permitted to act on, so the scope is the diff -- which is also the rule actually being
        // enforced, that NEW ddl must be safe against a live database.
        assertTrue(body.contains("git diff --name-only"), "squawk must lint the changed set, not the tree");
        assertTrue(
                body.contains("--diff-filter=AM"),
                "added and modified: a modified migration is itself a Flyway checksum violation, "
                        + "so flagging it is a second and cheaper alarm on that problem");
        assertTrue(
                body.contains("0000000000000000000000000000000000000000"),
                "a branch's first push has an all-zeros base and must pass, not error");
    }

    // --- dependency-check -----------------------------------------------------------------

    @Test
    @DisplayName("the dependency scan reports rather than blocks, and says so in the workflow")
    void dependencyScanReportsAndDoesNotBlock() throws Exception {
        // The inverse of every other assertion in this class, and deliberately so (spec D1). A
        // CVE published overnight in a transitive dependency would otherwise redden main with no
        // code change and nobody to attribute it to -- which is how gates get ignored. The flip
        // trigger is branch protection, at which point there is a PR queue to triage in.
        assertEquals(
                Boolean.TRUE,
                jobNamed("dependency-check").get("continue-on-error"),
                "dependency-check must NOT block while CI is post-merge only");
    }

    @Test
    @DisplayName("the dependency scan refreshes its vulnerability database nightly")
    void dependencyScanRunsOnASchedule() throws Exception {
        assertTrue(
                triggers().containsKey("schedule"),
                "without a schedule the NVD database only refreshes when someone pushes, so a "
                        + "quiet week reports against a stale database");
    }
}
