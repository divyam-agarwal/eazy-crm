package com.easycrm.platform.supplychain;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
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
 *
 * <p><b>The assertions are shaped around how a scan actually gets neutered</b>, which is almost
 * never by deleting the step -- that shows up in review. It is by adding {@code if:}, by adding a
 * {@code continue-on-error} whose value is an expression rather than a literal, by dropping
 * {@code fetch-depth: 0} so gitleaks scans one commit, or by appending {@code || true} to the
 * body. Each of those leaves a step that still exists, still names the right tool and still reads
 * as blocking. There is one test per route, and each was checked by making the change and watching
 * this class go red.
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

    /** The workflow as text, for assertions that are about what is written rather than what parses. */
    static String rawWorkflow() throws IOException {
        return Files.readString(Path.of(System.getProperty("ci.workflow")));
    }

    /** The whole step body, for asserting on a pinned image coordinate. */
    static String bodyOf(Map<String, Object> step) {
        return String.valueOf(step.getOrDefault("run", "")) + String.valueOf(step.getOrDefault("uses", ""));
    }

    /** The job's `actions/checkout` step, which carries no `name:` and so is found by its `uses`. */
    static Map<String, Object> checkoutStep(String job) throws IOException {
        return stepsOf(job).stream()
                .filter(s -> String.valueOf(s.getOrDefault("uses", "")).startsWith("actions/checkout"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no actions/checkout step in job " + job));
    }

    /**
     * Blocking means the key is ABSENT, not "present and not Boolean.TRUE".
     *
     * <p>{@code continue-on-error: ${{ true }}} parses as the String "${{ true }}", which is not
     * equal to {@link Boolean#TRUE} -- so an assertNotEquals passes while GitHub Actions expands
     * the expression and honours it. Anything at all under this key on a scan is a softening, and
     * the only safe assertion is that nobody wrote one.
     */
    static void assertBlocks(Map<String, Object> node, String what) {
        assertFalse(
                node.containsKey("continue-on-error"),
                what + " must block: continue-on-error is present (" + node.get("continue-on-error")
                        + "). Any value counts -- an expression like ${{ true }} is a String, not a boolean,"
                        + " and GitHub honours it either way.");
    }

    /**
     * {@code if:} is the cheapest way to neuter a scan and the hardest to spot in review:
     * {@code if: false}, or a plausible-looking {@code if: github.event_name == 'schedule'},
     * leaves the step in the file, in the diff and in the job listing while it never runs. None of
     * these steps has any reason to be conditional, so the assertion is that the key is absent --
     * there is no value worth allowing.
     */
    static void assertUnconditional(Map<String, Object> node, String what) {
        assertFalse(
                node.containsKey("if"),
                what + " must be unconditional: it carries `if: " + node.get("if")
                        + "`, which can disable the scan while leaving every other assertion green");
    }

    // --- gitleaks -------------------------------------------------------------------------

    @Test
    @DisplayName("the secret scan runs and blocks")
    void secretScanBlocks() throws Exception {
        var step = stepNamed("supply-chain", "Secret scan");
        assertBlocks(step, "the secret scan -- a committed secret on a public repo is disclosed, not merely reported");
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
        assertBlocks(jobNamed("supply-chain"), "the supply-chain job as a whole, not only step by step,");
    }

    // --- actionlint -----------------------------------------------------------------------

    @Test
    @DisplayName("the workflow lint runs and blocks")
    void workflowLintBlocks() throws Exception {
        var step = stepNamed("supply-chain", "Workflow lint");
        assertBlocks(step, "the workflow lint");
        assertTrue(
                bodyOf(step).contains("rhysd/actionlint:1.7.12"),
                "the actionlint image must be pinned to an exact tag");
    }

    // --- squawk -----------------------------------------------------------------------

    @Test
    @DisplayName("the migration lint runs and blocks")
    void migrationLintBlocks() throws Exception {
        var step = stepNamed("supply-chain", "Migration lint");
        assertBlocks(step, "the migration lint");
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

    // --- the ways a scan can be neutered without deleting it --------------------------------

    /** The three scans this job exists for, by the name prefix each step is found under. */
    private static final List<String> SCAN_STEPS = List.of("Secret scan", "Workflow lint", "Migration lint");

    @Test
    @DisplayName("no scan can be switched off with an `if:`")
    void noScanIsConditional() throws Exception {
        // Asserted on the whole job and on every step in it, not only the three scans: the
        // checkout is what the scans read, so an `if:` there disables all three at once.
        assertUnconditional(jobNamed("supply-chain"), "the supply-chain job");
        for (var step : stepsOf("supply-chain")) {
            assertUnconditional(step, "supply-chain step `" + step.getOrDefault("name", step.get("uses")) + "`");
        }
    }

    @Test
    @DisplayName("no scan step is softened with a continue-on-error of any kind")
    void noScanStepIsSoftened() throws Exception {
        for (String name : SCAN_STEPS) {
            assertBlocks(stepNamed("supply-chain", name), "supply-chain step `" + name + "`");
        }
    }

    @Test
    @DisplayName("the supply-chain checkout keeps full history")
    void supplyChainCheckoutIsUnshallowed() throws Exception {
        // Delete this and gitleaks on push scans a single commit: it finds nothing, exits 0, and
        // the job stays green while coverage has collapsed to one commit. Nothing else in this
        // class would notice -- the step is still there, still blocking, still pinned.
        @SuppressWarnings("unchecked")
        var with = (Map<String, Object>) checkoutStep("supply-chain").get("with");
        assertNotNull(with, "the supply-chain checkout has no `with:` block, so no fetch-depth");
        assertEquals(
                0,
                with.get("fetch-depth"),
                "fetch-depth: 0 is what makes the gitleaks full-history scan a full history; a "
                        + "diff-only scan cannot find a secret that entered three commits ago and "
                        + "was never removed");
    }

    /**
     * Shell escape hatches that leave a step looking intact while it can no longer fail.
     *
     * <p>{@code || true} and {@code || exit 0} swallow the tool's exit status; gitleaks'
     * {@code --exit-code 0} makes the tool itself report success on findings. Every assertion in
     * this class other than this one passes with any of them appended.
     */
    private static final List<String> ESCAPE_HATCHES = List.of("|| true", "|| exit 0", "--exit-code 0");

    @Test
    @DisplayName("no scan step swallows its own exit status")
    void noScanStepSwallowsItsExitStatus() throws Exception {
        for (String name : SCAN_STEPS) {
            String body = bodyOf(stepNamed("supply-chain", name));
            for (String hatch : ESCAPE_HATCHES) {
                assertFalse(
                        body.contains(hatch),
                        "supply-chain step `" + name + "` contains `" + hatch + "`, which makes the "
                                + "step incapable of failing while every other assertion here still passes");
            }
        }
    }

    // --- pinning (spec section 9, assertion 4) ----------------------------------------------

    /**
     * A moving tag: a reference whose target changes with no commit in this repo.
     *
     * <p>Matched against the raw workflow text rather than the parsed tree on purpose -- these
     * live inside {@code run:} bodies as docker image coordinates and npm specs, where the YAML
     * parser sees one opaque string. A general sweep is what spec section 9 asked for, and it is
     * what keeps earning its place: it covers the next image someone adds, which a hardcoded list
     * of today's three coordinates does not.
     *
     * <p><b>What this deliberately does not match:</b> {@code actions/checkout@v7} and the other
     * {@code uses:} refs. Spec section 9 lists {@code @v2} alongside {@code :latest} as floating,
     * and strictly it is -- a major-version tag is mutable. The narrowing is deliberate and the
     * reason is finding 7's: Dependabot's {@code github-actions} ecosystem reads {@code uses:} and
     * will open a PR when those move, so they have an updater and a review step. Nothing reads
     * inside a {@code run:} block, so a moving tag there has neither. If {@code uses:} refs are
     * ever SHA-pinned, tighten this pattern rather than adding a second one.
     */
    private static final Pattern MOVING_TAG = Pattern.compile(
            "[\\w.\\-/]+[:@](latest|main|master|stable|edge|nightly|next|head)\\b", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("no tool in the workflow is referenced by a moving tag")
    void everyToolVersionIsPinned() throws Exception {
        var hits =
                MOVING_TAG.matcher(rawWorkflow()).results().map(r -> r.group()).toList();
        assertTrue(
                hits.isEmpty(),
                "moving tags in ci.yml: " + hits + ". A moving tag is a build whose behaviour "
                        + "changes with no commit -- and when it changes it changes on main, "
                        + "after review, with no diff to point at.");
    }
}
