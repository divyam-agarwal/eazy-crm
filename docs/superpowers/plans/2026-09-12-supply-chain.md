# Supply Chain (Wave 1.5) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give this repo automated scanning for committed secrets, vulnerable dependencies, unsafe Postgres DDL and invalid CI workflows — none of which anything in the build reads today.

**Architecture:** Three standalone binaries (gitleaks, actionlint, squawk) run in a new blocking `supply-chain` CI job, deliberately *not* wired into `./gradlew check` so the local gate stays fast; a new `SupplyChainWorkflowTest` reads the real `ci.yml` and fails the build if any scan step is deleted or silently demoted to non-blocking. OWASP Dependency-Check runs in its own non-blocking job on a nightly schedule. Dependabot opens weekly update PRs.

**Tech Stack:** GitHub Actions, Docker (pinned scanner images), npm (squawk), Gradle 9.6.1 + `buildSrc` precompiled script plugin, JUnit 5 + SnakeYAML for the workflow assertions.

**Spec:** [`docs/superpowers/specs/2026-09-12-supply-chain-design.md`](../specs/2026-09-12-supply-chain-design.md)

## Global Constraints

- **Tool versions, all verified 2026-09-12. Pin exactly; never `latest` or a floating major:**
  - gitleaks `v8.30.1` — image `zricethezav/gitleaks:v8.30.1` (confirmed present on Docker Hub, pushed 2026-03-21)
  - actionlint `1.7.12` — image `rhysd/actionlint:1.7.12` (confirmed present on Docker Hub)
  - squawk `2.65.0` — npm `squawk-cli@2.65.0` (confirmed `dist-tags.latest`)
  - OWASP Dependency-Check Gradle plugin `13.0.0` (confirmed latest+release on `plugins.gradle.org/m2`, published 2026-08-03)
  - `actions/cache@v6` (latest release v6.1.0)
- **Existing pinned actions stay as they are:** `actions/checkout@v7`, `actions/setup-java@v6`, `gradle/actions/setup-gradle@v6`, `actions/upload-artifact@v7`.
- **`./gradlew clean check` must not get slower.** No Exec task wrapping a scanner, and Dependency-Check must not be wired into `check` (spec D7).
- **Blocking posture is load-bearing:** gitleaks, actionlint and squawk block. Dependency-Check is `continue-on-error: true` (spec D1). `SupplyChainWorkflowTest` asserts both directions.
- **Baseline to preserve: 591 tests, 0 failures, 0 errors** (563 root + 28 `platform-primitives`). The new test class adds to this; record the new total in the final task.
- **The Gradle root is `backend/`, not the repo root.** Workflow jobs that run Gradle need `defaults.run.working-directory: backend`; jobs that run `git` over the repo must NOT.
- **Commits:** author as `divyam` with a plain `git commit`. Do NOT add a `Co-Authored-By: Claude` trailer and do not mention Claude or AI anywhere in a commit message (`CLAUDE.md`).
- **No application behaviour changes.** No `src/main/java` edits in this slice.
- **Every rule you suppress needs a written reason.** "It was noisy" is not a reason (spec §5, §6).

### SnakeYAML trap — read before writing Task 1

SnakeYAML implements **YAML 1.1**, where the bare token `on` is a *boolean*, not a string. GitHub
Actions workflows key their triggers on `on:`, so `workflow().get("on")` returns **null** and
`workflow().get(Boolean.TRUE)` returns the trigger map. Task 1's helper handles both so the test
does not silently assert nothing. `OasdiffWorkflowTest` never hit this because it only reads
`jobs`.

---

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `backend/src/test/java/com/easycrm/platform/supplychain/SupplyChainWorkflowTest.java` | Create | Reads the real `ci.yml`; asserts each scan step exists, its blocking posture, and that every tool version is pinned |
| `.github/workflows/ci.yml` | Modify | Adds the `schedule` trigger, the `supply-chain` job and the `dependency-check` job |
| `.gitleaks.toml` | Create | Secret-scan config: default ruleset plus the three documented allowlist entries |
| `.github/dependabot.yml` | Create | Weekly update PRs for the `gradle` (at `/backend`) and `github-actions` (at `/`) ecosystems |
| `backend/squawk.toml` | Create (conditional) | Only if Task 3 finds rules that genuinely do not apply; each exclusion carries a reason |
| `backend/gradle/libs.versions.toml` | Modify | `dependencyCheck = "13.0.0"` version + plugin entry |
| `backend/buildSrc/build.gradle.kts` | Modify | Puts the Dependency-Check plugin on the `buildSrc` classpath so the convention plugin can apply it |
| `backend/buildSrc/src/main/kotlin/easycrm.quality-conventions.gradle.kts` | Modify | Applies and configures Dependency-Check; does **not** wire it into `check` |
| `docs/superpowers/engineering-challenges.md` | Modify | Entries for anything non-obvious that turns up |
| `docs/ROADMAP.md`, `docs/superpowers/HANDOFF.md` | Modify | Strike item 2; record the slice |

Tasks are drawn one-per-tool so a reviewer can reject the squawk scoping without rejecting gitleaks. Each task opens with a failing assertion and ends green.

---

### Task 1: gitleaks — secret scanning, and the test that guards every later step

