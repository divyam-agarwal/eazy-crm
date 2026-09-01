# Build hygiene — design

**Date:** 2026-09-01
**Status:** Designed, not yet planned
**Backlog item:** none — this is new work, raised by the user, not carried from `HANDOFF.md` §8
**Baseline:** `main` at `4ed641c` (519 tests, 0 failures, 0 errors)
**Wave:** 1 of 3 (build hygiene → observability → OpenAPI), plus a short Wave 1.5 split out of
this one in §3. Every later wave gets its own spec.
**Prerequisite met:** `origin` is `git@github.com:divyam-agarwal/eazy-crm.git` (public), with `main`
tracking `origin/main`, as of `c17e097`. §8's workflow has somewhere to run.

---

## 1. What this builds, and why it is not just "add some plugins"

The repo has 383 tracked files under `backend/`, 323 of them Java, and **not one automated
quality gate that runs outside a developer's own `./gradlew test`**. There is no CI. There was
no git remote until this slice. Nothing formats, nothing lints, nothing measures coverage, and
nothing checks a dependency for a published CVE.

What *does* exist is unusually good and is the reason this slice can be narrow: 519 tests, strict
TDD, an ArchUnit suite guarding tenant isolation at layer 2, an integration test guarding RLS at
layer 3, and a challenges log that records the traps already hit. The gap is not test discipline.
The gap is that **every one of those guarantees depends on a human remembering to run the build.**

Three things make this worth a design spec rather than a straight config task.

**A formatter applied to an unformatted 323-file tree is a one-way door taken at exactly one
moment.** The diff touches every file, so it conflicts with every in-flight branch. `main` is
clean and nothing is in flight today — the handoff says so explicitly. This is the cheapest this
change will ever be, and it gets monotonically more expensive from here.

**Two file categories in this repo must never be reformatted, and one of them fails loudly in
production rather than in CI.** Flyway checksums every applied migration; reformatting a single
byte of `V1__*.sql` makes `flyway validate` fail against every database that has already run it.
§5 makes both exclusions structural rather than remembered.

**A quality gate that has never failed is indistinguishable from one that checks nothing.** This
codebase already learned that the hard way: challenge #33 records an ArchUnit rule that passed
while checking nothing, caught only because the plan mandated a prove-it-can-fail step, and §6 of
the stack-quirks list now says "never add an ArchUnit rule without deliberately introducing a
violation and watching it fail." Every gate this slice adds inherits that rule. §9.

---

## 2. Decisions taken

- **D1 — the hygiene programme is a sequence of independent waves, and this spec is only the
  first.** Build hygiene (this), a short supply-chain follow-up (Wave 1.5), then observability,
  then OpenAPI. Each gets its own spec, plan, branch and merge, matching the slice rhythm the rest
  of the repo follows. §3 triages the full inventory the user raised and says what lands where.
- **D2 — gates run in GitHub Actions on push and PR**, against a new public `eazy-crm` remote.
  A gate that only runs locally is a convention, not a gate.
- **D3 — shared build config lives in a `buildSrc` convention plugin**, applied explicitly by
  both build files, not in an `allprojects {}` block. §4.
- **D4 — versions move to a `gradle/libs.versions.toml` catalog, and their justifying comments
  move with them.** The comments are the asset, not the numbers. §4.
- **D5 — Spotless uses `palantirJavaFormat()`.** 4-space, 120-col: closest to the existing style,
  so the big-bang diff stays as small as a big-bang diff can be. §5.
- **D6 — the reformat is one mechanical commit, recorded in `.git-blame-ignore-revs`.** Not a
  ratchet. §5.
- **D7 — `db/migration/*.sql` and `templates/quotation.xhtml` are excluded from every formatter.**
  §5, and the Flyway-checksum reasoning in §1.
- **D8 — SpotBugs runs at `effort = MAX` with find-sec-bugs, on main sources only, against a
  baseline of today's findings.** The build fails on *new* findings. §6.
- **D9 — existing SpotBugs findings become a tracked backlog item, not this slice's work.** The
  baseline is what stops a tooling slice from silently becoming a bug-fixing project of unknown
  size. §6.
- **D10 — JaCoCo floors are measured, not guessed, and are per-project.** The first task prints
  the real number; the floor is set just under it and can only ratchet up. §7.
- **D11 — no application code changes.** The only production-source diff this slice produces is
  mechanical reformatting. Anything else is out of scope and means something has gone wrong.

---

## 3. The full inventory, triaged

The user raised: SonarQube, SpotBugs, a linter, logging, tracing, metrics, Spring Cloud Contract,
Pact, OpenAPI, AsyncAPI, Chaos Monkey, and AWS FIS. Six facts read off the codebase decide most
of the ordering, and they are recorded here so the deferrals are re-checkable rather than taken
on trust:

| Fact, as of `4ed641c` | Consequence |
|---|---|
| No CI, and no git remote before this slice | Every "gate on PR" story had nowhere to run |
| Zero outbound HTTP calls — no `RestTemplate`/`WebClient`/`RestClient`/`HttpClient` in `src/` | Pact and Spring Cloud Contract have no consumer/provider pair |
| Events are in-process: 9 `ApplicationEventPublisher` uses, no broker anywhere | AsyncAPI has no async API to describe |
| Nothing deployed to AWS; the re-platform is design-only across three handoffs | AWS FIS has no target to inject into |
| 4 of 198 main files use a logger; no MDC, no correlation id, no JSON encoder | "Which tenant did this?" is unanswerable from logs |
| `management.endpoints.web.exposure.include: health`, Micrometer already on the classpath via actuator | Metrics are a config block, not a project |

**Wave 1 — this spec.** CI, Spotless, SpotBugs + find-sec-bugs, JaCoCo, version catalog.

**Wave 1.5 — supply chain, a short follow-up.** gitleaks, Dependabot or Renovate, OWASP
Dependency-Check, and `squawk` for unsafe Postgres DDL. Deliberately split out because it is a
third tool family and Dependency-Check needs an NVD cache that makes CI setup meaningfully
slower. Higher real security value than anything deferred below it.

**Wave 2 — observability.** Structured JSON logging, an MDC correlation filter carrying
`requestId`/`tenantId`/`userId`, a redaction rule for GSTIN/phone/email, Micrometer with
`/actuator/prometheus`, and Micrometer Tracing over OTLP. Not academic: the handoff's two known
production hazards — the in-process rate-limit store that multiplies every limit by N, and the
unlocked 00:30 expiry sweep that duplicates work across instances — are both **invisible** today.

**Wave 3 — OpenAPI.** springdoc, a committed spec snapshot, and `oasdiff` breaking-change
detection in CI. Arguably the highest-value item on the user's whole list: there are 18
controllers and the frontend has never been started, so this *is* the contract the frontend will
be built against. It is also the honest substitute for contract testing in a single-service repo.

**Deferred, each with a trigger rather than a vague "later":**

- **SonarQube** — deferred on merit, not sequencing. SpotBugs + Spotless + JaCoCo + the existing
  ArchUnit suite covers most of what it would flag, and Sonar earns its keep with a team and a PR
  queue rather than solo. The JaCoCo XML report stays enabled, so SonarCloud is a drop-in later.
- **Pact** — when the React frontend exists. Consumer-driven contracts written by the frontend
  against this API is a real use; there is no consumer today.
- **Spring Cloud Contract** — when the five-service AWS split in `docs/architecture/` is actually
  built. Contract testing exists to decouple independently deployed services.
