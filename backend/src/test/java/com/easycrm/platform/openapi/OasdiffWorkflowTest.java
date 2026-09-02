package com.easycrm.platform.openapi;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

/**
 * Guards the CI step that reports the OpenAPI changelog, on both events it can fire on.
 *
 * <p>Two things this test is careful to be, because the bug it exists to prevent was subtle:
 *
 * <p><b>It reads the real workflow, not a copy.</b> The path is injected as {@code ci.workflow}
 * by build.gradle.kts, and the shell body is extracted out of that file and executed — so a
 * change to ci.yml is a change to what runs here. A test against a transcribed copy of the
 * script would have kept passing through exactly the regression described below.
 *
 * <p><b>It stubs {@code docker} rather than running oasdiff.</b> What can actually break here is
 * <i>our</i> logic — which commit gets chosen as the base, and which guard fires when there
 * isn't one. oasdiff's own correctness is oasdiff's problem, and pulling its image would make
 * this suite depend on Docker Hub. The stub records what it was asked to diff, which is the only
 * thing the step is responsible for getting right.
 *
 * <p><b>The regression being guarded.</b> The step originally diffed against {@code HEAD~1}. That
 * is the previous <i>commit</i>, not the previous state of the <i>branch</i>: a push carrying N
 * commits compared only the last one and silently reported nothing for the other N-1. This repo
 * pushes a merge commit plus its docs follow-ups together as a matter of course, so the common
 * case was the broken one — measured on the real branch, a 19-commit push reported "No changes
 * detected" for the range that introduced the entire API contract. {@code pushUsesTheBranchesPreviousState}
 * fails against the old behaviour and passes against the new one.
 */
class OasdiffWorkflowTest {

    private static final String ZERO_SHA = "0000000000000000000000000000000000000000";
    private static final String SNAPSHOT = "docs/api/openapi.yaml";

