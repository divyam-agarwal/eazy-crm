# Build Hygiene (Wave 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the repo its first automated quality gate — one `./gradlew clean check` that formats, finds bugs, and measures coverage, enforced in GitHub Actions on every push and PR.

**Architecture:** A `buildSrc` convention plugin (`easycrm.quality-conventions`) applied explicitly by both real Gradle projects carries Spotless, SpotBugs and JaCoCo config; a `gradle/libs.versions.toml` catalog carries every version and the comment explaining why it is pinned. Existing violations are handled asymmetrically: formatting is fixed in one big-bang commit, SpotBugs findings are baselined so the gate fails only on new ones.

**Tech Stack:** Gradle 9.6.1 (Kotlin DSL), JDK 25 toolchain / JDK 21 daemon, Spotless + palantir-java-format, SpotBugs + find-sec-bugs, JaCoCo, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-01-build-hygiene-design.md`

## Global Constraints

- **No application code changes.** The only production-source diff this slice may produce is mechanical reformatting (spec D11). If a task finds itself editing logic, stop and report.
- **The Gradle project root is `backend/`, not the repo root.** Every `./gradlew` command runs from `backend/`. The CI workflow needs `working-directory: backend`.
- **Never format `backend/src/main/resources/db/migration/*.sql`** (spec D7). Flyway checksums applied migrations; a reformatted byte fails `flyway validate` against every database that already ran it — and CI will *not* catch this, because a fresh Testcontainers database recomputes the checksum from the new text. There are 32 migrations.
- **Never format `backend/src/main/resources/templates/quotation.xhtml`** (spec D7). Thymeleaf XML in `mode: XML`; whitespace is load-bearing for PDF layout and untested.
- **The test baseline is 519 tests, 0 failures, 0 errors** — 496 root + 23 `platform-primitives`. Count both projects with the `find`/`awk` snippet in `HANDOFF.md` §0; a root-only `find` reports a phantom 496.
- **A `--tests` filter must be project-qualified:** `./gradlew :test --tests '…'` or `./gradlew :platform:platform-primitives:test --tests '…'`. Unqualified, Gradle applies it to both projects and fails on the one with no match.
- **Commits:** author as `divyam <divyam.0444@gmail.com>` via plain `git commit`. Never add a `Co-Authored-By: Claude` trailer or mention Claude/AI in any commit message.
- **Docker must be running** before any `./gradlew test`: `open -a Docker`, then wait for `docker info` to succeed.
- **Branch:** all work happens on a feature branch `build-hygiene` off `main`, never on `main` directly.

---

## File Structure

**Created:**
- `backend/gradle/libs.versions.toml` — every dependency and plugin version, with its justifying comment
- `backend/buildSrc/settings.gradle.kts` — makes the catalog visible to buildSrc's own build
- `backend/buildSrc/build.gradle.kts` — declares the plugin jars the convention plugin applies
- `backend/buildSrc/src/main/kotlin/easycrm.quality-conventions.gradle.kts` — the shared gate config
- `backend/gradle.properties` — daemon JVM args for palantir-java-format
- `backend/config/spotbugs/exclude.xml` — permanent category-level exclusions, empty at first
- `backend/config/spotbugs/baseline.xml` — today's SpotBugs findings (Task 4 generates it)
- `.git-blame-ignore-revs` — repo root; the reformat commit SHA
- `.github/workflows/ci.yml` — repo root; the Actions workflow

**Modified:**
- `backend/build.gradle.kts` — applies the convention plugin, versions move to the catalog
- `backend/platform/platform-primitives/build.gradle.kts` — same
- All 323 `.java` files under `backend/src/` and `backend/platform/` — Task 3, mechanical only
- `docs/superpowers/HANDOFF.md`, `docs/superpowers/engineering-challenges.md` — Task 7

---

## Task 1: Version catalog and convention plugin scaffolding

Pure refactor. No gate is switched on and no behaviour changes; the deliverable is that both projects still build and all 519 tests still pass with every version now coming from one file.

**Files:**
- Create: `backend/gradle/libs.versions.toml`
- Create: `backend/buildSrc/settings.gradle.kts`
- Create: `backend/buildSrc/build.gradle.kts`
- Create: `backend/buildSrc/src/main/kotlin/easycrm.quality-conventions.gradle.kts`
- Modify: `backend/build.gradle.kts`
- Modify: `backend/platform/platform-primitives/build.gradle.kts`

**Interfaces:**
- Produces: a plugin id `easycrm.quality-conventions`, appliable as `id("easycrm.quality-conventions")` from any project's `plugins {}` block. Tasks 2, 4 and 5 add Spotless, SpotBugs and JaCoCo config *inside* this one file.
- Produces: version catalog aliases used by later tasks — `libs.plugins.spotless`, `libs.plugins.spotbugs`, `libs.findsecbugs`, `libs.versions.palantirJavaFormat`.

- [ ] **Step 1: Create the branch and confirm the baseline**

```bash
cd /Users/divyam/Documents/easy-crm
git checkout -b build-hygiene
open -a Docker
until docker info >/dev/null 2>&1; do sleep 2; done
cd backend && ./gradlew clean test
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'failures="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "failures:", s}'
```

Expected: `tests: 519`, `failures: 0`. If the number differs, STOP and reconcile before continuing — every later step assumes it.

- [ ] **Step 2: Resolve the current plugin versions**

Do not copy versions from memory; this project has been bitten three times by stale coordinates (ArchUnit 1.3.0, `bucket4j-core`, openhtmltopdf 1.1.x). Look up the current release of each on the Gradle Plugin Portal / Maven Central and record what you find:

```bash
curl -s "https://plugins.gradle.org/api/gradle/1.0/search?q=com.diffplug.spotless" | head -c 500
curl -s "https://plugins.gradle.org/api/gradle/1.0/search?q=com.github.spotbugs" | head -c 500
curl -s "https://search.maven.org/solrsearch/select?q=g:com.h3xstream.findsecbugs+AND+a:findsecbugs-plugin&core=gav&rows=3&wt=json"
curl -s "https://search.maven.org/solrsearch/select?q=g:com.palantir.javaformat+AND+a:palantir-java-format&core=gav&rows=3&wt=json"
```

Requirements the chosen versions must satisfy, in order of importance:
1. **Gradle 9.6.1 compatibility.** Spotless 7.x and spotbugs-gradle-plugin 6.x are the known-good major lines; anything older may not configure under Gradle 9's stricter configuration cache and task API.
2. **JDK 25 compatibility** for anything that parses bytecode or source.

If a lookup fails or is ambiguous, use these floors and note the actual resolved version in the TOML comment: Spotless `7.0.2`, spotbugs-gradle-plugin `6.1.13`, find-sec-bugs `1.13.0`, palantir-java-format `2.50.0`.

- [ ] **Step 3: Write the version catalog**

Create `backend/gradle/libs.versions.toml`. Every comment below is carried over from `build.gradle.kts` and is the point of the exercise — the numbers are recoverable, the reasoning is not.

```toml
[versions]
springBoot = "4.1.0"
springDependencyManagement = "1.1.7"
# 1.1.x was never cut upstream (as of 2026-07-28: latest on Maven Central is 1.0.10,
# dev branch pom.xml reads 1.0.11-SNAPSHOT). 1.0.10 IS the latest.
openhtmltopdf = "1.0.10"
jjwt = "0.12.6"
# The artifact id is JDK-qualified as of 8.10: com.bucket4j:bucket4j-core is a stale
# coordinate that silently resolves to 8.1.x.
bucket4j = "8.19.0"
# The Spring Boot 4.1 BOM does not manage Testcontainers module versions; pin the BOM.
testcontainers = "1.21.3"
# 1.4.x parses Java 25 bytecode. 1.3.0 silently skips it and every rule passes vacuously.
archunit = "1.4.1"
junit = "5.13.4"
# --- quality tooling, added by the build-hygiene slice ---
spotless = "7.0.2"
spotbugs = "6.1.13"
# Pinned separately from the Spotless plugin: the formatter version decides the actual
# output bytes, so bumping it reformats the tree. Treat a bump as its own commit.
palantirJavaFormat = "2.50.0"
findsecbugs = "1.13.0"

[libraries]
openhtmltopdf-pdfbox = { module = "com.openhtmltopdf:openhtmltopdf-pdfbox", version.ref = "openhtmltopdf" }
jjwt-api = { module = "io.jsonwebtoken:jjwt-api", version.ref = "jjwt" }
jjwt-impl = { module = "io.jsonwebtoken:jjwt-impl", version.ref = "jjwt" }
jjwt-jackson = { module = "io.jsonwebtoken:jjwt-jackson", version.ref = "jjwt" }
bucket4j-core = { module = "com.bucket4j:bucket4j_jdk17-core", version.ref = "bucket4j" }
testcontainers-bom = { module = "org.testcontainers:testcontainers-bom", version.ref = "testcontainers" }
archunit-junit5 = { module = "com.tngtech.archunit:archunit-junit5", version.ref = "archunit" }
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
findsecbugs = { module = "com.h3xstream.findsecbugs:findsecbugs-plugin", version.ref = "findsecbugs" }

[plugins]
springBoot = { id = "org.springframework.boot", version.ref = "springBoot" }
springDependencyManagement = { id = "io.spring.dependency-management", version.ref = "springDependencyManagement" }
spotless = { id = "com.diffplug.spotless", version.ref = "spotless" }
spotbugs = { id = "com.github.spotbugs", version.ref = "spotbugs" }
```

- [ ] **Step 4: Make the catalog visible to buildSrc**

This step exists because of a real Gradle trap: `buildSrc` is a separate build with its own settings, so it does **not** see the main build's version catalog unless told to.

Create `backend/buildSrc/settings.gradle.kts`:

```kotlin
// buildSrc is a separate Gradle build and does not inherit the main build's version
// catalog. Pointing it at the same TOML keeps one source of versions across both.
dependencyResolutionManagement {
    repositories { gradlePluginPortal() }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}
```

Create `backend/buildSrc/build.gradle.kts`:

```kotlin
plugins { `kotlin-dsl` }

repositories { gradlePluginPortal() }

// A precompiled script plugin can only apply plugins whose implementation is on the
// buildSrc classpath, so each one is declared here as an ordinary dependency using its
// *marker* coordinate (id:id.gradle.plugin).
dependencies {
    implementation("com.diffplug.spotless:spotless-plugin-gradle:${libs.versions.spotless.get()}")
    implementation("com.github.spotbugs:com.github.spotbugs.gradle.plugin:${libs.versions.spotbugs.get()}")
}
```

- [ ] **Step 5: Create the convention plugin, empty for now**

Create `backend/buildSrc/src/main/kotlin/easycrm.quality-conventions.gradle.kts`:

```kotlin
// Shared quality gates for every EasyCRM Gradle project.
//
// Applied explicitly by each real project rather than through allprojects{}: settings
// includes ":platform:platform-primitives", which materialises an implicit empty
// ":platform" project that allprojects{} would wrongly configure, while subprojects{}
// would miss the root project where all 198 main sources live.
//
// KNOWN GRADLE LIMITATION: type-safe `libs.` accessors are NOT available inside a
// precompiled script plugin. Versions needed at *configuration* time here (find-sec-bugs)
// are literals with a comment, not catalog references. Do not spend time trying to make
// `libs` work in this file — it does not, by design.

plugins {
    java
}

// Spotless config arrives in Task 2, SpotBugs in Task 4, JaCoCo in Task 5.
```

- [ ] **Step 6: Switch both build files to the catalog and apply the convention plugin**

In `backend/build.gradle.kts`, change the `plugins` block and every version-bearing dependency. Keep every existing comment that is not moving to the TOML:

```kotlin
plugins {
    java
    id("easycrm.quality-conventions")
    alias(libs.plugins.springBoot)
    alias(libs.plugins.springDependencyManagement)
}
```

and in `dependencies`, replace the pinned coordinates:

```kotlin
    implementation(libs.openhtmltopdf.pdfbox)
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)
    implementation(libs.bucket4j.core)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.archunit.junit5)
```

In `backend/platform/platform-primitives/build.gradle.kts`:

```kotlin
plugins {
    `java-library`
    id("easycrm.quality-conventions")
}
```

and in `dependencies`:

```kotlin
    api(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.archunit.junit5)
```

Leave `caffeine` unpinned — its comment already says the version comes from the Boot BOM and must not be pinned here.

- [ ] **Step 7: Verify nothing changed**

```bash
cd backend && ./gradlew clean test
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
```

Expected: `tests: 519`, build SUCCESSFUL. A refactor that changes a test count has changed a dependency version — go back and find which.

- [ ] **Step 8: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/gradle/libs.versions.toml backend/buildSrc backend/build.gradle.kts \
        backend/platform/platform-primitives/build.gradle.kts
git commit -m "build: add version catalog and quality-conventions plugin scaffolding"
```

---

## Task 2: Configure Spotless, and watch its gate fail

The deliverable is a **failing** `spotlessCheck` listing unformatted files. That failure is this gate's prove-it-can-fail evidence (spec §9) and is collected here for free, before the tree is formatted. Nothing is on `main` and CI does not exist yet, so a red branch between Tasks 2 and 3 is safe and intended.

**Files:**
- Modify: `backend/buildSrc/src/main/kotlin/easycrm.quality-conventions.gradle.kts`
- Create: `backend/gradle.properties`

**Interfaces:**
- Consumes: the `easycrm.quality-conventions` plugin from Task 1.
- Produces: `spotlessCheck` and `spotlessApply` tasks on both projects; `spotlessCheck` is auto-wired into `check` by the plugin.

- [ ] **Step 1: Add the daemon JVM args palantir-java-format needs**

palantir-java-format reaches into `jdk.compiler` internals, and the JVM that must be opened is the **Gradle daemon** — which runs on the shell default JDK 21, not the toolchain's JDK 25. Create `backend/gradle.properties`:

```properties
# palantir-java-format (via Spotless) accesses jdk.compiler internals that are strongly
# encapsulated since JDK 16. These exports open them for the *daemon* JVM, which is where
# Spotless runs its formatter -- note that is the shell default JDK (21 on this machine),
# NOT the toolchain JDK 25 used to compile and test. See HANDOFF.md section 5.
#
# Setting org.gradle.jvmargs at all replaces Gradle's default heap, so -Xmx is restated.
org.gradle.jvmargs=-Xmx2g \
  --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED
```

- [ ] **Step 2: Add Spotless to the convention plugin**

Replace the trailing comment in `easycrm.quality-conventions.gradle.kts` with:

```kotlin
plugins {
    java
    id("com.diffplug.spotless")
}

spotless {
    java {
        // Only this project's own sources. target must be explicit: the default sourceSet
        // scan would also reach generated output under build/.
        target("src/**/*.java")
        // 4-space, 120-col -- the closest match to the style already in the tree, chosen
        // to keep the one-time reformat diff as small as a whole-tree reformat can be.
        palantirJavaFormat("2.50.0")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("yaml") {
        // NOTE the exclusions this does NOT need: only src/main/resources/*.yml is matched,
        // so db/migration/*.sql and templates/quotation.xhtml are out of scope by construction
        // rather than by an exclude rule someone could later delete. See plan Global Constraints.
        target("src/**/*.yml")
        trimTrailingWhitespace()
        endWithNewline()
    }
}
```

If Step 2 of Task 1 resolved a palantir-java-format version other than `2.50.0`, use that value here and keep it equal to `libs.versions.palantirJavaFormat` in the TOML. They cannot be linked automatically — see the known-limitation comment in the convention plugin.

- [ ] **Step 3: Resolve the import-order question**

The spec deliberately does not assert whether `palantirJavaFormat()`'s own import handling can coexist with a custom `importOrder()` reproducing the current `java.*`-last grouping. Find out now, on one file:

```bash
cd backend && ./gradlew :spotlessJavaApply --console=plain 2>&1 | tail -20
git diff --stat src/main/java/com/easycrm/platform/job/TenantJobRunner.java
git diff src/main/java/com/easycrm/platform/job/TenantJobRunner.java | head -40
```

Inspect what happened to the import block. Then:
- **If palantir left import order alone**, add `importOrder("", "java", "javax")` to the `java {}` block to preserve the current grouping (everything, blank line, `java.*`, `javax.*`).
- **If palantir reordered imports itself**, do NOT add `importOrder` — a second ordering step will fight it and produce an unstable format. Palantir's ordering wins; record this in the commit message.

Then revert the probe so Task 3 owns the whole reformat:

```bash
cd /Users/divyam/Documents/easy-crm && git checkout -- backend/src backend/platform
```

- [ ] **Step 4: Run the gate and confirm it FAILS**

```bash
cd backend && ./gradlew spotlessCheck --console=plain 2>&1 | tail -30
```

Expected: **FAIL**, with a message naming unformatted files and pointing at `spotlessApply`. Record the number of files it reports — Task 3 compares against it.

If it *passes*, the `target()` globs are wrong and Spotless is scanning nothing. Do not proceed; a gate that passes vacuously is precisely the failure mode challenge #33 records.

- [ ] **Step 5: Commit the configuration only**

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/gradle.properties backend/buildSrc
git commit -m "build: configure Spotless with palantir-java-format

spotlessCheck fails as expected until the tree is formatted in the next
commit. Verified the gate reports violations rather than passing vacuously."
```