**Files:**
- Create: `backend/src/test/java/com/easycrm/platform/supplychain/SupplyChainWorkflowTest.java`
- Create: `.gitleaks.toml`
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: the `ci.workflow` system property, already injected for every `Test` task by `backend/build.gradle.kts` (`systemProperty("ci.workflow", ciWorkflow.asFile.absolutePath)`). Nothing to add.
- Produces: `SupplyChainWorkflowTest.stepsOf(String jobName)` and `stepNamed(String jobName, String namePrefix)` — Tasks 2, 3 and 5 add assertions using these helpers. `triggers()` returns the `on:` map handling the SnakeYAML boolean-key trap.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/platform/supplychain/SupplyChainWorkflowTest.java`:

```java
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
 * <p>Wave 1.5 deliberately did NOT wire gitleaks, actionlint and squawk into {@code check}:
 * they are Go/Rust binaries, and wrapping them in Exec tasks would slow every local build and
 * require three installs (spec D4). The cost of that choice is that a green local run no longer
 * implies a green CI. This test is what bounds the cost (spec D5) — it reads the real
 * {@code ci.yml} through the {@code ci.workflow} system property, so deleting a scan step, or
 * quietly adding {@code continue-on-error} to one, fails {@code ./gradlew check} on the
 * developer's own machine.
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
     * SnakeYAML implements YAML 1.1, in which the bare token {@code on} is the boolean true --
     * so the trigger block of every GitHub Actions workflow parses under the key
     * {@code Boolean.TRUE}, not {@code "on"}. Looking up the string alone returns null, and an
     * assertion built on it would pass vacuously against a workflow with no triggers at all.
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
                .orElseThrow(() -> new AssertionError(
                        "no step in job " + job + " whose name starts with " + namePrefix));
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
}
```

- [ ] **Step 2: Run the test and watch it fail**

```bash
cd backend && ./gradlew test --tests 'com.easycrm.platform.supplychain.SupplyChainWorkflowTest' --console=plain
```

Expected: FAIL — `AssertionError: no job named supply-chain in ci.yml; jobs are [check]`.

- [ ] **Step 3: Write the gitleaks allowlist**

Create `.gitleaks.toml` at the **repository root**:

```toml
# Secret scanning for EasyCRM. See docs/superpowers/specs/2026-09-12-supply-chain-design.md section 4.
#
# This file is the repo's written record of "known non-secrets". Adding a fourth entry means
# justifying it here, in the same place, which is the point of keeping the list narrow.

title = "EasyCRM gitleaks config"

[extend]
# Keep the whole upstream ruleset. This file only ever subtracts, and only by exact literal.
useDefault = true

[allowlist]
description = "Env-var-defaulted local development credentials in application.yml."
# Each regex matches the COMPLETE ${VAR:default} construct, not the bare value and not the
# property name. That scoping is deliberate and it is what makes this list safe to keep once
# AWS exists (Phase 3): every one of these three properties holds a real credential in a real
# environment, and a hardcoded production value on the same line would NOT match any entry
# below -- it would still fail the scan.
regexes = [
  # spring.datasource.password -- local Postgres role, created by docker-compose.yml
  '''\$\{DB_PASSWORD:easycrm_app\}''',
  # spring.flyway.password -- local migration role, same
  '''\$\{FLYWAY_PASSWORD:easycrm_owner\}''',
  # easycrm.jwt.secret -- self-describing dev value with no production counterpart
  '''\$\{JWT_SECRET:0123456789-0123456789-0123456789-devsecret\}''',
]
```

- [ ] **Step 4: Confirm the gitleaks CLI surface before writing the step**

The CLI changed at 8.19: `gitleaks detect` was superseded by `gitleaks git` and `gitleaks dir`. Confirm the flags for this exact version rather than trusting a pre-8.19 tutorial:

```bash
docker run --rm zricethezav/gitleaks:v8.30.1 git --help
```

Expected: a help page listing `--log-opts`, `--config/-c`, `--redact`, `--no-banner`. If any flag below is absent, use the name this help page gives and note the correction in the commit message.

- [ ] **Step 5: Add the `supply-chain` job with the gitleaks step**

In `.github/workflows/ci.yml`, add a new job after the existing `check` job (leave `check` untouched):

```yaml
  # Supply-chain scans. A separate job from `check` on purpose: these need no JDK, no Gradle and
  # no Testcontainers, so they report in well under a minute instead of behind a 20-minute test
  # run -- and a failure here is attributable at a glance rather than buried in a Gradle log.
  #
  # These three run in CI only (spec D4). SupplyChainWorkflowTest is what stops that being a
  # silent gap: delete a step here and ./gradlew check fails.
  supply-chain:
    runs-on: ubuntu-latest
    timeout-minutes: 10

    steps:
      - uses: actions/checkout@v7
        with:
          # Full history: on push and schedule gitleaks scans every commit, and a diff-only scan
          # cannot find a secret that entered three commits ago and was never removed.
          fetch-depth: 0

      - name: Secret scan (gitleaks)
        env:
          # Same resolution as the oasdiff step, and for the same reasons: on push, the SHA the
          # branch was at BEFORE the push (not HEAD~1, which is the previous commit and misses
          # N-1 of an N-commit push); on a pull request, the base branch tip.
          BASE_SHA: ${{ github.event_name == 'pull_request' && github.event.pull_request.base.sha || github.event.before }}
        run: |
          set -euo pipefail
          # --redact: findings are printed with the secret value masked. Without it a leaked
          # credential would be re-published in a world-readable Actions log by the very tool
          # that found it.
          COMMON="-c /repo/.gitleaks.toml --redact --no-banner"
          if [ "${{ github.event_name }}" = "pull_request" ] \
             && [ -n "${BASE_SHA:-}" ] \
             && [ "$BASE_SHA" != "0000000000000000000000000000000000000000" ]; then
            echo "Scanning $BASE_SHA..HEAD"
            # shellcheck disable=SC2086 # COMMON is a deliberate flag list, not a filename
            docker run --rm -v "$PWD:/repo:ro" -w /repo \
              zricethezav/gitleaks:v8.30.1 git /repo $COMMON --log-opts="$BASE_SHA..HEAD"
          else
            echo "Scanning full history"
            # shellcheck disable=SC2086 # as above
            docker run --rm -v "$PWD:/repo:ro" -w /repo \
              zricethezav/gitleaks:v8.30.1 git /repo $COMMON
          fi
