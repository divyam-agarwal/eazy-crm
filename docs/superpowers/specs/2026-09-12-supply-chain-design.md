# Supply chain (Wave 1.5) — design

**Date:** 2026-09-12
**Roadmap item:** 2 — "Wave 1.5 — supply chain"
**Parent spec:** [`2026-09-01-build-hygiene-design.md`](2026-09-01-build-hygiene-design.md) §3, which
named this wave and deferred it deliberately.
**Baseline:** `main` at `ac2fc63`, 591 tests (563 root + 28 `platform-primitives`), 0 failures.

---

## 1. What this builds, and why it is not just "add some scanners"

Wave 1 gave this repo its first automated quality gates: Spotless, SpotBugs + find-sec-bugs,
JaCoCo, all behind one `./gradlew clean check`, all running in GitHub Actions. Every one of those
tools reads **the code we wrote**.

Nothing reads what we *depend on*, what we *accidentally committed*, or what our *migrations will
do to a live database*. That is the gap this wave closes, and it matters more here than the tool
list suggests:

| Fact about this repo | Consequence |
|---|---|
| The repo is **public** (`github.com/divyam-agarwal/eazy-crm`) | A committed secret is disclosed the moment it is pushed, not when someone notices |
| It ships JWT mint/parse, a bcrypt password path and a `permitAll` PDF route | The dependency tree is part of the attack surface, and nothing enumerates its published CVEs |
| 34 Flyway migrations, `ddl-auto: validate`, and an AWS deployment coming in Phase 3 | The first migration that takes an `ACCESS EXCLUSIVE` lock on a live table is an outage, and nothing today would say so before it ran |
| `application.yml` carries three env-var-defaulted dev credentials | A secret scanner with no allowlist fails on day one; a secret scanner with a hand-waved allowlist fails at nothing |

Four tools plus one bot, none of which overlaps with anything Wave 1 installed.

---

## 2. Decisions taken

**D1 — Dependency-Check reports, it does not block.** A published CVE in a transitive Spring
dependency can turn `main` red overnight with no code change and nobody to attribute it to. That is
how gates get ignored — the same reasoning that made the oasdiff step non-blocking in Wave 3. It
runs as its own job with `continue-on-error: true`, writes to the step summary, and uploads its HTML
report as an artifact. **Flip trigger:** branch protection (roadmap item 8), at which point there is
a PR queue to triage findings in and a human gate in front of `main`.

**D2 — The other three block.** gitleaks, squawk and actionlint all answer questions with a correct
answer that does not change overnight: is this a secret, is this DDL safe, is this workflow valid.
None of them can be reddened by a third party publishing something. There is no reason to soften
them.

**D3 — Dependabot, not Renovate.** Native to GitHub, no third-party app with write access to a repo
that now holds an `NVD_API_KEY` secret. It reads `gradle/libs.versions.toml` and can bump the pinned
actions in `ci.yml`. **Deferral with a trigger:** revisit Renovate if PR volume becomes unmanageable
or `buildSrc`'s plugin versions start drifting (see D8).

**D4 — The three binaries run in CI only; `./gradlew clean check` is unchanged.** gitleaks, squawk
and actionlint are Go/Rust binaries, not Gradle plugins. Wrapping them in `Exec` tasks wired into
`check` would require every developer to have three binaries installed and would slow the everyday
gate for no local benefit. **The honest cost, stated rather than buried: a green local run no longer
implies a green CI.** D5 is what keeps that cost bounded.

**D5 — CI-only tooling is asserted by a test.** `SupplyChainWorkflowTest` reads the real
`.github/workflows/ci.yml` and asserts each scan step exists with the right blocking posture. This
is the `OasdiffWorkflowTest` pattern, and it exists for exactly this failure mode: a CI-only gate
that nothing in the build knows about can be deleted or misconfigured without anything going red.

**D6 — squawk lints changed migrations only.** All 34 existing migrations are frozen by Flyway
checksums; editing one breaks every database that has already applied it. Linting them can therefore
only produce findings nobody is permitted to act on. Squawk runs over migrations changed in the push
or PR, which is the rule actually being enforced: *new* DDL must be safe against a live database.
The rejected alternative — lint everything, suppress the existing findings in `squawk.toml` — bakes
a suppression list into the repo that outlives the reason for it and reads, a year later, as
"squawk's rules do not apply here."