---

## Task 3: Reformat the tree in one mechanical commit

**Files:**
- Modify: every `.java` file under `backend/src/` and `backend/platform/`
- Create: `.git-blame-ignore-revs` (repo root)

**Interfaces:**
- Consumes: `spotlessApply` from Task 2.
- Produces: a green `spotlessCheck`, and a tree that all later tasks format-check cleanly.

- [ ] **Step 1: Apply the formatter**

```bash
cd backend && ./gradlew spotlessApply --console=plain
cd /Users/divyam/Documents/easy-crm
git diff --stat | tail -5
```

Expected: a large diff across the Java tree. Sanity-check the blast radius before committing:

```bash
git diff --name-only | grep -c '\.java$'
git diff --name-only | grep -v '\.java$' || echo "OK: java files only"
```

The second command must print `OK: java files only`. **If any `.sql` file appears, STOP** — a migration has been reformatted and the Flyway checksum constraint in Global Constraints has been violated. Revert everything (`git checkout -- .`) and fix the `target()` globs before retrying.

- [ ] **Step 2: Verify the build and tests are unaffected**

```bash
cd backend && ./gradlew clean check
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
```

Expected: BUILD SUCCESSFUL, `tests: 519`, 0 failures. `check` now includes `spotlessCheck`, so this single command proves both that the format gate is satisfied and that reformatting broke nothing. ArchUnit rules read bytecode and are indifferent to formatting; if one fails, something other than formatting changed.