```

- [ ] **Step 6: Run the test and watch it pass**

```bash
cd backend && ./gradlew test --tests 'com.easycrm.platform.supplychain.SupplyChainWorkflowTest' --console=plain
```

Expected: PASS, 2 tests.

- [ ] **Step 7: Run gitleaks locally against the real tree**

This is the step that proves the allowlist is right rather than merely plausible:

```bash
cd /Users/divyam/Documents/easy-crm && docker run --rm -v "$PWD:/repo:ro" -w /repo \
  zricethezav/gitleaks:v8.30.1 git /repo -c /repo/.gitleaks.toml --redact --no-banner
```

Expected: exit 0, no findings. If it reports the `application.yml` values, the regexes do not match — fix them here, not by widening to a path exclusion.

- [ ] **Step 8: Prove the gate can fail, then revert**

```bash
cd /Users/divyam/Documents/easy-crm
printf 'aws_secret_access_key = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"\n' > /tmp/leak.txt
cp /tmp/leak.txt ./leak-probe.txt
docker run --rm -v "$PWD:/repo:ro" -w /repo \
  zricethezav/gitleaks:v8.30.1 dir /repo -c /repo/.gitleaks.toml --redact --no-banner; echo "exit=$?"
rm -f ./leak-probe.txt /tmp/leak.txt
```

Expected: a finding naming `leak-probe.txt`, `exit=1`. Confirm `leak-probe.txt` is gone before committing. A gate whose failure path has never executed is a gate nobody has tested.

- [ ] **Step 9: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add .gitleaks.toml .github/workflows/ci.yml \
  backend/src/test/java/com/easycrm/platform/supplychain/SupplyChainWorkflowTest.java
git commit -F - <<'EOF'
ci: scan for committed secrets (gitleaks)

The repo is public, so a committed credential is disclosed the moment it
is pushed rather than when someone notices. gitleaks 8.30.1 runs in a new
supply-chain job: the diff on a pull request, full history on push and
schedule -- a diff-only scan cannot find a secret that entered three
commits ago and was never removed.

.gitleaks.toml allowlists the three env-var-defaulted dev credentials in
application.yml by matching the complete ${VAR:default} construct rather
than the bare value or the property name. That scoping is what keeps the
list safe once AWS exists: all three properties hold real credentials in
a real environment, and a hardcoded production value on the same line
would still fail the scan.

SupplyChainWorkflowTest guards it. These scans run in CI only, so nothing
in the build would otherwise notice a deleted step -- it asserts the step
exists, that it is not continue-on-error, and that the image tag is
pinned.

Failure path exercised before commit: a planted AWS key is caught and
exits 1.
EOF
```

---

### Task 2: actionlint — workflow linting

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify: `backend/src/test/java/com/easycrm/platform/supplychain/SupplyChainWorkflowTest.java`

**Interfaces:**
- Consumes: `stepNamed`, `bodyOf` from Task 1.
- Produces: nothing new.

- [ ] **Step 1: Write the failing test**

Append to `SupplyChainWorkflowTest`:

```java
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
```

- [ ] **Step 2: Run the test and watch it fail**

```bash
cd backend && ./gradlew test --tests 'com.easycrm.platform.supplychain.SupplyChainWorkflowTest' --console=plain
```

Expected: FAIL — `no step in job supply-chain whose name starts with Workflow lint`.

- [ ] **Step 3: Run actionlint against the current workflow and read every finding**

Before adding the step, find out what it says about the workflow as it stands:

```bash
cd /Users/divyam/Documents/easy-crm && docker run --rm -v "$PWD:/repo:ro" -w /repo \
  rhysd/actionlint:1.7.12 -color
```

Expect findings in the oasdiff `run:` block — roughly forty lines of shell with `set -uo pipefail`, unquoted expansions and `docker run … | tee` pipelines.