**D7 — Dependency-Check gets a Gradle task, deliberately not wired into `check`.** The plugin is
applied so the scan is runnable locally on demand (`./gradlew dependencyCheckAnalyze`), but adding it
to `check` would put an NVD database sync in front of every build. Two sources, one wiring: the CI
job reads the `NVD_API_KEY` environment variable; a local run reads the `nvdApiKey` Gradle property
from `~/.gradle/gradle.properties`, which is outside the repository and therefore outside both git
and gitleaks.

**D8 — `buildSrc` stays manually versioned, and this is recorded, not fixed.** Dependabot's gradle
ecosystem reads `gradle/libs.versions.toml`, and the convention plugin already sources
`palantirJavaFormat` and `findsecbugs` from that catalog — so those *are* covered. What is not
covered is anything Dependabot cannot resolve inside a separate `buildSrc` build. Verify the actual
coverage during implementation (Task 5) and write down what it misses rather than assuming either
way.

---

## 3. CI shape — three jobs, not three more steps

The existing `check` job is untouched. Two jobs are added, and one new trigger:

```
on:
  push:      branches: [main]
  pull_request:
  schedule:  - cron: '30 1 * * *'      # NEW: nightly NVD refresh, ~07:00 IST

jobs:
  check:              # UNCHANGED — Gradle, Testcontainers, 20 min
  supply-chain:       # NEW — gitleaks + actionlint + squawk. Blocking. No JDK, no Gradle.
  dependency-check:   # NEW — continue-on-error, step summary + HTML artifact.
                      #       Runs on push, pull_request and schedule.
```

`supply-chain` runs in parallel with `check` and needs no JDK, no Gradle and no Testcontainers, so
it reports in well under a minute. Keeping it as its own job means a secret-leak failure is
attributable at a glance rather than buried in a Gradle log — and it fails *fast*, before the
20-minute test job finishes.

The nightly schedule exists for Dependency-Check's database refresh, which is what Part 4 of the
roadmap sketches. `supply-chain` does not need it but costs nothing to include, and a nightly
full-history gitleaks run catches a secret that entered through a path CI did not see.

---

## 4. gitleaks

**Version 8.30.1**, verified against the GitHub releases API on 2026-09-12 (published 2026-03-21).

**Distribution.** Run the pinned container rather than `gitleaks/gitleaks-action`, which requires a
licence key for organisation-owned repositories — a condition that does not apply today but would
apply silently the day this repo moves to an org. **The image coordinate must be verified at
implementation time:** the historical Docker Hub image is `zricethezav/gitleaks`, and the project has
since published to `ghcr.io/gitleaks/gitleaks`. Confirm which is current for 8.30.1 and pin by tag.

**Scan scope.** On `pull_request`, scan the diff. On `push` and `schedule`, scan full history —
`fetch-depth: 0` is already set on the checkout for oasdiff's benefit, so the history is present. A
diff-only scan cannot find a secret that entered three commits ago and was never removed.

**The allowlist is the real work.** `application.yml` carries three values gitleaks will flag:

| Line | Value | Why it is not a secret |
|---|---|---|
| `spring.datasource.password` | `${DB_PASSWORD:easycrm_app}` | Env-var indirection; the literal is a local-dev default |
| `spring.flyway.password` | `${FLYWAY_PASSWORD:easycrm_owner}` | Same |
| `easycrm.jwt.secret` | `${JWT_SECRET:0123456789-…-devsecret}` | Same; the literal is self-describing and has no production counterpart |

These go into a committed `.gitleaks.toml`, allowlisted **by path and rule**, each with a comment
saying why. Not by broadening a regex, and not by a blanket path exclusion on
`src/main/resources/**` — which would also exempt every future file in that tree. The file becomes
the repo's written record of "known non-secrets": adding a fourth entry requires justifying it in
the same place, which is the point.

**Note for Phase 3.** Every one of those three defaults is a real credential in a real environment
the moment AWS exists. The allowlist entries are scoped to the *literal default*, not to the
property, so a hardcoded production value on the same line would still fire.

---

## 5. squawk

**Version 2.65.0**, verified against the GitHub releases API on 2026-09-12 (published 2026-09-10).

**Distribution.** Squawk ships release binaries and an npm package (`squawk-cli`); confirm at
implementation time which is reproducible in CI and pin it — prefer a pinned release binary verified
by checksum over an unpinned `npm install -g`.