- [ ] **Step 3: Commit the reformat alone**

```bash
cd /Users/divyam/Documents/easy-crm
git add -u
git commit -m "style: apply Spotless (palantir-java-format) across the tree

Mechanical reformat only, no behaviour change: 519 tests green before and
after. Recorded in .git-blame-ignore-revs in the following commit."
```

- [ ] **Step 4: Record the commit in `.git-blame-ignore-revs`**

```bash
cd /Users/divyam/Documents/easy-crm
REFORMAT_SHA=$(git rev-parse HEAD)
cat > .git-blame-ignore-revs <<EOF
# Revisions to skip in \`git blame\`. GitHub honours this file automatically; locally,
# enable it with:  git config blame.ignoreRevsFile .git-blame-ignore-revs
#
# Whole-tree Spotless (palantir-java-format) reformat, build-hygiene slice, 2026-09-01.
$REFORMAT_SHA
EOF
git config blame.ignoreRevsFile .git-blame-ignore-revs
git add .git-blame-ignore-revs
git commit -m "chore: ignore the whole-tree reformat in git blame"
```

- [ ] **Step 5: Verify blame is readable again**

```bash
git blame -- backend/src/main/java/com/easycrm/platform/job/TenantJobRunner.java | head -5
```

Expected: the commits shown are the original authoring commits, not the reformat SHA.