**How to treat each finding** (spec §6):
- A genuine bug → fix it.
- A shellcheck complaint about something Wave 3 reasoned about deliberately (the `pipefail` handling and explicit capture are explained in that block's existing comments) → add a targeted `# shellcheck disable=SCxxxx` on the specific line, with a short comment referencing the reason already written above it.
- **Never** disable a rule globally, and never rewrite the oasdiff logic wholesale. If fixing findings starts turning into rewriting that block, stop and raise it with the user — it is reasoned-about code with a documented opinion.

- [ ] **Step 4: Fix the findings**

Apply the fixes from Step 3. Re-run the command from Step 3 until it exits 0.

- [ ] **Step 5: Add the actionlint step**

In the `supply-chain` job, after the gitleaks step:

```yaml
      - name: Workflow lint (actionlint)
        # Validates workflow syntax and expression types, and shellchecks every `run:` block.
        # Scans .github/workflows by default, so no path argument.
        run: |
          set -euo pipefail
          docker run --rm -v "$PWD:/repo:ro" -w /repo rhysd/actionlint:1.7.12 -color
```

- [ ] **Step 6: Run the test and watch it pass**

```bash
cd backend && ./gradlew test --tests 'com.easycrm.platform.supplychain.SupplyChainWorkflowTest' --console=plain
```

Expected: PASS, 4 tests.

- [ ] **Step 7: Prove the gate can fail, then revert**

```bash
cd /Users/divyam/Documents/easy-crm
cp .github/workflows/ci.yml /tmp/ci.yml.bak
# An undefined `needs` reference -- a real error actionlint is built to catch.
perl -0pi -e 's/^  supply-chain:\n    runs-on: ubuntu-latest/  supply-chain:\n    needs: no-such-job\n    runs-on: ubuntu-latest/m' .github/workflows/ci.yml
docker run --rm -v "$PWD:/repo:ro" -w /repo rhysd/actionlint:1.7.12 -color; echo "exit=$?"
cp /tmp/ci.yml.bak .github/workflows/ci.yml && rm /tmp/ci.yml.bak
git diff --stat .github/workflows/ci.yml
```

Expected: a finding naming `no-such-job`, `exit=1`, and an empty `git diff --stat` after the restore.

- [ ] **Step 8: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add .github/workflows/ci.yml \
  backend/src/test/java/com/easycrm/platform/supplychain/SupplyChainWorkflowTest.java
git commit -F - <<'EOF'
ci: lint the workflow and its shell (actionlint)

actionlint 1.7.12 validates workflow syntax and expression types, and
shellchecks every run: block -- which matters here because the oasdiff
step is forty lines of shell that nothing has ever checked.

Findings in that block are fixed rather than suppressed wholesale. Where
a shellcheck complaint lands on something Wave 3 chose deliberately, the
suppression is per-line and references the reason already written above
it; no rule is disabled globally.

Failure path exercised before commit: an undefined `needs` reference is
caught and exits 1.
EOF
```

---

### Task 3: squawk — unsafe DDL in new migrations

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify: `backend/src/test/java/com/easycrm/platform/supplychain/SupplyChainWorkflowTest.java`
- Create (conditional): `backend/squawk.toml`

**Interfaces:**
- Consumes: `stepNamed`, `bodyOf` from Task 1.
- Produces: nothing new.

- [ ] **Step 1: Write the failing test**

Append to `SupplyChainWorkflowTest`:

```java
    // --- squawk ---------------------------------------------------------------------------

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
```

- [ ] **Step 2: Run the test and watch it fail**

```bash
cd backend && ./gradlew test --tests 'com.easycrm.platform.supplychain.SupplyChainWorkflowTest' --console=plain
```

Expected: FAIL — `no step in job supply-chain whose name starts with Migration lint`.

- [ ] **Step 3: Run squawk against the existing migrations to learn its opinions**

Not to fix them — they are immutable — but to find out which rules this codebase's DDL style trips, so Step 5 knows whether `squawk.toml` is needed:

```bash
cd /Users/divyam/Documents/easy-crm/backend
npx --yes squawk-cli@2.65.0 src/main/resources/db/migration/V3*.sql
```

Read every finding. Expect opinions about RLS `ENABLE`/`FORCE` statements, `V34`'s per-tenant DML backfill loop, and `NOT NULL` columns added to tables that are empty today but will not be in Phase 3.

- [ ] **Step 4: Add the squawk step**

In the `supply-chain` job, after the actionlint step:

```yaml
      - name: Migration lint (squawk)
        env:
          BASE_SHA: ${{ github.event_name == 'pull_request' && github.event.pull_request.base.sha || github.event.before }}
        run: |
          set -euo pipefail
          # Scope: migrations changed in this push or PR, never the whole directory. The 34
          # existing migrations are frozen by Flyway checksums -- editing one breaks every
          # database that has already applied it -- so a finding in one of them is a finding
          # nobody is permitted to act on. The diff is also the rule actually being enforced:
          # NEW ddl must be safe to run against a live database.
          if [ -z "${BASE_SHA:-}" ] || [ "$BASE_SHA" = "0000000000000000000000000000000000000000" ]; then
            echo "No base commit for this event - no migrations to lint."
            exit 0
          fi
          if ! git cat-file -e "$BASE_SHA" 2>/dev/null; then
            echo "Base $BASE_SHA is unreachable (force-push?) - no migrations to lint."
            exit 0
          fi
          # --diff-filter=AM: added and modified. A modified migration is itself a Flyway
          # checksum violation, so squawk flagging it is a second, cheaper alarm on a problem
          # the application would otherwise discover at startup.
          CHANGED=$(git diff --name-only --diff-filter=AM "$BASE_SHA" HEAD \
            -- 'backend/src/main/resources/db/migration/*.sql')
          if [ -z "$CHANGED" ]; then
            echo "No migrations changed in this range."
            exit 0
          fi
          echo "Linting:"
          echo "$CHANGED"
          npm install -g squawk-cli@2.65.0
          # xargs rather than an unquoted expansion: multiple paths must word-split, and doing it
          # this way keeps shellcheck (and therefore the actionlint step above) satisfied.
          echo "$CHANGED" | xargs squawk
```

- [ ] **Step 5: Add `squawk.toml` only if Step 3 found rules that genuinely do not apply**

If and only if needed, create `backend/squawk.toml`:

```toml
# Rules excluded for this codebase, each with the reason. "It was noisy" is not a reason.
excluded_rules = [
  # EXAMPLE SHAPE ONLY -- replace with the real exclusions Step 3 justifies, or delete this
  # file entirely if Step 3 justifies none.
  # "require-concurrent-index-creation",  # <- why this does not apply here
]
```

If the file is created, add `--config squawk.toml` to the `xargs squawk` invocation and note it in the commit message. **If Step 3 justified no exclusions, do not create this file** — an empty suppression file reads, a year later, as "squawk's rules do not apply here."

- [ ] **Step 6: Run the test and watch it pass**

```bash
cd backend && ./gradlew test --tests 'com.easycrm.platform.supplychain.SupplyChainWorkflowTest' --console=plain
```

Expected: PASS, 7 tests.

- [ ] **Step 7: Prove the gate can fail, then revert**

```bash
cd /Users/divyam/Documents/easy-crm/backend
cat > /tmp/V99__probe.sql <<'SQL'
ALTER TABLE customer ADD COLUMN probe text NOT NULL;
SQL
npx --yes squawk-cli@2.65.0 /tmp/V99__probe.sql; echo "exit=$?"
rm -f /tmp/V99__probe.sql
```

Expected: a finding on the unsafe `NOT NULL` add (adding a required field rewrites the table and takes an `ACCESS EXCLUSIVE` lock), `exit=1`. Written to `/tmp`, never into the migrations directory — a stray `V99` there would be picked up by Flyway on the next local run.

- [ ] **Step 8: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add .github/workflows/ci.yml \
  backend/src/test/java/com/easycrm/platform/supplychain/SupplyChainWorkflowTest.java
# add backend/squawk.toml too, only if Step 5 created it
git commit -F - <<'EOF'
ci: lint new migrations for unsafe DDL (squawk)

Phase 3 puts this schema in front of a live database, and the first
migration that takes an ACCESS EXCLUSIVE lock on a populated table is an
outage. squawk 2.65.0 catches that class before it ships.

Scoped to migrations changed in the push or PR, not the whole directory.
The 34 existing migrations are frozen by Flyway checksums -- editing one
breaks every database that has applied it -- so a finding in one of them
would be a finding nobody is permitted to act on. The diff is also the
rule actually being enforced: new DDL must be safe against a live
database. The rejected alternative, linting everything and suppressing
today's findings, bakes in a list that outlives its reason.

The base SHA is resolved the same way the oasdiff step resolves it, and
the all-zeros first-push case and an unreachable base both pass with a
message rather than erroring.

Failure path exercised before commit: an unsafe NOT NULL column add is
caught and exits 1.
EOF
```

---

### Task 4: Dependabot

**Files:**
- Create: `.github/dependabot.yml`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing. No workflow test — Dependabot is not a `ci.yml` step, and GitHub validates this file itself.

- [ ] **Step 1: Write the config**

Create `.github/dependabot.yml`:

```yaml
# Weekly dependency update PRs. See docs/superpowers/specs/2026-09-12-supply-chain-design.md section 7.
#
# Dependabot rather than Renovate (spec D3): native to GitHub, so no third-party app with write
# access to a repo that now holds an NVD_API_KEY secret. Revisit Renovate if PR volume becomes
# unmanageable or buildSrc's own plugin versions start drifting.
version: 2

updates:
  # The Gradle root is backend/, NOT the repository root. Pointed at "/" this finds nothing and
  # reports nothing -- silently, which is the worst way for a scanner to be wrong.
  - package-ecosystem: gradle
    directory: /backend
    schedule:
      interval: weekly
      day: monday
      time: "04:00"
      timezone: Asia/Kolkata
    # A repo that has never run dependency updates has a backlog. Cap the first wave rather than
    # deferring the bot.
    open-pull-requests-limit: 5
    groups:
      # Boot's modules move in lockstep; one PR per module would be noise for a single upgrade.
      spring-boot:
        patterns:
          - "org.springframework.boot:*"
          - "org.springframework:*"
      test-tooling:
        patterns:
          - "org.testcontainers:*"
          - "org.junit*:*"
          - "com.tngtech.archunit:*"
    commit-message:
      prefix: build

  - package-ecosystem: github-actions
    directory: /
    schedule:
      interval: weekly
      day: monday
      time: "04:00"
      timezone: Asia/Kolkata
    open-pull-requests-limit: 5
    commit-message:
      prefix: ci
```

- [ ] **Step 2: Validate the file is well-formed before pushing**

```bash
cd /Users/divyam/Documents/easy-crm && python3 -c "import yaml,sys; yaml.safe_load(open('.github/dependabot.yml')); print('ok')"
```

Expected: `ok`.

- [ ] **Step 3: Establish what Dependabot actually covers (spec D8)**

The convention plugin sources `palantirJavaFormat` and `findsecbugs` from `gradle/libs.versions.toml`, so those versions **are** in a file Dependabot reads. What is unclear is whether it resolves the `buildSrc` build's own `implementation(...)` declarations, which use `libs.versions.spotless.get()` interpolation.

Record the finding — do not guess. After pushing, check the repo's Dependabot page for what it detected:

```bash
gh api repos/divyam-agarwal/eazy-crm/dependabot/alerts --jq '.[].dependency.manifest_path' 2>/dev/null | sort -u
```

If this is inconclusive at plan time (likely — Dependabot needs a scan cycle first), write down in Task 6's HANDOFF entry that coverage of `buildSrc` is **unverified**, rather than claiming either way.

- [ ] **Step 4: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add .github/dependabot.yml
git commit -F - <<'EOF'
build: open weekly dependency update PRs (Dependabot)

Native to GitHub rather than Renovate, so no third-party app gets write
access to a repo that now holds an NVD_API_KEY secret. Two ecosystems:
gradle rooted at /backend (the Gradle root is backend/, and pointing it
at / would find nothing and report nothing, silently), and github-actions
at / for the pinned actions in ci.yml.

Grouped and capped at five open PRs, because a repo that has never run
dependency updates has a backlog -- the cap is the mitigation, not
deferring the bot.

Worth knowing: this makes Dependabot the first thing in this repo's
history to open pull requests as routine practice, which rehearses the
pull_request CI path before branch protection makes it mandatory. Every
such PR is also a live test of whether check passes from a clean
checkout, which post-merge-only CI has never been able to tell us.
EOF
```

---

### Task 5: OWASP Dependency-Check

**Files:**
- Modify: `backend/gradle/libs.versions.toml`
- Modify: `backend/buildSrc/build.gradle.kts`
- Modify: `backend/buildSrc/src/main/kotlin/easycrm.quality-conventions.gradle.kts`
- Modify: `.github/workflows/ci.yml`
- Modify: `backend/src/test/java/com/easycrm/platform/supplychain/SupplyChainWorkflowTest.java`

**Interfaces:**
- Consumes: `jobNamed`, `triggers`, `stepNamed` from Task 1.
- Produces: the Gradle task `dependencyCheckAggregate`, invoked by the CI job and available locally.

- [ ] **Step 1: Write the failing test**

Append to `SupplyChainWorkflowTest`:

```java
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
```

- [ ] **Step 2: Run the test and watch it fail**

```bash
cd backend && ./gradlew test --tests 'com.easycrm.platform.supplychain.SupplyChainWorkflowTest' --console=plain
```

Expected: FAIL — `no job named dependency-check in ci.yml`.

- [ ] **Step 3: Add the version to the catalog**

In `backend/gradle/libs.versions.toml`, under `[versions]`, after the `springdoc` entry:

```toml
# --- supply chain, added by the Wave 1.5 slice ---
# Verified against plugins.gradle.org/m2 on 2026-09-12: latest and release are both 13.0.0,
# published 2026-08-03. Version 9 onward requires an NVD API key for a usable sync rate --
# NVD_API_KEY is a repository secret, and ~/.gradle/gradle.properties holds nvdApiKey locally.
dependencyCheck = "13.0.0"
```

And under `[plugins]`:

```toml
dependencyCheck = { id = "org.owasp.dependencycheck", version.ref = "dependencyCheck" }
```

- [ ] **Step 4: Put the plugin on the buildSrc classpath**

In `backend/buildSrc/build.gradle.kts`, add to the `dependencies` block:

```kotlin
    implementation("org.owasp.dependencycheck:org.owasp.dependencycheck.gradle.plugin:${libs.versions.dependencyCheck.get()}")
```

- [ ] **Step 5: Apply and configure it in the convention plugin**

In `backend/buildSrc/src/main/kotlin/easycrm.quality-conventions.gradle.kts`, add `id("org.owasp.dependencycheck")` to the `plugins` block, and append this at the end of the file:

```kotlin
// Published-CVE scanning over the dependency tree. Two things about this are deliberate.
//
// It is NOT wired into `check` (spec D7). Every other gate in this file runs on every build;
// this one would put an NVD database sync in front of it. Invoke it explicitly:
//     ./gradlew dependencyCheckAggregate
//
// And failBuildOnCVSS is set above the maximum possible score, so it never fails the build even
// when invoked directly -- matching the CI job's continue-on-error (spec D1). The flip trigger
// for both is branch protection: at that point a finding lands in a PR queue with someone to
// triage it, rather than on a main branch that deploys itself.
dependencyCheck {
    // CVSS v3 tops out at 10.0. 11 means "never fail", which is the documented idiom.
    failBuildOnCVSS = 11f
    formats = listOf("HTML", "JSON")

    // Two sources, one wiring. CI supplies the environment variable from the repository secret;
    // a local run supplies the Gradle property from ~/.gradle/gradle.properties, which is
    // outside the repository and therefore outside both git and gitleaks. Without either, the
    // scan still runs -- the NVD sync is just rate-limited into the tens of minutes.
    val nvdKey = providers.gradleProperty("nvdApiKey").orElse(providers.environmentVariable("NVD_API_KEY"))
    if (nvdKey.isPresent) {
        nvd { apiKey = nvdKey.get() }
    } else {
        logger.lifecycle("No nvdApiKey property or NVD_API_KEY env var - the NVD sync will be rate-limited.")
    }
}
```

- [ ] **Step 6: Verify the Gradle wiring, and that `check` did not grow a dependency**

```bash
cd backend
./gradlew tasks --group=other --console=plain | grep -i dependencyCheck
./gradlew check --dry-run --console=plain | grep -i dependencyCheck && echo "REGRESSION: check now depends on it" || echo "ok: check is unchanged"
```

Expected: the `dependencyCheck*` tasks are listed, and the second command prints `ok: check is unchanged`.

- [ ] **Step 7: Add the `dependency-check` job and the nightly trigger**

In `.github/workflows/ci.yml`, extend the trigger block:

```yaml
on:
  push:
    branches: [main]
  pull_request:
  # Nightly, for the NVD database refresh. 01:30 UTC is ~07:00 IST. Without this the database
  # only refreshes when someone pushes, so a quiet week reports against a stale database.
  schedule:
    - cron: '30 1 * * *'
```

And add the job:

```yaml
  # Published-CVE scanning. Reports, deliberately: a CVE published overnight in a transitive
  # dependency would otherwise redden main with no code change and nobody to attribute it to --
  # the same reasoning that made the oasdiff step non-blocking. Flip continue-on-error to false
  # when branch protection exists (roadmap item 8) and findings land in a PR queue.
  dependency-check:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    continue-on-error: true
    defaults:
      run:
        working-directory: backend

    steps:
      - uses: actions/checkout@v7

      - name: Set up JDK 25
        uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: '25'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v6

      # The NVD database is large and slow to build from cold. A miss is slow, not broken, so
      # this must never be the reason the job fails.
      - name: Cache the NVD database
        uses: actions/cache@v6
        with:
          path: backend/build/dependency-check-data
          key: nvd-${{ github.run_id }}
          restore-keys: nvd-

      - name: Dependency-Check
        env:
          NVD_API_KEY: ${{ secrets.NVD_API_KEY }}
        run: ./gradlew dependencyCheckAggregate --no-daemon --console=plain

      - name: Summarise findings
        if: always()
        run: |
          set -euo pipefail
          REPORT=build/reports/dependency-check-report.json
          echo "## Dependency-Check" >> "$GITHUB_STEP_SUMMARY"
          if [ ! -f "$REPORT" ]; then
            echo "No report produced - see the step log." >> "$GITHUB_STEP_SUMMARY"
            exit 0
          fi
          # Count vulnerable dependencies rather than raw CVEs: one bad library with forty CVEs
          # is one thing to upgrade, and a CVE count would report it as forty problems.
          COUNT=$(python3 -c "import json;d=json.load(open('$REPORT'));print(sum(1 for x in d.get('dependencies',[]) if x.get('vulnerabilities')))")
          echo "Vulnerable dependencies: **$COUNT**" >> "$GITHUB_STEP_SUMMARY"

      - name: Upload the report
        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: dependency-check-report
          path: backend/build/reports/dependency-check-report.*
          retention-days: 7
```

- [ ] **Step 8: Run the test and watch it pass**

```bash
cd backend && ./gradlew test --tests 'com.easycrm.platform.supplychain.SupplyChainWorkflowTest' --console=plain
```

Expected: PASS, 9 tests.

- [ ] **Step 9: Run the real scan locally once**

This is a cold cache and will take a while even with a key. Let it finish rather than assuming it hung:

```bash
cd backend && ./gradlew dependencyCheckAggregate --console=plain
```

Expected: completes, writes `build/reports/dependency-check-report.html`. Open it and read the findings — **do not fix any of them in this slice** (spec §12). Note the count for Task 6.

- [ ] **Step 10: Confirm the full gate is still green and count the tests**

```bash
cd backend && ./gradlew clean check --console=plain
```

Expected: BUILD SUCCESSFUL. Count the new total with the two-project snippet from `HANDOFF.md` §0 — it should be 591 plus the number of tests in `SupplyChainWorkflowTest`.

- [ ] **Step 11: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/gradle/libs.versions.toml backend/buildSrc/build.gradle.kts \
  backend/buildSrc/src/main/kotlin/easycrm.quality-conventions.gradle.kts \
  .github/workflows/ci.yml \
  backend/src/test/java/com/easycrm/platform/supplychain/SupplyChainWorkflowTest.java
git commit -F - <<'EOF'
ci: scan dependencies for published CVEs (OWASP Dependency-Check)

Plugin 13.0.0, applied through the quality-conventions plugin so both
projects are analysed, and deliberately NOT wired into check: every other
gate runs on every build, and this one would put an NVD database sync in
front of it. Invoke it with ./gradlew dependencyCheckAggregate.

It reports rather than blocks, in both places -- continue-on-error on the
job and failBuildOnCVSS above the maximum possible score. A CVE published
overnight in a transitive dependency would otherwise redden main with no
code change and nobody to attribute it to, which is how gates get
ignored. The flip trigger for both is branch protection, at which point
findings land in a PR queue with someone to triage them.

Credentials resolve from two sources through one wiring: NVD_API_KEY from
the environment in CI, the nvdApiKey Gradle property from
~/.gradle/gradle.properties locally -- outside the repo, so outside both
git and gitleaks. Without either the scan still runs, just rate-limited.

A nightly schedule refreshes the database; without it a quiet week
reports against a stale one.
EOF
```

---

### Task 6: Documentation

**Files:**
- Modify: `docs/superpowers/engineering-challenges.md`
- Modify: `docs/ROADMAP.md`
- Modify: `docs/superpowers/HANDOFF.md`

**Interfaces:**
- Consumes: the outcomes of Tasks 1–5.
- Produces: nothing.

`CLAUDE.md` makes this part of the same change, not a follow-up.

- [ ] **Step 1: Add the engineering-challenge entries**

Read the template at the bottom of `docs/superpowers/engineering-challenges.md` and follow it exactly (Problem → why it's hard → Solution → Lesson). Continue the existing numbering — the log currently runs to #69.

Candidates, in descending order of how well they qualify. Log the ones that are true after implementation; do not log a routine one to make up volume:

1. **The SnakeYAML `on:` boolean-key trap.** YAML 1.1 resolves the bare token `on` to boolean true, so every GitHub Actions trigger block parses under `Boolean.TRUE` rather than `"on"`. A test asserting on `get("on")` returns null and passes vacuously — it fails exactly the way the thing it guards fails, which is the same shape as challenge #68's RLS-blind post-check. Strong candidate.
2. **Why squawk lints the diff and not the tree.** Flyway checksums make applied migrations immutable, so a linter pointed at all of them can only produce findings nobody is permitted to act on. The general lesson: when the artefact under test is immutable, the correct scope for a linter is the change, not the state.
3. **Allowlisting a secret by its full `${VAR:default}` construct.** Allowlisting the value would exempt a future hardcoded production credential on the same line; allowlisting the property name would exempt the property forever. Matching the complete env-var-indirected form is what keeps the entry safe once those three properties hold real credentials in Phase 3.

- [ ] **Step 2: Check whether `annotations-reference.md` owes a row**

```bash
grep -nE '@(DisplayName|Test|TempDir)' docs/superpowers/annotations-reference.md | head
```

`SupplyChainWorkflowTest` uses `@Test` and `@DisplayName`, both long-established in this repo. If both already have rows, this slice owes nothing — say so explicitly in the commit rather than leaving it ambiguous.

- [ ] **Step 3: Update the roadmap**

In `docs/ROADMAP.md`:
- Part 6, item 2: strike it through and mark **DONE 2026-09-12** with the merge SHA, following the shape item 1 already uses.
- §6.1 "If you only do three things": item 2 is done; the remaining two are Wave 1.6 and settling D-g.
- Part 5, the Build/CI row: move Wave 1.5 out of "Left".
- §1.3: note that CI now has three jobs, and that `supply-chain` blocks while `dependency-check` reports.

Also fix the two numbering slips found while reading it on 2026-09-12, since this commit is already touching the file:
- **Phase 4 and Phase 5 both number an item 18.** Phase 4 ends at "18 — SP4 scaling policies" and Phase 5 opens at "18 — prod environment". Part 6's ranking has no such collision.
- **The baseline header says `main` at `4baa4b4`.** It is now `ac2fc63`, and everything is pushed.

- [ ] **Step 4: Update the handoff**

In `docs/superpowers/HANDOFF.md` §0, replace the stale "Nothing is in flight. `main` is at `4baa4b4` and is **12 commits ahead of `origin/main`**" block. That warning has been false since the reprioritisation commit was pushed; leaving it would have the next session reconcile a push that already happened.

Record: the slice, its commits, the new test total, what Dependabot was and was not verified to cover (Task 4 Step 3), the Dependency-Check finding count from Task 5 Step 9 — **and the fact that acting on those findings is explicitly the next decision, not something this slice did.**

- [ ] **Step 5: Verify the full gate one last time**

```bash
cd backend && ./gradlew clean check --console=plain
```

Expected: BUILD SUCCESSFUL. Report the exact test count; do not claim a number you did not read off this run.

- [ ] **Step 6: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add docs/
git commit -F - <<'EOF'
docs: record the supply-chain slice

Challenge entries for what was non-obvious, roadmap item 2 struck, and
the handoff brought up to date.

Also corrects two things in the roadmap that this commit was already
touching: Phase 4 and Phase 5 both numbered an item 18, and the baseline
header still named 4baa4b4 as the tip with twelve commits unpushed --
both stale since the 2026-09-12 reprioritisation was pushed.
EOF
```

---

## Self-Review

**Spec coverage.** §3 CI shape → Tasks 1, 5. §4 gitleaks → Task 1. §5 squawk → Task 3. §6 actionlint → Task 2. §7 Dependabot → Task 4. §8 Dependency-Check → Task 5. §9 guarding the guards → assertions distributed across Tasks 1, 2, 3, 5. §10 prove-it-can-fail → Task 1 Step 8, Task 2 Step 7, Task 3 Step 7; Dependency-Check has no failure path by design and Task 5 Step 9 verifies the report renders instead. §11 risks → Dependabot PR volume capped in Task 4; actionlint scope-creep escape hatch in Task 2 Step 3; squawk rule-tuning in Task 3 Step 5; cold-cache warning in Task 5 Step 9. §12 out of scope → no task touches Trivy, branch protection, SonarQube, the SpotBugs baseline, or `src/main/java`. §13 task sketch → reorganised one-task-per-tool so each ends green; the spec's Task 1 (a standalone failing test) is folded into Task 1 here, which avoids a four-task window with a red build.

**Placeholder scan.** One conditional artefact remains — `backend/squawk.toml` in Task 3 Step 5 — and it is conditional on a finding, with explicit instructions for both branches and a written prohibition on creating it empty. The example rule inside it is labelled as shape-only. Task 1 Step 4 verifies the gitleaks CLI surface before the flags are used, rather than asserting flag names on trust. No TBDs.

**Type consistency.** `stepNamed(String, String)`, `stepsOf(String)`, `jobNamed(String)`, `triggers()` and `bodyOf(Map)` are defined in Task 1 and used with those exact signatures in Tasks 2, 3 and 5. Job names `supply-chain` and `dependency-check` are consistent between the YAML and every assertion. Step-name prefixes `Secret scan` / `Workflow lint` / `Migration lint` match between the YAML `name:` fields and the `stepNamed` calls. The catalog key `dependencyCheck` is consistent across `libs.versions.toml`, `buildSrc/build.gradle.kts` and the convention plugin.