**Scope, per D6.** Compute the changed migration set from the same `BASE_SHA` the oasdiff step
already derives (`github.event.pull_request.base.sha` on PRs, `github.event.before` on push), then:

```
git diff --name-only --diff-filter=AM "$BASE_SHA" HEAD -- \
  'backend/src/main/resources/db/migration/*.sql'
```

`--diff-filter=AM` covers added and modified; a modified migration is itself a Flyway checksum
violation, so squawk flagging it is a second, cheaper alarm on a problem the application would
otherwise discover at startup.

**Empty set is a pass, not a skip with a scary message.** Most pushes touch no migrations. The step
must say "no migrations changed" and exit 0 — the failure mode to avoid is a step that looks like it
ran and found nothing when it actually found nothing to run on. The oasdiff step's
`BASE_SHA`-unavailable handling is the precedent to copy, including its all-zeros first-push case.

**Expect rule tuning.** This codebase's migrations do things squawk has opinions about: RLS
`ENABLE`/`FORCE` statements, `V34`'s per-tenant DML backfill loop, and `NOT NULL` columns added to
tables that are empty today but will not be in Phase 3. Any rule disabled in `squawk.toml` needs a
comment saying why, and "it was noisy" is not why.

---

## 6. actionlint

**Version 1.7.12**, verified against the GitHub releases API on 2026-09-12 (published 2026-03-30).

Lints workflow syntax, expression types, and — via shellcheck — the contents of every `run:` block.

**Expect real findings, and fix them in this slice.** The oasdiff step is roughly forty lines of
shell with `set -uo pipefail`, several unquoted expansions, and `docker run … | tee` pipelines.
Landing actionlint pre-suppressed would be the same as not landing it. If a finding is genuinely
wrong, suppress that specific finding with a comment naming the reason; do not disable the rule
globally.