---

## Task 4: SpotBugs with find-sec-bugs, gated on a baseline

**Files:**
- Modify: `backend/buildSrc/build.gradle.kts` (already declares the plugin from Task 1)
- Modify: `backend/buildSrc/src/main/kotlin/easycrm.quality-conventions.gradle.kts`
- Create: `backend/config/spotbugs/baseline.xml`

**Interfaces:**
- Consumes: the convention plugin from Task 1. Note it does **not** consume `libs.findsecbugs` as an accessor — catalog accessors do not exist inside a precompiled script plugin, so the version is a literal kept manually equal to the TOML's `findsecbugs`. The catalog entry exists so the two are greppable together.
- Produces: `spotbugsMain` on both projects, auto-wired into `check`, failing only on findings absent from the baseline.

- [ ] **Step 1: Add SpotBugs to the convention plugin, reporting only**

Add to `easycrm.quality-conventions.gradle.kts` — extend the `plugins` block with `id("com.github.spotbugs")`, then append:

```kotlin
spotbugs {
    effort = com.github.spotbugs.snom.Effort.MAX
    reportLevel = com.github.spotbugs.snom.Confidence.DEFAULT
    // First pass runs report-only so the finding list can be inspected and baselined.
    // Flipped to true at the end of this task.
    ignoreFailures = true
    excludeFilter = rootProject.file("config/spotbugs/exclude.xml")
}

dependencies {
    // find-sec-bugs is the half that earns SpotBugs its place in this codebase: JWT
    // mint/parse, a bcrypt password path, a permitAll route that renders a PDF, and a
    // rate limiter keyed on an attacker-controlled value.
    //
    // Literal version, not libs.findsecbugs: type-safe catalog accessors do not exist
    // inside a precompiled script plugin. Keep it equal to `findsecbugs` in
    // gradle/libs.versions.toml.
    "spotbugsPlugins"("com.h3xstream.findsecbugs:findsecbugs-plugin:1.13.0")
}

// Test sources are excluded as noise: assertion-heavy test code trips a large number of
// low-value patterns and drowns the signal from main.
tasks.named("spotbugsTest") { enabled = false }

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports.create("html") { required = true }
    reports.create("xml") { required = true }
}
```