    private static Map<String, Object> workflow() throws IOException {
        Path wf = Path.of(System.getProperty("ci.workflow"));
        assertTrue(Files.exists(wf), "ci.workflow points at a missing file: " + wf);
        try (var in = Files.newInputStream(wf)) {
            return new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> steps() throws IOException {
        var jobs = (Map<String, Object>) workflow().get("jobs");
        var check = (Map<String, Object>) jobs.get("check");
        return (List<Map<String, Object>>) check.get("steps");
    }

    private static Map<String, Object> stepNamed(String prefix) throws IOException {
        return steps().stream()
                .filter(s -> String.valueOf(s.get("name")).startsWith(prefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no CI step whose name starts with " + prefix));
    }

    // --- wiring: which commit each event diffs against -------------------------------------

    @Test
    @DisplayName("the base is the branch's previous state on a push, and the base branch tip on a PR")
    void baseShaIsResolvedPerEvent() throws Exception {
        String baseSha = String.valueOf(((Map<?, ?>) stepNamed("API changelog").get("env")).get("BASE_SHA"));

        // Matched with a trailing word boundary, not `contains`. A substring check passes on
        // `...base.shaa` -- a typo actionlint does NOT catch either, because it does not
        // deep-validate webhook payload properties. Verified empirically before writing this.
        assertTrue(
                baseSha.matches("(?s).*\\bgithub\\.event\\.before\\b.*"),
                "a push must diff against the SHA the branch was at before the push; got: " + baseSha);
        assertTrue(
                baseSha.matches("(?s).*\\bgithub\\.event\\.pull_request\\.base\\.sha\\b.*"),
                "a pull request must diff against the base branch tip; got: " + baseSha);
        assertFalse(
                baseSha.contains("HEAD~1"),
                "HEAD~1 is the previous commit, not the previous state of the branch — it reports"
                        + " nothing for all but the last commit of a multi-commit push");
    }

    @Test
    @DisplayName("the step is not gated to push, so pull requests are covered too")
    void theStepRunsOnPullRequestsAsWell() throws Exception {
        Object condition = stepNamed("API changelog").get("if");
        assertNull(
                condition,
                "an `if` here would silently exclude an event; the step resolves its own base per"
                        + " event instead. Found: " + condition);

        var on = (Map<?, ?>) workflow().get(true); // YAML 1.1 parses the key `on` as boolean true
        assertTrue(on.containsKey("pull_request"), "the workflow must still trigger on pull_request");
        assertTrue(on.containsKey("push"), "the workflow must still trigger on push");
    }

    @Test
    @DisplayName("checkout fetches enough history to reach a base any distance back")
    void checkoutIsNotShallow() throws Exception {
        var checkout = steps().stream()
                .filter(s -> String.valueOf(s.get("uses")).startsWith("actions/checkout"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no checkout step"));
        Object depth = ((Map<?, ?>) checkout.get("with")).get("fetch-depth");

        assertEquals(
                "0",
                String.valueOf(depth),
                "the base can be any distance back; a shallow clone that lacks it fails *silently*"
                        + " into the nothing-to-compare path, reporting no API changes for a push"
                        + " that changed the API");
    }

    @Test
    @DisplayName("the changelog is reported, not enforced")
    void theStepDoesNotBlockTheBuild() throws Exception {
        assertEquals(
                "true",
                String.valueOf(stepNamed("API changelog").get("continue-on-error")),
                "flipping this to blocking is a deliberate policy change tied to the frontend"
                        + " existing — see the comment in ci.yml — not something to drift into");
    }

    // --- behaviour: run the real shell body against git fixtures ----------------------------

    @TempDir
    Path tmp;

    private Path repo;
    private Path summary;
    private Path dockerLog;
    private Path stubDir;

    /** Extracts the step's own shell body so the test exercises what actually runs. */
    private static String stepScript() throws IOException {
        return "#!/bin/bash\n" + stepNamed("API changelog").get("run");
    }

    private String git(String... args) throws Exception {
        var cmd = new java.util.ArrayList<String>(List.of("git"));
        cmd.addAll(List.of(args));
        var p = new ProcessBuilder(cmd)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "git timed out: " + String.join(" ", args));
        assertEquals(0, p.exitValue(), "git " + String.join(" ", args) + " failed:\n" + out);
        return out.trim();
    }

    /** Commits the snapshot with the given marker content and returns the commit SHA. */
    private String commitSnapshot(String marker) throws Exception {
        Path f = repo.resolve(SNAPSHOT);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "openapi: 3.0.1\ninfo:\n  title: " + marker + "\n");
        git("add", "-A");
        git("commit", "-m", "snapshot " + marker);
        return git("rev-parse", "HEAD");
    }

    @BeforeEach
    void setUpFixtureRepo() throws Exception {
        repo = Files.createDirectories(tmp.resolve("repo"));
        summary = tmp.resolve("summary.md");
        Files.writeString(summary, "");
        dockerLog = tmp.resolve("docker-invocations.txt");

        // A `docker` stub earlier on PATH than any real one. It records the base document it was
        // handed, which is the single thing the step is responsible for choosing correctly.
        stubDir = Files.createDirectories(tmp.resolve("bin"));
        Path docker = stubDir.resolve("docker");
        Files.writeString(docker, """
                #!/bin/bash
                # Records the CONTENT of the base document the step extracted, then emits a
                # plausible oasdiff line. Exits 1 when OASDIFF_STUB_FAIL is set, to exercise the
                # tool-failure branch without needing a broken image.
                cat /tmp/openapi-base.yaml >> "$DOCKER_LOG" 2>/dev/null || true
                if [ -n "${OASDIFF_STUB_FAIL:-}" ]; then
                  echo "stub failure" >&2
                  exit 1
                fi
                echo "1 changes: 1 error, 0 warning, 0 info"
                """);
        docker.toFile().setExecutable(true);

        git("init", "-q", "-b", "main");
        git("config", "user.email", "test@easycrm.test");
        git("config", "user.name", "test");
    }

    private int runStep(String baseSha, boolean stubFails) throws Exception {
        Path script = tmp.resolve("step.sh");
        Files.writeString(script, stepScript());
        script.toFile().setExecutable(true);

        var pb = new ProcessBuilder("bash", script.toString())
                .directory(repo.toFile())
                .redirectErrorStream(true);
        var env = pb.environment();
        env.put("PATH", stubDir + ":" + env.getOrDefault("PATH", ""));
        env.put("BASE_SHA", baseSha);
        env.put("GITHUB_STEP_SUMMARY", summary.toString());
        env.put("DOCKER_LOG", dockerLog.toString());
        if (stubFails) {
            env.put("OASDIFF_STUB_FAIL", "1");
        }
        var p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "step timed out. Output:\n" + out);
        return p.exitValue();
    }

    private String summaryText() throws IOException {
        return Files.readString(summary, StandardCharsets.UTF_8);
    }

    private String diffedBaseContent() throws IOException {
        return Files.exists(dockerLog) ? Files.readString(dockerLog, StandardCharsets.UTF_8) : "";
    }

    @Test
    @DisplayName("a push diffs against the branch's previous state, not the previous commit")
    void pushUsesTheBranchesPreviousState() throws Exception {
        String before = commitSnapshot("v1-before-the-push");
        commitSnapshot("v2-middle-of-the-push");
        commitSnapshot("v3-head");

        // github.event.before — the branch tip before a push that carried three commits.
        assertEquals(0, runStep(before, false));

        assertTrue(
                diffedBaseContent().contains("v1-before-the-push"),
                "the step must diff against the branch's pre-push state. Diffed against:\n" + diffedBaseContent());
        assertFalse(
                diffedBaseContent().contains("v2-middle-of-the-push"),
                "v2 is HEAD~1 — diffing against it is the regression this test exists to catch:"
                        + " it reports nothing for all but the last commit of the push");
        assertTrue(summaryText().contains("## API changelog"), summaryText());
    }

    @Test
    @DisplayName("a pull request diffs against the base branch tip")
    void pullRequestUsesTheBaseBranchTip() throws Exception {
        String baseTip = commitSnapshot("main-tip");
        commitSnapshot("pr-commit-one");
        commitSnapshot("pr-commit-two");

        // github.event.pull_request.base.sha, with the working tree at the merge result.
        assertEquals(0, runStep(baseTip, false));

        assertTrue(
                diffedBaseContent().contains("main-tip"),
                "a PR must be compared against the base branch tip. Diffed against:\n" + diffedBaseContent());
        assertTrue(summaryText().contains("### Breaking changes"), summaryText());
    }

    @Test
    @DisplayName("a branch's first push has no base and says so")
    void firstPushReportsNothingToCompare() throws Exception {
        commitSnapshot("v1");

        assertEquals(0, runStep(ZERO_SHA, false));

        assertTrue(summaryText().contains("nothing to compare"), summaryText());
        assertEquals("", diffedBaseContent(), "oasdiff must not run when there is no base");
    }

    @Test
    @DisplayName("an unreachable base — a discarded force-push — says so instead of failing")
    void unreachableBaseReportsNothingToCompare() throws Exception {
        commitSnapshot("v1");

        assertEquals(0, runStep("deadbeefdeadbeefdeadbeefdeadbeefdeadbeef", false));

        assertTrue(summaryText().contains("nothing to compare"), summaryText());
        assertEquals("", diffedBaseContent());
    }

    @Test
    @DisplayName("a base from before the snapshot existed says so instead of failing")
    void baseWithoutTheSnapshotReportsNothingToCompare() throws Exception {
        Files.writeString(repo.resolve("README.md"), "no snapshot here yet\n");
        git("add", "-A");
        git("commit", "-m", "before the snapshot existed");
        String beforeSnapshot = git("rev-parse", "HEAD");
        commitSnapshot("v1");

        assertEquals(0, runStep(beforeSnapshot, false));

        assertTrue(summaryText().contains("nothing to compare"), summaryText());
        assertEquals("", diffedBaseContent());
    }

    @Test
    @DisplayName("a tool failure says so rather than rendering as 'no API changes'")
    void aToolFailureIsReportedNotSwallowed() throws Exception {
        String before = commitSnapshot("v1");
        commitSnapshot("v2");

        assertEquals(0, runStep(before, true), "the step stays non-blocking even when oasdiff fails");

        String text = summaryText();
        assertTrue(
                text.contains("failed to run"),
                "without pipefail and an explicit check, a docker failure exits 0 through `tee` and"
                        + " leaves an empty section — indistinguishable from 'no API changes', which"
                        + " is a misreport rather than a missed alert. Got:\n" + text);
        assertEquals(
                4,
                text.split("```", -1).length - 1,
                "both fenced blocks must still be closed on the failure path, or the rest of the"
                        + " job summary renders inside a code fence. Got:\n" + text);
    }
}