**One consequence worth anticipating:** actionlint may object to something in the very block that
Wave 3 wrote deliberately (the `pipefail` and explicit-capture reasoning in `ci.yml`'s comments).
Where the existing comment already explains the choice, the fix is a targeted suppression referencing
that comment — not rewriting logic that was reasoned about once already.

---

## 7. Dependabot

One committed `.github/dependabot.yml`, two ecosystems, weekly:

| Ecosystem | Directory | Covers |
|---|---|---|
| `gradle` | `/backend` | `gradle/libs.versions.toml`, `build.gradle.kts`, `platform/platform-primitives` |
| `github-actions` | `/` | The pinned `actions/checkout@v7`, `setup-java@v6`, `setup-gradle@v6`, `upload-artifact@v7` in `ci.yml` |

The gradle directory is `/backend`, not `/` — the Gradle root is `backend/`, and pointing Dependabot
at the repository root would find nothing and report nothing, silently.

**Two properties of this that are features, not side effects.** Dependabot will be the first thing
in this repo's history to open a pull request as routine practice, which rehearses the
`pull_request` CI path before roadmap item 8 makes it mandatory. And every Dependabot PR is a live
test of whether `check` actually passes from a clean checkout — which is the one thing a
post-merge-only CI has never been able to tell us.

---

## 8. OWASP Dependency-Check

**Gradle plugin 13.0.0**, verified against `plugins.gradle.org/m2` on 2026-09-12 (latest and release
both 13.0.0, published 2026-08-03).

Added to `gradle/libs.versions.toml` with the same "verified against X on DATE" comment convention
the catalog already uses, and applied in `easycrm.quality-conventions.gradle.kts` so both projects
are analysed. **Not wired into `check`** (D7).

**Credentials.** `NVD_API_KEY` is set as a repository secret (done, 2026-09-12). The local path is
the `nvdApiKey` Gradle property in `~/.gradle/gradle.properties` — outside the repo, so there is
nothing for git or gitleaks to see. Without a key the plugin still runs but the NVD sync is
rate-limited into the tens of minutes, which is the entire reason this wave was split out of Wave 1.

**Caching.** The NVD database must be cached between runs (`actions/cache` keyed on the data
directory) or every run pays the full sync. A cache miss must not fail the job — it is slow, not
broken.

**Output.** Step summary for the finding counts by severity, HTML report uploaded as an artifact with
the same 7-day retention the existing failure-report upload uses.

---

## 9. Guarding the guards

`SupplyChainWorkflowTest`, alongside `OasdiffWorkflowTest` in
`backend/src/test/java/com/easycrm/platform/`, reading the real `ci.yml` via the `ci.workflow`
system property that `build.gradle.kts` already injects. It asserts:

1. A `supply-chain` job exists and contains a gitleaks step, an actionlint step and a squawk step.
2. None of those three carries `continue-on-error: true`.
3. A `dependency-check` job exists and **does** carry `continue-on-error: true` — so silently
   promoting it to blocking, or silently demoting one of the other three, both fail the build.
4. Every tool version referenced in the workflow is pinned, not floating (`@v2`, `:latest`).

Assertion 4 is the one that earns its keep longest: `:latest` on a scanner image is a build whose
behaviour changes without a commit.

---

## 10. Testing: every gate is proven able to fail

Same discipline as Wave 1 §9, and mandatory rather than optional. For each blocking gate, introduce
a violation, watch CI fail, revert:

| Gate | Injected violation | Expected |
|---|---|---|
| gitleaks | Commit a syntactically valid fake AWS key on a scratch branch | `supply-chain` fails, naming file and rule |
| squawk | A migration doing `ALTER TABLE customer ADD COLUMN x text NOT NULL` | squawk fails on the unsafe-DDL rule |
| actionlint | A malformed `${{ }}` expression or an undefined `needs` reference | actionlint fails, naming the line |
| Dependency-Check | — | Not applicable: it is non-blocking by design (D1). Verify instead that its report renders and the artifact uploads |
| `SupplyChainWorkflowTest` | Delete the gitleaks step from `ci.yml` | `./gradlew check` fails locally, before CI is involved |

The last row is the important one, because it is the only one of the five that fails on a
developer's machine.

Unconditional check, before and after: **591 tests, 0 failures, 0 errors**, counted with the
two-project snippet from `HANDOFF.md` §0.

---

## 11. Risks

**Dependabot opens a wave of PRs on day one.** A repo that has never run dependency updates will
have a backlog. Mitigate with `open-pull-requests-limit` and a grouping strategy in
`dependabot.yml`; do not mitigate by deferring the bot.

**actionlint's findings could exceed the slice.** §6 anticipates findings in the oasdiff block. If
fixing them turns into rewriting Wave 3's reasoned-about shell, stop and raise it rather than
quietly rewriting logic this repo already has a documented opinion about.

**Squawk's rule set may not fit RLS-heavy migrations.** §5 anticipates this. The failure mode to
avoid is disabling rules until it passes; the correct output of that situation is a `squawk.toml`
with a small number of disabled rules, each with a written reason.

**The first Dependency-Check run is slow even with a key.** Budget for it; it is a cold cache, not a
hang.

**A green local run no longer implies a green CI** (D4). Accepted deliberately, bounded by D5.

---

## 12. Out of scope

- **Trivy** — still blocked on there being a `Dockerfile`. Roadmap item 5.
- **Branch protection** — roadmap item 8, and a GitHub repository setting the user owns rather than
  a repo artifact.
- **SonarQube** — deferred on merit in the Wave 1 spec §3; nothing here changes that.
- **The 32 baselined SpotBugs findings** — a separate backlog item.
- **Fixing any CVE Dependency-Check reports.** This wave installs the scanner. Acting on its first
  report is the next decision, made with the report in hand.
- **Any application behaviour change.**

---

## 13. Task sketch

Each task is a commit, TDD where a test is possible, reviewed before the next begins.

1. **`SupplyChainWorkflowTest`, failing.** Written first, against a `ci.yml` that has none of these
   jobs. This is the deliberately-failing test this repo's practice opens a slice with.
2. **gitleaks** — the `supply-chain` job, the step, and `.gitleaks.toml` with the three documented
   allowlist entries. Prove it fails (§10), revert.
3. **actionlint** — the step, plus whatever findings it raises in the existing workflow. Prove it
   fails, revert.
4. **squawk** — the step with changed-files scoping and the empty-set path, plus `squawk.toml` if
   rules need disabling. Prove it fails, revert.
5. **Dependabot** — `.github/dependabot.yml`; verify what it actually covers and record what it does
   not (D8).
6. **Dependency-Check** — catalog entry, convention-plugin wiring, the `dependency-check` job, the
   nightly trigger, NVD cache.
7. **Documentation** — challenge-log entries for anything non-obvious that turned up (D6's
   Flyway-immutability reasoning is the current candidate), roadmap item 2 struck, HANDOFF updated.
   Per `CLAUDE.md`, this is part of the same change, not a follow-up.