Create a minimal `backend/config/spotbugs/exclude.xml` so the `excludeFilter` reference resolves:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- Findings excluded permanently, by category. Distinct from baseline.xml, which is a
     snapshot of pre-existing findings to be worked off over time. Empty for now. -->
<FindBugsFilter>
</FindBugsFilter>
```

- [ ] **Step 2: Run it and read the findings**

```bash
cd backend && ./gradlew clean spotbugsMain --console=plain 2>&1 | tail -20
echo "--- root findings ---"
grep -c '<BugInstance' build/reports/spotbugs/main.xml || echo 0
echo "--- module findings ---"
grep -c '<BugInstance' platform/platform-primitives/build/reports/spotbugs/main.xml || echo 0
echo "--- by type ---"
grep -ho 'type="[A-Z_]*"' build/reports/spotbugs/main.xml | sort | uniq -c | sort -rn | head -20
```

**Report the totals and the type breakdown to the user before continuing.** Spec D9 makes these findings a backlog item rather than this slice's work, and the user needs the number to decide whether that stays true.

- [ ] **Step 3: Generate the baseline**

The spec deliberately left the mechanism open. Determine what this plugin version supports:

```bash
cd backend && ./gradlew help --task spotbugsMain --console=plain 2>&1 | head -40
```

- **If the task exposes a `baselineFile` property**, set it in the convention plugin:
  ```kotlin
  spotbugs {
      baselineFile = rootProject.file("config/spotbugs/baseline.xml")
  }
  ```
  and generate the file by copying the current report:
  ```bash
  cp build/reports/spotbugs/main.xml config/spotbugs/baseline.xml
  ```

- **If it does not**, fall back to an exclude filter generated from the findings. Write `config/spotbugs/baseline.xml` as a `FindBugsFilter` with one `<Match>` per `(class, bug type)` pair present today, and point `excludeFilter` at a filter that includes both files. Generate the pairs with:
  ```bash
  grep -o 'type="[A-Z_]*"[^>]*' build/reports/spotbugs/main.xml | head -50
  ```
  Each `<Match>` takes the shape:
  ```xml
  <Match>
    <Class name="com.easycrm.example.Thing"/>
    <Bug pattern="EI_EXPOSE_REP"/>
  </Match>
  ```

Whichever path is taken, add a header comment to `baseline.xml` recording the date, the slice, the total count, and that new findings must be fixed rather than appended.

- [ ] **Step 4: Flip the gate on and confirm it now passes**

Change `ignoreFailures = true` to `ignoreFailures = false` in the convention plugin, then:

```bash
cd backend && ./gradlew clean spotbugsMain --console=plain 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL — every existing finding is baselined, so nothing fails.