- **AsyncAPI** — when `platform-outbox` (LLD #3, unbuilt) puts events on SNS/SQS. Spring
  `ApplicationEvent`s inside one JVM are not an async API.
- **Chaos Monkey for Spring Boot** — after the service split, and after retries, timeouts or
  circuit breakers exist. Injecting latency into a monolith with no downstream calls and no
  resilience patterns to validate discovers nothing.
- **AWS FIS** — after there is AWS.
- **Load baseline (k6 or Gatling)** — before the first large tenant, alongside the two missing
  indexes the handoff already flags.
- **Trivy image scanning** — blocked on there being a `Dockerfile`; there is none.

---

## 4. Structure: catalog and convention plugin

`settings.gradle.kts` includes `:platform:platform-primitives`, which means Gradle materialises an
implicit, empty `:platform` project between root and the module. An `allprojects {}` block would
configure that phantom project with the Java and quality plugins. A `subprojects {}` block would
miss the root project, which is where 198 of the 198 main sources live.

So: a `buildSrc` convention plugin, `easycrm.quality-conventions`, applied by name from both real
build files. The explicitness matches the taste already recorded in `build.gradle.kts` — *"a
declared edge rather than a package convention"* — and it is the shape that survives the **five
remaining platform modules** the LLD queue has designed but not built. Two projects barely justify
`buildSrc`; seven do, and the queue is on record.

The catalog absorbs the version pins currently inlined across both build files: Boot 4.1.0,
dependency-management 1.1.7, openhtmltopdf 1.0.10, jjwt 0.12.6, bucket4j 8.19.0, the Testcontainers
BOM 1.21.3, ArchUnit 1.4.1, and the JUnit BOM 5.13.4.

**The comments move with the versions.** Several of those pins encode a trap that cost real time —
that ArchUnit 1.3.0 silently skips Java 25 bytecode and passes vacuously, that
`com.bucket4j:bucket4j-core` is a stale coordinate resolving to 8.1.x, that openhtmltopdf 1.1.x was
never cut upstream, that the Boot 4.1 BOM does not manage Testcontainers module versions. TOML
takes comments. A catalog that keeps the numbers and drops the reasoning is a net loss.

---

## 5. Spotless

`palantirJavaFormat()` over `src/**/*.java` in both projects, plus `trimTrailingWhitespace` and
`endWithNewline` on `*.gradle.kts` and `*.yml`.

**Two exclusions, both structural.**

`src/main/resources/db/migration/*.sql` is never touched by any formatter. Flyway stores a checksum
per applied migration and validates it on every startup; a reformatted byte in an already-applied
file makes `flyway validate` fail against every database that has run it. There are 32 migrations.
This failure would not appear in CI — a fresh Testcontainers database applies the *new* text and
computes a matching checksum — it would appear against the developer's own long-lived database, or
worse, in production. It is the one change in this slice that could break something CI cannot see.

`src/main/resources/templates/quotation.xhtml` is Thymeleaf XML parsed by openhtmltopdf in XML mode
(`spring.thymeleaf.mode: XML`). Whitespace handling in that pipeline is load-bearing for PDF layout
and there is no test that would catch a subtle regression. Leave it alone.

**The reformat is one commit and nothing else is in it**, so it can be reviewed by confirming the
build stays green rather than by reading 323 files. Its SHA goes into `.git-blame-ignore-revs`,
which GitHub honours automatically in the blame view.

**Two things are known to cost time here and are called out so the plan budgets for them.**

Palantir reformats Javadoc. This codebase has an unusual amount of deliberately hand-wrapped prose
in class comments — `TenantJobRunner`'s doc comment is the clearest example — and the reflow will
be visible. Content is preserved; wrapping is not. This is an accepted cost of D5, not a surprise.

Whether `palantirJavaFormat()`'s own import handling can coexist with a custom `importOrder()` step
reproducing the current `java.*`-last grouping is **not asserted here**. It is a fifteen-minute
spike in the first task, the same shape as the openhtmltopdf engine spike that opened the PDF
slice. If they conflict, Palantir's ordering wins and the import grouping changes — that is a
formatting change, not a correctness one.

---

## 6. SpotBugs

The `com.github.spotbugs` Gradle plugin at `effort = MAX`, with **find-sec-bugs** added as a
`spotbugsPlugins` dependency. The security half is the part that earns its place in this
particular codebase: JWT minting and parsing, a bcrypt password path, a `permitAll` public route
that renders a PDF, and a rate limiter keyed on an attacker-controlled value. Main sources only in
both projects; test sources are excluded as noise.

Today's findings go into a baseline so that **the build fails only on new findings.** This is D9,
and it is the decision that keeps the slice bounded: the finding count on 198 never-analysed files
is unknown until the plugin runs, so gating on zero findings would commit the branch to a
bug-fixing project of unmeasured size before anyone has seen the list. The baseline is reported to
the user and recorded in `HANDOFF.md` as a backlog item with its actual count.

**The exact baseline mechanism is deliberately not fixed by this spec.** SpotBugs supports both a
baseline file and an `excludeFilter` XML, and which one the current Gradle plugin exposes cleanly
is a question for the task, not an assertion for a design doc. Either satisfies D8; the plan pins
it after reading the plugin's actual API.

---

## 7. JaCoCo

Per-project reports and per-project floors. No `jacoco-report-aggregation`: the two projects have
genuinely different character — 23 tests over pure value types versus 496 over a Spring
application with Testcontainers — and one blended percentage would mask movement in both.

The task order is the decision. **Measure, report the number, then set the floor**, roughly a
point below measured so that an unlucky run does not turn the build red for no reason. From there
it only ratchets up. Setting a conventional 80% bar before measuring risks two failure modes and
this repo can afford neither: if real coverage is higher the gate is slack and gives false comfort,
and if it is lower the slice inherits a test-writing project it did not sign up for.

XML and HTML reports both stay on. HTML is what a human reads on a failed build; XML is what
SonarCloud would consume if §3's SonarQube deferral is ever revisited.

---

## 8. CI

`.github/workflows/ci.yml`, triggered on push to `main` and on pull request:

- `ubuntu-latest`, which ships Docker preinstalled — the Testcontainers singleton-container pattern
  the integration tests already use needs nothing extra.
- `actions/setup-java` with Temurin 25, matching the Gradle toolchain. Note that the *daemon* JVM
  and the *toolchain* JVM are different things here and §10 says why that matters.
- `gradle/actions/setup-gradle` for dependency and build caching.
- One command: `./gradlew clean check`. By the end of this slice `check` means test **plus**
  `spotlessCheck` **plus** `spotbugsMain` **plus** `jacocoTestCoverageVerification`, so CI and a
  local run are the same command producing the same verdict. No CI-only checks.
- Test results, SpotBugs HTML and JaCoCo HTML upload as artifacts when the build fails.
- A concurrency group cancels superseded runs on the same ref.
- An explicit job timeout, so a hung Testcontainers pull cannot burn the runner budget.

---

## 9. Testing: every gate is proven able to fail

Build configuration has no unit tests, so the verification is behavioural and it is mandatory, not
optional. For each of the three gates, deliberately introduce a violation, run the build, watch it
fail, then revert:

| Gate | Injected violation | Expected |
|---|---|---|
| Spotless | Mangle indentation in one file | `spotlessCheck` fails, naming the file |
| SpotBugs | Plant a known-detectable pattern (e.g. a guaranteed null dereference) | `spotbugsMain` fails on a finding absent from the baseline |
| JaCoCo | Remove enough tests to drop under the floor | `jacocoTestCoverageVerification` fails, printing measured vs. required |

This is the same discipline as the `platform-primitives` plan's Task 5, and the reason challenge
#33 was caught rather than shipped. A gate whose failure path has never executed is a gate nobody
has tested.

Alongside it, the unconditional check: **519 tests, 0 failures, 0 errors, before and after the
reformat** — counted with the two-project `find`/`awk` snippet from `HANDOFF.md` §0, not a
root-only variant, and with any `--tests` filter project-qualified.

---

## 10. Risks

**The reformat conflicts with every branch.** `main` is clean and nothing is in flight. If any
other work starts first, this slice gets harder and the argument for the ratchet alternative that
was rejected in D6 gets stronger. Sequencing is the mitigation; there is no technical one.

**Palantir Java Format needs `--add-exports` on JDK 25 — CORRECTED after execution, this risk did
not play out as predicted.** The daemon-not-toolchain distinction below turned out to be right,
but the "expect it to take a couple of attempts" prediction did not: Task 2 found Gradle 9.6.1's
daemon already supplies `--add-exports` for `jdk.compiler.{api,util}` by default (confirmed via
`ps aux` on the running daemon process), which was sufficient for palantir-java-format 2.97.0 to
run cleanly on its very first classpath resolution — no `gradle.properties` iteration, no
`InaccessibleObjectException`. It also worked unmodified on CI's daemon (Task 6), a different
machine entirely. All five `--add-exports` flags (`api`, `file`, `parser`, `tree`, `util`) were
still added to `backend/gradle.properties`, but as **insurance, not a requirement**: only 2 of the
5 are supplied by the daemon's own default, and the remaining 3 (`file`, `parser`, `tree`) cover
palantir codepaths this tree does not currently exercise — a defensive superset that costs nothing
at runtime and avoids a silent `InaccessibleObjectException` surfacing later on a different
JDK/Gradle combination, whose defaults are an undocumented implementation detail. The underlying
wrinkle this risk entry correctly anticipated — the JVM that needs opening up is the **Gradle
daemon**, which runs on the shell default JDK 21, not the toolchain's 25 (`HANDOFF.md` §5) — is
real and worth knowing; it just turned out to already be handled by this Gradle version.

**The Flyway checksum trap is silent in CI.** §5 covers it; it is repeated here because it is the
only risk in this slice whose blast radius is a running database rather than a red build.

**An unknown SpotBugs finding count** is bounded by D9's baseline, not by optimism. If the list is
long, that is information, and it becomes a backlog item with a number attached.

---

## 11. Out of scope

Everything in Wave 1.5, Wave 2, Wave 3 and the deferral list in §3. No application behaviour
changes. No new tests beyond what §9 describes. No fixing of baselined SpotBugs findings. No
`Dockerfile`, no deployment, no branch protection rules — the last of those is a GitHub setting
the user owns, not a repo artifact.

---

## 12. Task sketch

Detail belongs to the plan, not here, but the shape is seven tasks so the plan has a starting
point: (1) version catalog plus `buildSrc` convention plugin, no behaviour change, 519 still green;
(2) Spotless configured and the import-order spike resolved; (3) the big-bang `spotlessApply` commit
plus `.git-blame-ignore-revs`, alone in its own commit; (4) SpotBugs, find-sec-bugs and the
baseline, with its count reported; (5) JaCoCo measured, then floored; (6) the Actions workflow and a
first green run; (7) the prove-each-gate-fails pass from §9 and the docs wrap-up — `HANDOFF.md`
§3/§5, any challenges worth logging, and the annotations reference if a new annotation appears,
which for a build-tooling slice it probably will not.