- [ ] **Step 5: Prove the gate can fail**

This is the mandatory prove-it-can-fail step (spec §9). Plant a finding that is definitely not in the baseline:

```bash
cd /Users/divyam/Documents/easy-crm
cat > backend/src/main/java/com/easycrm/platform/SpotBugsCanary.java <<'EOF'
package com.easycrm.platform;

public final class SpotBugsCanary {
    public static int length() {
        String s = null;
        return s.length();
    }
}
EOF
cd backend && ./gradlew spotbugsMain --console=plain 2>&1 | tail -20
```

Expected: **FAIL**, reporting `NP_ALWAYS_NULL` (or an equivalent null-dereference pattern) in `SpotBugsCanary`. Then remove it:

```bash
rm /Users/divyam/Documents/easy-crm/backend/src/main/java/com/easycrm/platform/SpotBugsCanary.java
cd backend && ./gradlew spotbugsMain --console=plain 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL again. **If the canary did not fail the build, the gate is not wired** — do not proceed.

- [ ] **Step 6: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/buildSrc backend/config/spotbugs
git commit -m "build: add SpotBugs with find-sec-bugs, baselined at today's findings

Gate fails on new findings only; existing ones are recorded in
config/spotbugs/baseline.xml as a backlog item. Verified the gate fails on a
planted null-dereference before being reverted."
```

---

## Task 5: JaCoCo, measured then floored

**Files:**
- Modify: `backend/buildSrc/src/main/kotlin/easycrm.quality-conventions.gradle.kts`

**Interfaces:**
- Consumes: the convention plugin.
- Produces: `jacocoTestReport` (HTML + XML) and `jacocoTestCoverageVerification`, the latter wired into `check`.

- [ ] **Step 1: Add JaCoCo, reporting only, no threshold yet**

Extend the `plugins` block with `jacoco`, then append to the convention plugin:

```kotlin
tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        // HTML is what a human reads on a failed build; XML is what SonarCloud would
        // consume if the deferral in the design spec section 3 is ever revisited.
        html.required = true
        xml.required = true
    }
}

tasks.named("test") { finalizedBy(tasks.named("jacocoTestReport")) }
```

- [ ] **Step 2: Measure**

```bash
cd backend && ./gradlew clean test jacocoTestReport --console=plain
echo "=== root ==="
python3 -c "
import xml.etree.ElementTree as ET
for p in ['build/reports/jacoco/test/jacocoTestReport.xml','platform/platform-primitives/build/reports/jacoco/test/jacocoTestReport.xml']:
    try: r = ET.parse(p).getroot()
    except Exception as e: print(p, 'MISSING', e); continue
    for c in r.findall('counter'):
        if c.get('type') in ('LINE','BRANCH','INSTRUCTION'):
            m, cv = int(c.get('missed')), int(c.get('covered'))
            print(f\"{p.split('/')[0] or 'root':22} {c.get('type'):12} {cv/(m+cv)*100:5.1f}%\")
"
```

**Report both projects' line and branch coverage to the user.** These numbers set the floors and nobody has seen them before.

- [ ] **Step 3: Set the floors just under measured**

Round each measured value **down** to the nearest whole percent and subtract 1, so a marginally unlucky run does not turn the build red. Append to the convention plugin, substituting the real numbers:

```kotlin
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("jacocoTestReport"))
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                // Measured <X>% on 2026-09-01 in the build-hygiene slice. Floors ratchet
                // UP only -- raise this when coverage rises; never lower it to go green.
                minimum = "0.<XX>".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.<YY>".toBigDecimal()
            }
        }
    }
}

// JaCoCo does not wire verification into check by default, unlike Spotless and SpotBugs.
tasks.named("check") { dependsOn(tasks.named("jacocoTestCoverageVerification")) }
```

Per-project floors, not aggregated (spec D10): if the two projects' numbers differ materially, guard the shared rule with `if (project.name == "platform-primitives")` or set the values from a `val` defined per project. Do **not** add the `jacoco-report-aggregation` plugin.

- [ ] **Step 4: Confirm the gate passes at the chosen floor**

```bash
cd backend && ./gradlew clean check --console=plain 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL. `check` now runs tests, Spotless, SpotBugs and coverage verification in one command.

- [ ] **Step 5: Prove the gate can fail**

Raise the floor above measured, confirm red, then restore:

```bash
cd backend
# temporarily edit minimum to "0.99" in the convention plugin, then:
./gradlew jacocoTestCoverageVerification --console=plain 2>&1 | tail -10
```

Expected: **FAIL**, printing the measured ratio against the required 0.99. Restore the real floor and re-run to confirm green.

- [ ] **Step 6: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/buildSrc
git commit -m "build: add JaCoCo with floors set from measured coverage

Floors sit just below today's measured values and ratchet up only. Verified
the gate fails when the floor is raised above measured."
```

---

## Task 6: GitHub Actions workflow

**Files:**
- Create: `.github/workflows/ci.yml` (repo root, **not** under `backend/`)

**Interfaces:**
- Consumes: the `check` task as assembled by Tasks 2, 4 and 5.
- Produces: a CI run on push to `main` and on every PR.

- [ ] **Step 1: Write the workflow**

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

# A newer push to the same ref supersedes an in-flight run.
concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  check:
    runs-on: ubuntu-latest
    timeout-minutes: 20
    defaults:
      run:
        # The Gradle project root is backend/, not the repository root.
        working-directory: backend

    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 25
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4

      # ubuntu-latest ships Docker preinstalled, so the Testcontainers singleton
      # Postgres container the integration tests use needs no extra setup.
      - name: Build, test and check
        run: ./gradlew clean check --no-daemon --console=plain

      - name: Upload reports
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: reports
          path: |
            backend/build/reports/
            backend/platform/platform-primitives/build/reports/
          retention-days: 7
```

- [ ] **Step 2: Push the branch and watch the run**

```bash
cd /Users/divyam/Documents/easy-crm
git add .github/workflows/ci.yml
git commit -m "ci: run gradlew check on push and pull request"
git push -u origin build-hygiene
gh run watch --exit-status
```

Expected: the run goes green. If it fails, read the real cause before changing anything:

```bash
gh run view --log-failed | tail -60
```

The three most likely first-run failures and their fixes:
- **`--add-exports` not applied** → CI's daemon reads `backend/gradle.properties`, which is committed, so this should work; if it does not, the JVM args may need repeating as `GRADLE_OPTS`.
- **Testcontainers cannot reach Docker** → confirm the runner is `ubuntu-latest`, not a macOS or Windows runner.
- **JDK 25 not found for Temurin** → check `actions/setup-java`'s supported versions and switch distribution if needed; do not silently downgrade to 21, since the toolchain requires 25.

- [ ] **Step 3: Confirm the gates actually run in CI**

```bash
gh run view --log | grep -E "spotlessCheck|spotbugsMain|jacocoTestCoverageVerification|Task :test" | head
```

Expected: all four appear. A green run that skipped the gates is worse than a red one.

---

## Task 7: Prove the format gate in CI, and the docs wrap-up

**Files:**
- Modify: `docs/superpowers/HANDOFF.md`
- Modify: `docs/superpowers/engineering-challenges.md`
- Modify: `docs/superpowers/annotations-reference.md` (only if a new annotation appeared — for a build-tooling slice it almost certainly did not)

- [ ] **Step 1: Prove the format gate fails in CI, not just locally**

Tasks 2, 4 and 5 each proved their gate fails locally. The remaining question is whether CI enforces them. Push a deliberate violation:

```bash
cd /Users/divyam/Documents/easy-crm
printf '\n\n   \n' >> backend/src/main/java/com/easycrm/platform/job/TenantJobRunner.java
sed -i '' 's/^public final class TenantJobRunner/  public final class TenantJobRunner/' \
  backend/src/main/java/com/easycrm/platform/job/TenantJobRunner.java 2>/dev/null || true
git add -u && git commit -m "test: temporary formatting violation to prove CI enforces the gate"
git push
gh run watch --exit-status; echo "exit: $?"
```

Expected: the run **FAILS** on `spotlessCheck`, and the exit code is non-zero. Then revert:

```bash
git revert --no-edit HEAD
git push
gh run watch --exit-status
```

Expected: green again.

- [ ] **Step 2: Run the full local verification one last time**

```bash
cd backend && ./gradlew clean check
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'failures="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "failures:", s}'
```

Expected: BUILD SUCCESSFUL, `tests: 519`, `failures: 0`.

- [ ] **Step 3: Log the engineering challenges**

Append to `docs/superpowers/engineering-challenges.md` using the template at the bottom of that file. Candidates from this slice, each of which meets the "naive approach is subtly wrong" bar — log the ones that actually bit during execution, not all of them reflexively:

- **Reformatting a Flyway migration is invisible in CI and fatal in production.** A fresh Testcontainers database applies the new text and computes a matching checksum, so the build is green; the failure appears only against a database that already ran the old text. The lesson is that a formatter's exclusion list can encode a *runtime* invariant, not just a style preference.
- **A precompiled Gradle script plugin cannot see the version catalog.** `libs.` type-safe accessors do not exist in `buildSrc/src/main/kotlin/*.gradle.kts`, so a version needed at configuration time there must be a literal — which silently creates a second source of truth that can drift from the TOML.
- **The JVM that needs `--add-exports` is the daemon, not the toolchain.** On a machine whose shell default is JDK 21 and whose Gradle toolchain is JDK 25, it is natural to assume a JDK 25 problem needs a JDK 25 fix; the formatter actually runs in the daemon.
- **Whichever of the SpotBugs baseline mechanisms turned out to exist**, and why the other did not work.

- [ ] **Step 4: Update the handoff**

Edit `docs/superpowers/HANDOFF.md`:
- §0: replace "Nothing is in flight" with the state of this branch, and add `./gradlew clean check` as the new baseline command — it is now strictly stronger than `clean test`.
- §3: a new "Latest code work" bullet describing the gates, the SpotBugs baseline count, and the measured coverage floors.
- §5: note `backend/gradle.properties` and the daemon-vs-toolchain JDK distinction.
- §6: add the precompiled-script-plugin catalog limitation to the stack-quirks list.
- §8: add **Wave 1.5 (supply chain)**, **Wave 2 (observability)** and **Wave 3 (OpenAPI)** as candidates, and add the baselined SpotBugs findings as a backlog item with its real count.
- Add the new spec and this plan to the numbered reading list in §2.

- [ ] **Step 5: Commit and push**

```bash
cd /Users/divyam/Documents/easy-crm
git add docs/
git commit -m "docs: record the build-hygiene slice in the handoff and challenges log"
git push
gh run watch --exit-status
```

- [ ] **Step 6: Hand back for whole-branch review**

Do not merge. Report to the user: the SpotBugs baseline count, both coverage floors, the resolved plugin versions, whether `importOrder` survived alongside palantir, and the CI run URL. Merging is the `superpowers:finishing-a-development-branch` step and happens after a whole-branch review.

---

## Self-Review

**Spec coverage:** D1 §3 → Task 7 Step 4 (handoff waves). D2 → Task 6. D3 → Task 1 Steps 4–6. D4 → Task 1 Step 3. D5 → Task 2 Step 2. D6 → Task 3 Steps 3–4. D7 → Task 2 Step 2 comment + Task 3 Step 1 guard + Global Constraints. D8 → Task 4 Steps 1–4. D9 → Task 4 Steps 2–3. D10 → Task 5 Steps 2–3. D11 → Global Constraints. Spec §9's three prove-it-can-fail cases → Task 2 Step 4 (Spotless), Task 4 Step 5 (SpotBugs), Task 5 Step 5 (JaCoCo), plus Task 7 Step 1 for CI enforcement. Spec §10's three risks → Task 2 Step 1 (add-exports), Task 3 Step 1 (SQL guard), Task 4 Step 2 (unknown finding count reported before it becomes work).

**Known gaps, deliberate:** the exact palantir-java-format and plugin versions, the SpotBugs baseline mechanism, the import-order outcome, and the coverage floors are all resolved *by* steps in the plan rather than asserted in it — each has a named step, a command, and a decision rule. That is the honest treatment for a value nobody can know before running the tool, and matches how the spec declines to assert them.
