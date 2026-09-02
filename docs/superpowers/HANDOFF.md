# EasyCRM — Handoff

**Last updated:** 2026-09-02 — **Members management is merged to `main`.** Eight task commits
(`0d80e9b`..`f0ce72c`, one per task) plus two intermediate fix-ups folded in along the way
(`2a11fa7` a Spotless reformat, `1577d50` a defensive copy for a new SpotBugs finding —
challenge #67 is about how those two came to be needed), every task reviewed clean. A tenant's
owner can now do the four things invitations deliberately stopped short of: **list** every
member, **change** a role, **disable** a member without deleting their history, and **enable**
one again. Two decisions carry the slice. The reassign-first gate on disable needs an
unfiltered, tenant-wide count of open work from `crm`/`sales` repositories that `iam` must not
depend on, so `iam` declares an `AssignedWorkload` port and `crm`/`sales` implement it —
reusing the dependency edge those packages already have on `iam`, so the graph stays acyclic
(challenge #66). And two owners demoting each other at once is **write skew**, not a lost
update — nothing already in the codebase (`@Version`, a unique index, `REPEATABLE READ`) catches
it, so every member-admin write now takes a `PESSIMISTIC_WRITE` lock on the tenant row first
(challenge #65). See §3 for the full inventory: the four routes, the port, the lock, the
`AuthService.refresh` fix that makes `disable` actually revoke access rather than just look like
it, the `ConflictException` structured-fields addition, and migration `V33`. **561 tests, 0
failures, 0 errors** on the branch, up from the 519-test baseline, via `./gradlew clean check`
from a clean state.

**This slice and the OpenAPI contract slice ran concurrently off the same `main`, and that is
worth knowing before reading either one's history.** Neither could see the other's uncommitted
work, so both independently numbered their engineering-challenges entries from #61 and collided
on #62–#64. OpenAPI merged first, so it kept those numbers and members management renumbered to
**#65–#67**; the log runs 60–67 with no gap and no duplicate. Both slices also edited
`ApiExceptionHandler.conflict(...)` — OpenAPI gave it a typed `ApiErrorResponse` return and an
`@ApiResponse` annotation, members management made it pass the exception's structured `fields`
through — and the merge keeps both. An earlier version of this line claimed "Nothing is in
flight; `main` is the baseline for new work" while that was already false; the lesson is that a
handoff asserting nothing is in flight, when something plainly is, stops being useful the moment
it is read literally.

**Before it, the OpenAPI contract slice was merged to `main` as `bead2f8`.** This API now has a
document: springdoc 3.1.0 generates it, `docs/api/openapi.yaml` is the committed snapshot, and
`./gradlew clean check` fails when the two disagree — so the contract stops being "whatever the
controllers happen to do" and becomes the thing the frontend gets built against. The error envelope
is a typed pair of records rather than a `Map`, the browsable UI is dev-profile-only and physically
absent from `bootJar`, and CI reports an oasdiff API changelog without blocking on it — **proven
on real runs for both the push and the pull-request path**, which also fired this repo's first
ever pull request. See §0 for the detail and §3's bullet for the inventory. Before it, build hygiene was built
and merged to `main` as `83e6880`. This repo now has automated quality
gates for the first time: one `./gradlew clean check` runs the tests **plus** Spotless
(palantir-java-format), SpotBugs (+ find-sec-bugs, baselined) and JaCoCo coverage verification
across both Gradle projects, and **GitHub Actions runs that same command on every push to `main`**
— the repo also has a remote for the first time, `git@github.com:divyam-agarwal/eazy-crm.git`
(public). Zero application-code changes: the only production-source diff is one mechanical
whole-tree reformat of 311 files, proven byte-identical to `spotlessApply` output. **Read §0's
"What CI does and does not do" before assuming the gate blocks anything** — it is a post-merge
smoke alarm, not a pre-merge gate, and that is a consequence of this repo's no-PR merge habit
rather than an oversight. See §3's top bullet for the gate detail (baseline count, coverage
floors, resolved plugin versions). Before it, user invitations were merged to `main` as
`f265cfe`. A tenant can finally have more than one user. An owner invites an email and a role, the invitee
follows a link, sets a password, and becomes an `ACTIVE` user of that tenant — which is what makes
`assigned_to`, the record-visibility slice and the `SALES_EXEC` role stop being notional. The
`invitation` table is the codebase's **third global, RLS-exempt table** after `refresh_token` and
`share_link`, because accepting is pre-auth: the tenant has to be resolved from the token before
any context exists. Two consequences are worth knowing before touching this area — the only
hand-written `tenant_id` comparison in the entire codebase lives in `InvitationService.revoke`
(challenge #54 says why the structural rule cannot apply and why the miss must 404, not 403), and
every failed accept or preview returns a byte-identical 404 so a `permitAll` route cannot be used
as a token-enumeration oracle (challenge #55) — including when the invitation is fine but its
**tenant is SUSPENDED**, which both public routes refuse exactly as `AuthService.login` does. Challenge #56 records why this token is hashed at
rest when `share_link`'s is deliberately plaintext. Backlog item #3 (P0-auth follow-up) is now
**DONE in full** — see §8. Before that, the quotation auto-expiry slice was **merged to
`main` as `2fb2b85`**; see §3 for its detail.

**One caveat travels with the invitations slice, and it qualifies the "usable on day one" claim:**
the `acceptUrl` handed back by `POST /api/v1/invitations` is
`{easycrm.public-base-url}/invite/{token}` — a **frontend route that does not exist yet** (design
spec D10). The *token* works and both public endpoints consume it directly, which is how the
integration tests drive the whole flow; the *page* is not browsable. Wiring `/invite/{token}` is
the first thing to do when the frontend lands.

**Purpose:** Everything a fresh agent needs to pick up this project and continue. Read this first, then the linked docs.

---

## 0. Resuming? Start here

### Nothing is in flight

**`main` now carries both of the branches that were running concurrently, and neither remains
open.** Members management (this file's top entry) was merged after the OpenAPI contract slice;
its 16 commits were built in a locked worktree by a separate session, reviewed task-by-task and
then whole-branch, and verified green before merging. `openapi-contract` was merged as `bead2f8`
and its branch deleted.

**The one thing that merge taught, worth carrying:** members management was cut from `e9d694e`,
*before* the OpenAPI slice landed, so it contained no `docs/api/openapi.yaml` while `main` had
begun guarding that file — and it adds `MemberController`, four new endpoints. The regenerate-
alongside rule therefore got its first real exercise: `./gradlew updateOpenApiSnapshot`, committed
in the same change. The failure mode is loud, not silent, which is the point of the guard. **Any
future branch cut before a guard exists will hit the same thing** — the fix is mechanical, but it
is not automatic.

The other cross-branch lesson is recorded in the header: two slices off one `main` both numbered
their challenge entries from #61 and collided. The log now runs 60–67 with no gap and no
duplicate, because the slice that merged second renumbered. Worth remembering the next time two
sessions run at once — the collision is invisible to both until merge.

### The OpenAPI contract slice is done and pushed

**The OpenAPI contract slice is merged to `main` as `bead2f8`.** Seven tasks plus a
whole-branch-review fix wave, off `main` at `e9d694e`, every one reviewed, merged `--no-ff` on
2026-09-02 and the `openapi-contract` branch deleted; the merged result was verified green before
the branch went away. **`main` is the baseline for new work and is fully pushed — `main` and
`origin/main` are level at `b239f5f`.** The commit range started at `4a1848b`: two docs
commits (`4a1848b` the design spec,
`e0d0dfb` the plan), then the task commits from `b14c92a` (springdoc plus the OpenAPI metadata bean)
to `2d681fc` (the oasdiff misreport fix), then the final review wave (six items — the money-schema
fix, the dev-profile chain's first test, per-operation `security: []`, an explicit `servers` block,
a path-count floor on the snapshot guard, and fenced oasdiff output). See `docs/superpowers/specs/2026-09-01-openapi-contract-design.md` and
`docs/superpowers/plans/2026-09-01-openapi-contract.md`. **Its SDD ledger is gone** — deleted with
the workspace at merge, as `build-hygiene`'s was — so this file, the spec, and challenges #62–#64
are now the only record of that slice's reasoning.

**Verified green: 544 tests, 0 failures, 0 errors** (521 root + 23
`platform-primitives`), up from the previous 519 baseline — a full `./gradlew clean check`, which
now includes the OpenAPI drift guard.

**That loose end is now closed: everything is pushed and the CI step is proven on both events.**
Three real runs did it — `33605853807` (push, absent-base guard), `33606310967` (push, compare
path) and `33607087225` (pull request, compare path). Its shell logic was additionally verified
locally in detail (all three no-base branches, a
structural mutation producing a real changelog, a deliberately broken `docker run` producing an
explicit failure line rather than a silently empty summary), but local verification is not a CI
run and must not be written up as one.

**The most important thing the review wave found: the document said money was a JSON `number`.**
All 31 monetary and quantity fields did, while the server has never sent anything but a string
(`BigDecimalStringModule`). Every guard on this branch — drift, byte-stability, oasdiff — compares
the document to *itself*, so none of them could see it. Fixed with one global
`SpringDocUtils.replaceWithSchema(BigDecimal.class, …)` in `OpenApiConfig` and, more to the point,
guarded by `OpenApiSnapshotTest.moneyFieldsAreDocumentedAsStrings`, the only assertion in the suite
that checks the contract against a fact about the server. It was watched failing with the override
removed. **Read challenge #64 before adding any further guard to this document.**

**`clean check` now asserts something new: that the committed API document still matches the
code.** `OpenApiSnapshotTest` generates the document from the live controllers and compares it byte
for byte against `docs/api/openapi.yaml`. Add an endpoint, rename a response field, change a status
code or add a query parameter and the build goes red until the snapshot is regenerated
(`./gradlew updateOpenApiSnapshot`) and committed **in the same change**. That is the whole
difference between having a Swagger page and having a contract. The guard was watched failing on a
deliberate API change and passing again after regeneration, and the output was proven byte-stable
across two consecutive regenerations — see challenge #63 for why the guard and the regenerator are
one test in two modes rather than two tools.

**The oasdiff CI step is now proven on both events — it was dormant config for one day and is
not any more.** The workflow fires on `push: [main]` and `pull_request`; a feature-branch push
triggers neither, so while the slice sat unpushed no CI run had ever executed the step.
**The push half:** `main` was pushed on 2026-09-02 and
run `33605853807` executed the step for the first time, resolving `BASE_SHA` to
`e9d694e386…` — `github.event.before`, the previous `origin/main` tip, which is exactly the fix
below working. That base predates the snapshot, so it correctly took the nothing-to-compare
branch. **The pull-request half is now proven too.** PR #1 (`ci-verify-pr-oasdiff`, opened and
closed on 2026-09-02 purely to fire the event — it was never meant to merge) carried one added
optional query parameter and a regenerated snapshot. Run `33607087225` resolved `BASE_SHA` to
`11b40f38…`, `main`'s tip — i.e. `github.event.pull_request.base.sha`, not the merge commit's
parent — took the compare path, and reported `1 changes: 0 error, 0 warning, 1 info / added the
new optional query request parameter ciProbe`. That also incidentally confirms oasdiff's default
severities behave as the design assumed: an added *optional* request parameter is `info`, not a
breaking change. **Both events are therefore verified on real runs, and this step is no longer
dormant config.** The `pull_request` trigger itself, dormant since the build-hygiene slice, fired
for the first time on that same run. Its shell logic *was* verified locally in detail — all three absent-base
branches (all-zero SHA, unreachable base, base predating the snapshot), a structural mutation of the
snapshot producing a real changelog entry (`api-path-removed-without-deprecation`), and a
deliberately broken `docker run` producing the explicit "failed to run" line rather than a silently
empty summary section, which is what the `pipefail` and the `if !` wrappers exist for. So the logic
is exercised; the step is not. It will first run on the merge to `main`. Treat it as the same
untested-config category as the `pull_request` trigger below and challenge #33 — dormant and
reasoned, not proven. (The review wave did fix one thing about it that only a first run would have
exposed: oasdiff emits plain text one finding per line, and `$GITHUB_STEP_SUMMARY` is Markdown,
which joins consecutive lines — so both blocks are now wrapped in fenced code blocks, written
separately from the tool output so a tool failure still closes its fence.)

### `docs/api/openapi.yaml` is generated output, not a document

**Never hand-edit it.** It is written by `./gradlew updateOpenApiSnapshot`, which runs the same test
that guards it; anything typed into the file directly is erased by the next regeneration, and in the
meantime it makes the guard red for a reason that has nothing to do with the API.

**A merge conflict in it is always resolved by regenerating, never by hand-merging the YAML.** Take
either side (or `--theirs`, it does not matter), finish the merge, then run
`./gradlew updateOpenApiSnapshot` and commit the result. Hand-resolving a 5000-line generated file
produces a document that matches neither branch's code, and the guard is the only thing that would
tell you — after the fact.

### `main` before the OpenAPI slice — still current

**Everything in this subsection and the CI one after it is current state, not history.** The
OpenAPI slice added to it rather than replacing it, so the build gates, CI behaviour and RLS
posture described here all still hold on `main` at `bead2f8`. The `build-hygiene` slice — seven tasks plus
a whole-branch-review fix wave, 25 commits off `main` at `2dc50ba` — was merged `--no-ff` as
**`83e6880`** on 2026-09-01 and its local branch deleted; the merged result was verified green
(519 tests, full `clean check`) before the branch went away. See
`docs/superpowers/specs/2026-09-01-build-hygiene-design.md` and
`docs/superpowers/plans/2026-09-01-build-hygiene.md`. **Its SDD ledger is gone** — deleted with the
workspace at merge, per the standing practice — so this file, the spec, and challenges #58–#61 are
now the only record of that slice's reasoning. Two loose ends, neither blocking: the remote branch
`origin/build-hygiene` still exists and can be deleted (`git push origin --delete build-hygiene`),
and the four intermediate CI-testing commits inside the merge are deliberate — they are the
evidence for challenge #59 and must not be tidied away.

**The new baseline command is `./gradlew clean check`, not `clean test`:** `check` now
runs test **plus** `spotlessCheck` **plus** `spotbugsMain` **plus**
`jacocoTestCoverageVerification` for both projects, so it is strictly stronger than the old
baseline and is the exact command CI runs — a green local `clean check` and a green CI run now
assert the same thing. `clean test` still works but no longer proves what "the build is green"
means on this repo. See §3 for the gate detail (SpotBugs baseline count, coverage floors, plugin
versions) and §8 for the follow-on waves this opens up.

### What CI does and does not do

**It is a post-merge smoke alarm, not a pre-merge gate.** The workflow
(`.github/workflows/ci.yml`) fires on `push: [main]` and on `pull_request`. This repo has **never
opened a pull request** — every slice merges to `main` directly with `git merge --no-ff`, including
the one that added CI. So in practice CI runs *after* a merge has already landed: it tells you
`main` broke, automatically, instead of relying on someone remembering to run the build. It cannot
stop `main` from breaking.

Broadening the trigger to all branches was considered and **deliberately rejected**: it would
arrive earlier but still block nothing, because required-status-check protection is PR-shaped and
cannot gate a direct push. Making the gate genuinely blocking means adopting pull requests — a
process change, not a config change. §3 carries the full reasoning.

Two consequences to carry: **run `./gradlew clean check` locally before you merge**, because CI
will not catch it for you beforehand; and **the `pull_request` trigger has never once fired** — it
is dormant-but-correct config, not verified behaviour, which is the same untested-gate category as
challenge #33.

**What *is* proven, on real runs rather than by assertion:** the `push: [main]` half fired
unattended on the build-hygiene merge and on the docs commit after it, both green
(`33518708457`, `33518932037`, ~3m30s each), executing all four gates across both Gradle projects
and observing the same 519 tests as a local run. A failing gate is proven to produce a failing run
too — run `33510215755` went red on `spotlessJavaCheck` from a deliberate violation during the
slice. So the alarm works; it just rings after the fact. Note also that two pushes in quick
succession now both complete: `cancel-in-progress` is scoped to pull requests only, precisely so a
follow-up commit cannot cancel the merge run you most want to see finish.

**Below this point, §0 records the slice before this one — user invitations, merged as `f265cfe`.**
It is history, not current state; `build-hygiene` has since landed on top of it. Read it when you
touch the invitation/auth area, not to find out what to do next.

Eight tasks (the `RoleGuard` extraction, the
`invitation` table/entity/repository plus both isolation-guard allowlists, the owner invite
endpoint, the pending-list + revoke endpoints, the pre-auth accept endpoint, the pre-auth preview
endpoint, the expiry and concurrency tests, and the docs wrap-up) were done on branch
`user-invitations`, off `main` at `830f4bd` — three design/plan commits (`639bb23` the spec,
`532e38a` two corrections to it, `3c5b91a` the plan), then the task commits from `42d20e2` to the
docs wrap-up `d919242`. Every task reviewed clean on its first pass.

**The whole-branch review then found four Important issues and two Minor ones, all fixed in
the four commits from `3903030` to the tip** (invite-path correctness, the pre-auth path,
housekeeping, then this docs pass):
a case variant of an existing member's address could become a second `ACTIVE` user for one human;
`accept` minted a live session for a **SUSPENDED** tenant, which only `login` had been refusing;
an expired invitation blocked its address from ever being re-invited; and the byte-identical-404
property that challenge #55 and this file both claim for all four rejection states was only
actually asserted for one of them. Two findings were deliberately **deferred, not missed** —
splitting `InvitationService` by authentication posture (revisit when password reset gives the
pre-auth half a second client), and the redundant `invitations.save(...)` on already-managed
entities in `revoke` and `accept` (identical in both, so remove both or neither). `AuthService.refresh`
has the same suspended-tenant hole; it predates this branch and was left alone on purpose.

The branch was merged `--no-ff` as **`f265cfe`** on 2026-09-01 and deleted. The merged result was
verified green — **519 tests, 0 failures, 0 errors** (496 root + 23 `platform-primitives`), up from
the 464-test baseline (+55) — before the branch went away. See §3 for what it delivered and §8 for
what that closes.

Before it, `quotation-auto-expiry` ran to completion and was merged `--no-ff` as **`2fb2b85`** on
2026-09-01, then deleted; the merged result was verified green (464 tests) before the branch went
away. There is no unmerged feature branch to settle — start at item 1 below, then go to §8 and pick
the next chunk with the user.

**One loose end that is not code, carried forward from before this slice:** the Bucket4j entry
written for `/Users/divyam/Documents/dsa/good-repos/CATALOG.md` is **on disk but unversioned** — that
directory is not a git repository, so nothing was committed there. The rate-limiting design spec §7
asked for the entry; it exists; it is just untracked. Decide with the user whether that repo should
be `git init`ed. Do not init it unilaterally.

Before that, `activity-follow-up` ran to completion and **merged to `main` as `f97c62c`**; that
feature branch is deleted, as was `record-visibility` before it (merged as `c81f59f`),
`public-rate-limiting` before that (merged as `d7725b0`), `rls-force-and-guard` before that
(merged as `3c239d1`), and `platform-primitives-module` before that (merged as `210545e`).

1. **Confirm the baseline before touching anything:** `open -a Docker`, wait for `docker info`,
   then `cd backend && ./gradlew clean check`. **`clean check` is the baseline command, not
   `clean test`** — it is strictly stronger (adds `spotlessCheck`, `spotbugsMain`,
   `jacocoTestCoverageVerification` across both projects) and is the same command CI runs, so a
   green local run and a green CI run assert the same thing. On `main` at `b239f5f` this is
   **544 tests, 0 failures, 0 errors** (521 root + 23 `platform-primitives`), up from 519 before
   the OpenAPI slice and 464 before the invitations slice.
   Gradle prints no total for a multi-project build, so count it yourself:

   ```bash
   cd backend && ./gradlew clean check
   find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + \
     | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
   find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'failures="[0-9]*"' {} + \
     | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "failures:", s}'
   ```

   If that number differs, stop and reconcile before writing code — everything below assumes it.
   **Counting only the root project's XML files produces a phantom 23-test gap** — `find .
   -path './build/test-results/test/*.xml'` alone reports 521, not 544, and this tripped an
   implementer on an earlier branch. The unqualified `find . -path '*/build/test-results/test/*.xml'`
   above spans both projects; use it, not a root-only variant.

   **A filtered run must now be project-qualified.** Unqualified `./gradlew clean test` deliberately
   spans both projects, but `./gradlew test --tests '<filter>'` applies the filter to *every*
   project and then **fails on whichever project has no match**. Use `./gradlew :test --tests '…'`
   for a root-project test and `./gradlew :platform:platform-primitives:test --tests '…'` for a
   module test. This tripped an implementer on the branch that introduced the split; it will trip
   you too if you copy a `--tests` command out of any pre-2026-08-27 doc.
2. **Read §1** (what this product is) and **§7** (non-negotiable working agreements). For a
   whole-system orientation in one sitting — every module, endpoint, state machine and data flow
   that exists today — read `docs/architecture/2026-07-29-current-architecture.md` instead of
   reconstructing it from §3 and the per-slice specs.
3. **If you are here about the AWS re-platform, microservices, the platform modules, billing,
   the outbox or identity**, read `../architecture/2026-08-27-platform-llds-handoff.md` instead of
   §8 — it is the most recent thread and chains back through
   `../architecture/2026-08-26-platform-modules-handoff.md` to
   `../architecture/2026-08-20-aws-redesign-handoff.md`. All three are design-only (no code was
   written in any of them), and each carries its own decisions, findings and ordering. **All six
   platform modules now have a low-level design.** Those handoffs say no implementation plan exists
   for any of them; as of 2026-08-27 that is half false — **module 1, `platform-primitives`, has a
   plan (`plans/2026-08-27-platform-primitives-module.md`) and is built** (§8). The other five are
   still design only.
   Read that handoff's §3 before planning anything: it records three findings (PF14, PF15, PF19).
   **Two are now closed and that handoff has not been updated to say so** — trust §8 here over it.
   PF14 (RLS `ENABLE`d but never `FORCE`d) and PF15 (no layer-3 guard) were both closed by the
   `rls-force-and-guard` slice; **PF19 is the only one still open**, and it is blocked on the
   billing thread's design, not on effort. See §8 for the current status of each.
4. **Go to §8** and pick the next chunk *with the user*. Do not start one unilaterally.
5. Then run the standard workflow on a feature branch off `main`:
   **brainstorming → (design spec →) writing-plans → subagent-driven-development →
   finishing-a-development-branch.**

§3 is the detailed inventory of what exists; §4 is history and standing gotchas. Read them when
you need the detail, not to find out what to do next.

---

## 1. What this project is

**EasyCRM** — a multi-tenant SaaS CRM for Indian tier-2/3 **distributors, traders, and small manufacturers**. React (frontend, not started) + Spring Boot + PostgreSQL. It is a real product intended to be sold.

- **Wedge:** enquiry → GST quotation → order. Stops hard at the Order (no invoicing/stock/ledger — that's Tally's job, and every target customer already runs Tally).
- **Positioning:** vertical-first (distributors/traders), not a horizontal Zoho clone.
- Full rationale is in the design spec (below). Read it before making product decisions.

## 2. Read these, in order

All under `docs/superpowers/`:

1. **`../../CLAUDE.md`** (repo root) — working agreements loaded every session. **Non-negotiable rules live here.**
2. **`specs/2026-07-22-easycrm-design.md`** — the full design spec (architecture, domain model, 4-layer isolation, import module, frontend plan, release plan). The source of truth for *what* to build.
3. **`plans/2026-07-24-p0-tenant-isolation-foundation.md`** — P0 isolation plan (DONE, merged).
4. **`plans/2026-07-25-p0-auth-core.md`** — P0-auth plan (**DONE, merged** — see §4 for what changed vs the plan).
5. **`specs/2026-07-25-p1a-master-data-design.md`** — P1a design spec (product/customer/contact/price-list master data). The source of truth for *what* P1a built.
6. **`plans/2026-07-25-p1a-master-data.md`** — P1a implementation plan (**DONE, merged** — see §4 for execution-time deviations).
7. **`specs/2026-07-26-p1b-quotation-engine-design.md`** — P1b design spec (quotation/version/item aggregate, price resolution, GST calc, lifecycle). The source of truth for *what* P1b built.
8. **`plans/2026-07-26-p1b-quotation-engine.md`** — P1b implementation plan (**DONE, merged**).
9. **`specs/2026-07-27-order-accept-design.md`** — order/accept design spec (`Order` aggregate, accept transition, event/audit seam, idempotency). The source of truth for *what* the order/accept slice built.
10. **`plans/2026-07-27-order-accept.md`** — order/accept implementation plan (**DONE, merged to `main` as `ea11d3f`**).
11. **`specs/2026-07-27-enquiry-design.md`** — enquiry design spec (`Enquiry` aggregate, 5-stage guarded lifecycle, phone-normalized one-active-per-phone dedupe, filtered list). The source of truth for *what* the enquiry slice built.
12. **`plans/2026-07-27-enquiry-slice.md`** — enquiry implementation plan (**DONE, merged to `main` as `a68035d`**).
13. **`specs/2026-07-27-enquiry-conversion-design.md`** — enquiry→quotation conversion design spec (convert-at-quotation-create; flip enquiry to `CONVERTED` + stamp `quotation.enquiry_id`, atomically). Source of truth for *what* the conversion slice built.
14. **`plans/2026-07-27-enquiry-conversion.md`** — conversion implementation plan (**DONE, merged to `main` as `06e6014`**).
15. **`specs/2026-07-27-sales-hardening-design.md`** — sales hardening design spec (optimistic-lock→409 handler + `UNIQUE(tenant_id, enquiry_id)` quote backstop). Source of truth for *what* the hardening slice built.
16. **`plans/2026-07-27-sales-hardening.md`** — sales hardening implementation plan (**DONE, merged to `main` as `abc2bd3`**).
17. **`engineering-challenges.md`** — running log of non-obvious problems + solutions (63 entries). Great context on the stack's quirks.
18. **`annotations-reference.md`** — living glossary of every Spring/JPA annotation used.
19. **`specs/2026-07-28-order-lifecycle-design.md`** — order lifecycle design spec (`DISPATCHED`/`CLOSED`/`CANCELLED` transitions + the deferred order-list filter fix). Source of truth for *what* this slice built. **DONE** — spec committed directly as `8a6c9dd`; the slice it describes is implemented and merged as `8247579`.
20. **`plans/2026-07-28-order-lifecycle.md`** — order lifecycle implementation plan. **DONE** — plan committed directly as `8c0703f`; executed in full and merged as `8247579`.
21. **`specs/2026-07-28-quotation-pdf-share-design.md`** — quotation PDF/share design spec
    (server-side rendering, the tenant-resolution seam for a public link, `share_link`'s
    plaintext-token design, the `wa.me` deep link, and the deferred `QuotationService.list`
    filter fix). Source of truth for *what* this slice built. **DONE, merged as `8b6644b`.**
22. **`plans/2026-07-28-quotation-pdf-share.md`** — quotation PDF/share implementation plan (10
    tasks: PDF engine spike, Indian-format money, tenant profile columns, the Thymeleaf template,
    the render endpoint, the `share_link` table, the share endpoint, the public endpoint, the
    list-filter fix, this docs wrap-up). Executed in full, every task reviewed clean.

**Not under `docs/superpowers/`** — whole-system architecture, added 2026-07-29 (no code change):

23. **`../architecture/2026-07-29-current-architecture.md`** — HLD, LLD and data flow for **what
    exists on `main` today**, derived by reading the source at `908d9e6`, not the specs. Module map,
    the four isolation layers, full ER diagram, the complete REST surface, the error contract, all
    three state machines, the GST and numbering algorithms, and six end-to-end data flows. Its
    Part 4 is an explicit inventory of **what is deliberately absent** — read that before planning
    anything, so you don't assume a feature exists.
24. **`../architecture/2026-07-29-target-architecture.md`** — the same three views for the system
    **once every feature on record is built** (P0–P5, import, frontend, and the §8 backlog). Ends
    in a sized gap ledger, today → target. Everything in it is a target; nothing in it is built
    unless it also appears in doc 23.
25. **`../architecture/2026-08-20-aws-redesign-handoff.md`** — handoff for the **AWS re-platform
    design thread** (2026-08-19/20, docs only, no code). Points to four new docs: the five-service
    ECS target architecture, the billing/entitlements spec, the outbox LLD with its test plan and
    bug catalogue, and outbox interview Q&A. **Read it before proposing anything about AWS,
    microservices, messaging or billing** — those decisions are already made and reasoned, and it
    lists what remains unverified. It also records three findings about the code *as it stands*,
    including a live bug: `QuotationVersion` does not snapshot the buyer, so re-rendering a `SENT`
    quotation after a customer edit produces a different document.

26. **`../architecture/2026-08-27-platform-llds-handoff.md`** — handoff for the **platform-module LLD
    thread** (2026-08-26/27, docs only, no code), which closes the six-module queue. The four LLDs it
    produced — `platform-security`, `platform-tenancy`, the revised `platform-outbox`, and
    `platform-entitlement` — sit beside it in `docs/architecture/`, and the parent spec they amend is
    `specs/2026-08-26-shared-platform-modules-design.md`. **Read its §3 before anything else in this
    area**: PF14/PF15 are about tenant isolation as it works on `main` today, not about the future
    split.

**The first module built from that LLD queue** — the only entries below that changed code:

27. **`../architecture/2026-08-26-platform-primitives-lld.md`** — LLD #1 of 6, and the only one that
    is **IMPLEMENTED**. Read its **Appendix B** rather than re-deriving it: it now carries the
    verified answers (the Boot 4 Jackson auto-config artifact, how a `JacksonModule` bean is
    discovered, which of `EventJson`'s pinned settings actually change behaviour today, whether
    ArchUnit can express R1, the `WRITE_NUMBERS_AS_STRINGS` default, and what `api(...)` on
    jackson-databind actually pulls in). Its Appendix A now carries the outcome of MF1–MF6 plus four
    new findings MF7–MF10, and a new Appendix C lists what the implementation added that the design
    did not specify.
28. **`plans/2026-08-27-platform-primitives-module.md`** — the eight-task implementation plan for it
    (**DONE** — see §3). Task 5's brief is the one worth reading even if you never touch this
    module: its mandatory prove-it-can-fail step is the only reason challenge #33 was caught.

**Not a platform-LLD module — a hardening slice off the `rls-force-and-guard` baseline:**

29. **`specs/2026-08-27-public-rate-limiting-design.md`** — per-IP rate limiting design spec
    (Bucket4j token buckets per `(policy, client-IP)`, the public/auth policy set, the
    `RateLimitStore` port, and the deliberate choice to key on socket address rather than
    `X-Forwarded-For`). Source of truth for *what* this slice built.
30. **`plans/2026-08-28-public-rate-limiting.md`** — the seven-task implementation plan for it.
    **Merged to `main` as `d7725b0`** — see §3.
    Worth reading even if you never touch rate limiting: the plan's own code was wrong in five
    separate places that only surfaced during execution (a record that could not bind, a static
    factory colliding with a record accessor, a Jackson 2 import on a Jackson 3 project, a
    `BindResult` overload that does not exist in Boot 4.1, and a test-property precedence rule that
    is the reverse of what the plan assumed). Challenges #38–#42 are the write-ups.
31. **`specs/2026-08-29-record-visibility-design.md`** — record-level visibility design spec (the
    two-tier `assigned_to` rule, `VisibilityPolicy`/`VisibleFinder`, why quotation/order visibility
    derives from the customer rather than adding a column, the deliberately unfiltered dedupe/GSTIN
    lane, and why `SALES_MANAGER` is deliberately collapsed into the unrestricted tier). Source of
    truth for *what* this slice built.
32. **`plans/2026-08-29-record-visibility.md`** — the nine-task implementation plan for it.
    **Merged to `main` as `c81f59f`** — see §3.
33. **`specs/2026-08-30-activity-follow-up-design.md`** — activity log & follow-ups design spec (the
    polymorphic subject link across the four visibility-scoped aggregates, the two-strategy
    visibility gate in §4, why `OVERDUE` is a read-time predicate rather than a status column, the
    three flows, and the deliberate non-implementation of the parent spec's reminder scheduler).
    Source of truth for *what* this slice built. §5.3 was corrected during the slice from the
    single `V29__rls_activity_follow_up.sql` originally described to the four-file split the plan
    actually uses.
34. **`plans/2026-08-30-activity-follow-up.md`** — the fourteen-task implementation plan for it.
    **Merged to `main` as `f97c62c`** — see §0 and §3.
35. **`specs/2026-08-31-quotation-auto-expiry-design.md`** — quotation auto-expiry design spec (the
    `TenantJobRunner` seam for jobs with no JWT, the `TenantContext.runAs`-before-transaction
    ordering, why `asOf` must be computed in IST rather than the server's UTC clock, and the
    audit + activity event pair on expiry). Source of truth for *what* this slice built.
36. **`plans/2026-08-31-quotation-auto-expiry.md`** — the seven-task implementation plan for it.
    Executed in full, every task reviewed clean; **merged to `main` as `2fb2b85`** — see §0 and §3.
37. **`specs/2026-09-01-user-invitations-design.md`** — user-invitations design spec (why
    `invitation` is the third global table, why its token is hashed when `share_link`'s is
    plaintext (§3), the `RoleGuard` extraction, the accept ordering (§6.2), the consistent-404
    error contract (§8), why expiry is lazy with no job (§7), and what `acceptUrl` points at
    (§6.3/D10)). Source of truth for *what* this slice built.
38. **`plans/2026-09-01-user-invitations.md`** — the eight-task implementation plan for it.
    Executed in full, every task reviewed clean; **merged to `main` as `f265cfe`** — see §0 and §3.
39. **`specs/2026-09-01-build-hygiene-design.md`** — build-hygiene design spec (Wave 1 of a
    three-wave hygiene programme; the version-catalog/convention-plugin structure, the Spotless
    exclusion reasoning, the SpotBugs baseline-vs-fix-now decision, the measure-then-floor JaCoCo
    approach, and the full Wave 1.5/2/3 triage in §3). Source of truth for *what* this slice built
    and *why the rest is deferred*. **Note:** §10's `--add-exports` risk entry was written before
    execution and turned out to be wrong — see §5 below for the corrected finding.
40. **`plans/2026-09-01-build-hygiene.md`** — the seven-task implementation plan for it.
    Executed in full; **merged to `main` as `83e6880`** — see §0 and §3. Worth reading even if you
    never touch build tooling, for the same reason the rate-limiting plan is: several things the
    plan asserted turned out to be wrong under execution — every pinned action and plugin version
    was stale, the `--add-exports` risk was overstated, one `sed` targeted a class declaration that
    does not exist, the CI trigger it specified cannot fire on a feature branch, and it claimed a
    version duplication was structural when it is not. Challenges #58–#61 are the write-ups.
    **Its SDD ledger was deleted with the workspace at merge**, so the rulings it recorded survive
    only where they were copied out — §0's CI subsection, §3's gate detail, §5's environment notes
    and §6's stack quirks. Do not go looking for it.
41. **`specs/2026-09-01-openapi-contract-design.md`** — OpenAPI contract design spec (Wave 3 of the
    hygiene programme; why the snapshot is committed and guarded rather than merely served, the
    `implementation`/`developmentOnly` split that keeps swagger-ui out of `bootJar`, the typed error
    envelope, the dev-only exposure, and why the oasdiff gate reports instead of blocking). Source
    of truth for *what* this slice built.
42. **`plans/2026-09-01-openapi-contract.md`** — the seven-task implementation plan for it.
    Executed in full and **merged as `bead2f8`** — see §0 and §3. Read it for the two places
    execution contradicted it: the springdoc major line (3.x is Boot
    4; the plan's own research had to establish that 2.x, which every tutorial names, does not
    work here) and the predicted test count, which was 529 against an actual 530 on the branch and
    534 after the review wave. Its SDD ledger is gone, deleted with the workspace at merge.

## 3. Current state

- **Latest code work: members management** — **merged to `main`**, off `main` at `e9d694e`
  (branch `worktree-members-management`). Eight task commits (`0d80e9b`..`f0ce72c`) plus
  two intermediate fix-up commits folded in along the way (`2a11fa7` a Spotless reformat,
  `1577d50` a defensive copy for a new SpotBugs finding — challenge #67), every task reviewed
  clean. Closes the gap `user-invitations` deliberately left open (below): a workspace with more
  than one user is now *administrable*, not just creatable.

  **Four owner-only routes on `com.easycrm.iam.web.MemberController`, at `/api/v1/members`**,
  mirroring `InvitationController`'s shape (authenticated-only, no `SecurityConfig` change
  needed): `GET /api/v1/members` (unpaged `List<MemberResponse>` — a tenant has a handful of
  users, matching `listPending`'s precedent), `POST /{id}/role` (`ChangeRoleRequest`, `@Pattern`
  validated the same way `InviteRequest.role` is, so an unknown role is a 400, not a 422 or a raw
  Jackson failure), `POST /{id}/disable`, and `POST /{id}/enable`. `MemberResponse` never carries
  `passwordHash`. `User` gained its first mutators — `changeRole(Role)`, `disable()`, `enable()` —
  each with an already-in-that-state guard on the entity throwing `ConflictException`, matching
  `Quotation.expire()`/`Invitation.revoke()`; `changeRole` has no guard, because re-assigning the
  role a member already holds is a harmless, idempotent retry. Target resolution needs no
  hand-written tenant filter: `app_user` is `@TenantId` + RLS, so another tenant's member 404s
  structurally, in deliberate contrast to `InvitationService.revoke`'s hand-written filter
  (challenge #54), which is load-bearing only because `invitation` is a global, RLS-exempt table.

  **`AssignedWorkload` — a port declared in `iam`, implemented in `crm` and `sales` — is the
  slice's central structural decision.** Disabling a member who still holds open work strands
  that work, since a disabled member cannot log in to hand it off, so disable is refused (409)
  while the member holds an open `Customer`, `Enquiry`, or `FollowUp` assigned to them. Counting
  that requires a tenant-wide, *unfiltered* read across three repositories that
  `VisibilityScopingArchTest` normally restricts to `platform.visibility`, from a package (`iam`)
  that must not depend on `crm`/`sales` — the reverse of the one dependency edge that already
  exists (`crm`/`sales` → `iam`, via `AssignableUsers`). Rather than route the count through
  `VisibleFinder` (correct today only because an owner's visibility policy happens to be
  unrestricted, and silently wrong the day a non-owner reaches the path), `iam` declares
  `AssignedWorkload` (`label()`, `countOpenFor(UUID)`) and `crm.CustomerWorkload`,
  `sales.EnquiryWorkload`, `sales.FollowUpWorkload` implement it; `MemberService` injects
  `List<AssignedWorkload>` and aggregates. The dependency arrow this creates
  (`crm`/`sales` → `iam`) already existed, so `iam` gains zero new imports and the package graph
  stays acyclic. Three new entries on `VisibilityScopingArchTest.ALLOWED_METHODS` are the accepted
  cost — the same allowlist that already carries `findByGstin`/`findByNormalizedPhone` for the
  identical reason: *must see the whole tenant or the invariant breaks*. Challenge #66 is the
  write-up; `V33__assigned_to_indexes.sql` adds the two `(tenant_id, assigned_to)` indexes this
  slice's three new count queries actually run against, on `customer` and `enquiry` (`follow_up`
  already had its equivalent).

  **The last-active-owner invariant, and the tenant-row lock that actually closes it.** A
  workspace must never reach zero `ACTIVE` `OWNER`s — every member-admin route (and invite/revoke)
  calls `RoleGuard.requireOwner`, and this product has no support surface, so a stranded tenant
  needs a manual production `UPDATE` to recover. A plain `count(active OWNERs) > 1` check is
  check-then-act and misses the case where two owners demote each other at the same instant: both
  read 2, both pass, both commit, and the tenant reaches zero. That is write skew (two
  transactions read an overlapping set, then write disjoint rows), not a lost update — `@Version`
  guards one row and these are two, a unique index only expresses "at most one," and Postgres
  `REPEATABLE READ` catches write-write conflicts on the same row, not this. The fix: every
  member-admin write (role change, disable, enable — uniformly, not just the two paths that can
  reduce the count) takes a `PESSIMISTIC_WRITE` lock on the **tenant row** first
  (`TenantRepository.findForUpdate`), serialising the second writer behind the first so its
  re-count under the lock sees the truth. `MemberOwnerRaceTest` proves the anomaly is real by
  failing when the lock is removed (`expected: <1> but was: <2>`) before proving the fix closes
  it. Challenge #65 is the write-up, including why this is a different use of the
  `@Lock(PESSIMISTIC_WRITE)` idiom than challenge #16's gapless-numbering precedent: #16 locks the
  row it is about to write; this locks a row **neither** transaction would otherwise touch, purely
  to manufacture the contention point the invariant needs.

  **What makes `disable` actually bite, not just look like it.** Two of four layers were new:
  `RefreshTokenService.revokeAllForUser` ends every one of the member's sessions the moment
  they're disabled, and — the load-bearing fix — `AuthService.refresh` now refuses a non-`ACTIVE`
  user. Before this slice, `refresh` rotated the token and minted a new access token with **no
  status check at all**, so a disabled member's refresh token kept working indefinitely; the
  rejection reuses the existing generic `UnauthorizedException("invalid refresh token")`, so the
  endpoint gains no enumeration signal. `AssignableUsers.require` and `AuthService.login` already
  refused a non-`ACTIVE` user and needed no change.

  **`ConflictException` gained an optional `Map<String, Object> fields`**, so the reassign-first
  409 can carry structured counts (keyed by `AssignedWorkload.label()`) for a frontend to route
  on, instead of parsed prose. The single-argument constructor is unchanged and still yields no
  `fields` key, so **every pre-existing 409 in the codebase stays byte-identical**.
  `ApiExceptionHandler.conflict(...)` passes the map through — expect a mechanical merge conflict
  here against `openapi-contract` (§0), which touches the same method concurrently for an
  unrelated reason.

  **Two caveats carried forward, neither fixed here, both accepted deliberately (design spec
  §6.1/§10):**
  - **The ≤15-minute access-token window.** `JwtAuthenticationFilter` does no database read, so a
    disabled or just-demoted member's already-minted access token keeps working until it expires
    (`easycrm.jwt.access-ttl-seconds` = 900). Closing it properly means a per-request user lookup
    in a filter that runs before any transaction or tenant binding exists — not worth it for a
    15-minute window on a rare operation with no hostile-insider threat model yet. If this is ever
    revisited, shorten the access TTL; don't add the read.
  - **The suspended-tenant hole in `AuthService.refresh` is still open.** `refresh` does not check
    `TenantStatus` either, so a suspended tenant's users keep refreshing — out of scope here (it's
    a *tenant* lifecycle concern, not a *member* one), but worth naming because this slice
    modified the very same method for a different reason, and the fix is one condition away.

  **561 tests, 0 failures, 0 errors**, up from the 519-test baseline — `./gradlew clean check`
  green from a clean state, Spotless clean, SpotBugs 0 findings. New challenges **#65–#67** (#65
  the last-owner write-skew and the tenant-row lock, #66 the invariant-check-must-not-filter
  tension that produced `AssignedWorkload`, #67 the build-process lesson about running the full
  gate only at milestones instead of every task); the annotations reference needed one addition —
  a second use site on the existing `@Lock`/`LockModeType` row for
  `TenantRepository.findForUpdate`.
- **Previous code work: the OpenAPI contract** — **merged to `main` as `bead2f8`** (off `main` at
  `e9d694e`, seven tasks plus a whole-branch-review fix wave, every one reviewed), and pushed.
  **544 tests, 0 failures,
  0 errors** (511 root + 23 `platform-primitives`), up 15 from `main`'s 519. What it delivers:

  **springdoc 3.1.0, split across two Gradle configurations on purpose.**
  `springdoc-openapi-starter-webmvc-api` is on `implementation` — the generator ships;
  `springdoc-openapi-starter-webmvc-ui` is on `developmentOnly`, Spring Boot's own configuration
  for "on the `bootRun` classpath, excluded from `bootJar`". The swagger-ui webjar therefore cannot
  reach a production artefact even if someone later flips `springdoc.swagger-ui.enabled` by
  mistake: structural absence rather than a configured one, verified by unzipping the jar rather
  than by reading the Gradle docs. **3.x is the Spring Boot 4 line** (its POM parent is
  `spring-boot-starter-parent` 4.1.0); the 2.x line is Boot 3 and does not work here, which matters
  because every pre-2026 tutorial names 2.x. `OpenApiConfig` supplies only what springdoc cannot
  infer — title, description, the `bearer-jwt` security scheme, the `servers` entry — and takes
  `info.version` from `BuildProperties`, i.e. from the Gradle project version via
  `springBoot { buildInfo() }`, so there is one copy of that number rather than a literal in
  `application.yml` to keep in sync. `servers` is taken from `easycrm.public-base-url`, the one
  property this app already treats as its canonical external origin (share and invitation links
  are both built from it); left implicit, springdoc synthesized the entry from whatever request
  fetched the document and published `url: http://localhost` — the MockMvc origin, wrong rather
  than merely vague, in the artefact a frontend reads.

  **Money is documented as a string, and that is enforced, not just fixed.** springdoc infers
  schemas from the Java type, so `BigDecimal` came out as `type: number` on all 31 monetary and
  quantity fields while `BigDecimalStringModule` has always serialized them as JSON strings. One
  static `SpringDocUtils.getConfig().replaceWithSchema(BigDecimal.class, new
  StringSchema().format("decimal"))` in `OpenApiConfig` fixes every occurrence — global, because 31
  per-field annotations are 31 chances to miss one and the next DTO would have none. Request-body
  fields (`rate`, `qty`, `discountPct`) become `string` too, which is intended. The guard is
  `OpenApiSnapshotTest.moneyFieldsAreDocumentedAsStrings`; it was proven able to fail. See
  challenge **#64** — every other guard on this document compares it to itself.

  **The genuinely public operations carry an empty `@SecurityRequirements`.** The document-level
  `bearer-jwt` requirement applied to *everything*, including `POST /api/v1/auth/login` — the first
  call a frontend writes and the one call that by definition has no token. Seven operations now
  emit `security: []`, mirroring `SecurityConfig`'s `permitAll` list exactly: auth
  signup/login/refresh/logout, the invitation preview/accept pair, and `GET /public/q/{token}`.
  **`GET /api/v1/auth/me` is deliberately not among them** — it is `authenticated()`.

  **The error envelope is typed.** `ApiErrorResponse(ApiError error)` and
  `ApiError(String code, String message, Map<String, Object> fields)` replaced
  `ResponseEntity<Map<String, Object>>` across all seven `ApiExceptionHandler` handlers, with
  `@ApiResponse`/`@Content`/`@Schema` declaring one response per distinct status. The acceptance bar
  was **byte-identical output**, not "an equivalent document": `@JsonInclude(NON_NULL)` at type
  level preserves the omit-`fields`-when-absent behaviour, and the defensive copy inside `ApiError`
  is a wrapped `LinkedHashMap` rather than `Map.copyOf` specifically to preserve key order
  (challenge #62 — `Map.copyOf` randomizes iteration order per JVM boot). The proof of behaviour
  preservation is procedural, not rhetorical: `ApiErrorWireFormatTest` was written and committed
  **first and separately** (`26371ff`), passing against the old `Map`-based handler, and still
  passes against the records. No assertion outside `platform/error/` was touched.

  **`@ParameterObject` on the eight `Pageable` list endpoints; `@Hidden` on `DemoRecordController`.**
  The first is what makes the document advertise `page`/`size`/`sort` — the parameters a client can
  actually send — instead of a `Pageable` schema nobody can construct. The second removes the P0
  isolation fixture from the document; **it does not remove the route**, which stays live and
  authenticated in every profile. Whether that controller should exist in production at all is a
  separate decision that was deliberately not taken here.

  **`docs/api/openapi.yaml`, committed and guarded.** `OpenApiSnapshotTest` fails
  `clean check` whenever the generated document and the snapshot disagree; `./gradlew
  updateOpenApiSnapshot` regenerates it. The guard also asserts a **floor**: at least 45 `/api/v1/`
  paths, against the 54 the app publishes today. Without it, a misconfigured
  `springdoc.paths-to-match`, a stray `@Hidden` or a narrowed scan base-package would leave `info`
  intact while `paths` came back empty — and *both* modes would pass, write mode by overwriting the
  contract with a gutted document and read mode by then comparing gutted against gutted. The
  threshold is deliberately loose: pinning the exact count is the snapshot's job. **The guard and the regenerator are one test in two modes,
  not two tools** — see §0's standing note and challenge #63. Determinism is pinned with
  `springdoc.writer-with-order-by-keys: true`, confirmed present on 3.1.0 before being depended on,
  and the output proven byte-stable across two regenerations. The guard was watched going red on a
  throwaway query parameter and green again once it was removed.

  **`/v3/api-docs` and `/swagger-ui/**` are exposed only under the `dev` profile, in two
  independent layers.** Layer 1: springdoc's own `api-docs.enabled`/`swagger-ui.enabled` are
  `false` in `application.yml` and `true` only in `application-dev.yml`, so outside dev the routes
  are never registered and there is nothing for a security rule to have to deny. Layer 2:
  `DevApiDocsSecurityConfig`, a `@Profile("dev")` `@Order(0)` `SecurityFilterChain` whose
  `securityMatcher` names exactly five springdoc paths. **`SecurityConfig` was not modified** —
  `git diff main -- …/SecurityConfig.java` is empty, and deleting the new file restores today's
  behaviour exactly, with no conditional hole left in the real chain to reason about later. Verified
  live, not only in tests: `bootRun` under the dev profile served both routes with 200. It is now
  also verified *in* tests: `DevApiDocsSecurityConfigTest` is the **only** class in this suite with
  `@ActiveProfiles("dev")`, and therefore the only place that bean exists in a test context at all.
  Two assertions — `/v3/api-docs` returns 200 (nothing had ever demonstrated the dev chain works),
  and an unauthenticated `/api/v1/customers` still returns 401 (an `@Order(0)` chain with a widened
  `securityMatcher` would silently make the whole API public in dev). **Deliberately one class**: an
  `@ActiveProfiles` value is a distinct context cache key, so this costs the suite its second Spring
  context; do not spread dev-profile assertions across more classes. `ApiDocsExposureTest` runs
  *without* the dev profile and its `healthIsStillReachable` never guarded this — the comment
  claiming it did has been corrected.

  **CI reports an API changelog and does not block on it.** A `continue-on-error` oasdiff step
  (`tufin/oasdiff:latest`, `changelog` then `breaking`) writes into the job summary on every push
  and pull request. **The base it diffs against is `github.event.before` on a push and
  `github.event.pull_request.base.sha` on a PR — not `HEAD~1`**, which was the original form and
  was wrong on both events: on a push it compares only the last commit, so a push carrying N
  commits reports nothing for the other N-1, and this repo pushes a merge plus its docs
  follow-ups together as a matter of course. Measured on the real branch: with `HEAD~1` the
  pending 19-commit push reported "No changes detected"; with `event.before` the same range
  reports the 187 money-schema changes. That also forces `fetch-depth: 0` on the checkout — the
  base can be any distance back, and a shallow clone that lacks it fails *silently* into the
  "nothing to compare" path. **`OasdiffWorkflowTest` guards all of this** — it parses the real
  `ci.yml` (injected as the `ci.workflow` system property, the same trick the snapshot guard uses)
  and executes the step's own extracted shell body against throwaway git repos with `docker`
  stubbed, so it tests *our* base-selection logic without depending on Docker Hub. Ten tests:
  the per-event base wiring, `fetch-depth: 0`, `continue-on-error`, the push and PR base choices,
  all three no-base branches, and that a tool failure still writes its explicit line and closes
  both fences. Proven able to fail: reverting `ci.yml` to `HEAD~1` + `fetch-depth: 2` turns three
  of them red, and dropping the PR base from the expression turns a fourth. It reports rather than
  gates for two reasons: CI here is post-merge (§0), so a blocking gate would fail *after* the
  breaking change landed; and there is no consumer yet, so it would fire regularly, at nobody, on
  correct work — which is how gates get ignored. **Flip `continue-on-error` to `false` when the
  frontend exists and consumes this spec.** **Both event paths are proven on real runs** — see §0
  for the three run IDs. **The base it diffs against is `github.event.before` on a push and
  `github.event.pull_request.base.sha` on a PR, never `HEAD~1`**, and `OasdiffWorkflowTest`
  guards that: it parses the real `ci.yml` and executes the step's own extracted shell against
  throwaway git repos with `docker` stubbed. See the CI-tooling evaluation in §8 for why that
  harness exists rather than Bats or `act`.

  New challenges **#62** (a new record with a `Map` component fails SpotBugs on the first build,
  and byte-identical is a stricter bar than immutable), **#63** (one generator, two modes) and
  **#64** (every guard compared the document to itself, so it was consistent, deterministic,
  drift-proof and wrong about money). `annotations-reference.md` gained rows for `@ParameterObject`,
  `@Hidden`, `@Order`, `@SecurityRequirements` and `@ActiveProfiles`, and the `@Profile`,
  `@JsonInclude`, `@TestPropertySource` and `@AutoConfigureMockMvc` rows were extended.

  **Carried, not done:** operations are still tagged with springdoc's default internal class names
  (`activity-controller`, `public-share-controller`). Cosmetic, and fixing it means `@Tag` on 16
  controllers; ruled out at the end of this branch rather than forgotten.

- **Previous code work: build hygiene** — **merged to `main` as `83e6880`** (branch deleted). 25
  commits off `main` at `2dc50ba`: seven tasks, every one reviewed clean on its first pass with no
  fix round anywhere, plus a whole-branch-review fix wave of five commits (`82e0f4e`..`ee4641c`)
  that closed six Minors and two nits. Zero application-code
  diff other than a reformat and a temporary, reverted proof-of-concept violation (below); the
  entire slice is build configuration: `gradle/libs.versions.toml` (a version catalog with the
  justifying comment for every non-obvious pin travelling with the number, not left behind in
  build files), a `buildSrc` precompiled convention plugin (`easycrm.quality-conventions`) applied
  explicitly by both Gradle projects, and four new gates.

  **Spotless, `palantirJavaFormat()` 2.97.0, applied whole-tree in one mechanical commit
  (`2616049`), recorded in `.git-blame-ignore-revs`.** 4-space/120-col, the closest match to the
  pre-existing style. Two exclusions are structural (extension-scoped targets, not a rule that
  could later be deleted): `db/migration/*.sql` and `templates/quotation.xhtml` are never matched
  by any Spotless target. The `.sql` exclusion is not a style choice — Flyway checksums every
  applied migration, and a reformatted byte would pass CI silently (a fresh Testcontainers
  database recomputes the checksum from the new text) while failing `flyway validate` against any
  database that already ran the old text; see engineering-challenges #60. **`importOrder()` was
  NOT added** — palantir-java-format reorders imports itself (it collapses the codebase's
  separated `java.*`-last block into one alphabetical group), and the design spec's own §5 left
  this an open question to be resolved in the task, not asserted; a second ordering step would
  have fought palantir's and produced an unstable format.

  **SpotBugs 6.5.11 at `effort = MAX` + find-sec-bugs 1.14.0, gated by a baseline of
  today's findings — 32 total, split 29 root / 3 `platform-primitives`.** By category:
  `EI_EXPOSE_REP2` ×17, `EI_EXPOSE_REP` ×8, `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` ×3,
  `CT_CONSTRUCTOR_THROW` ×3, `MS_EXPOSE_REP` ×1. 26 of the 32 are the defensive-copy family
  (`EI_EXPOSE_REP2`/`EI_EXPOSE_REP`/`MS_EXPOSE_REP`), largely noise on JPA entities and records —
  see the §8 backlog item for the case to make that a permanent `config/spotbugs/exclude.xml`
  category exclusion rather than baseline debt. The other 6
  (`NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` ×3, `CT_CONSTRUCTOR_THROW` ×3) deserve a real look.
  **Zero SECURITY-category findings** from find-sec-bugs across JWT mint/parse, the bcrypt
  password path, the `permitAll` PDF route, and the rate limiter — verified as a genuine clean
  result rather than a silently-unloaded plugin (`-pluginList` shows the jar in `--info` output;
  the report XML carries `<Plugin id="com.h3xstream.findsecbugs" enabled="true"/>`). The baseline
  mechanism is `spotbugs { baselineFile = ... }`, which SpotBugs Gradle plugin 6.5.11 wires
  through to the SpotBugs-core `-excludeBugs` flag (matched by `instanceHash`, not by project, so
  one file safely covers both) — confirmed by decompiling the plugin jar, not by trusting the
  property's existence; challenge #58 is the write-up, including the `--` in an XML comment that
  silently broke it once.

  **JaCoCo, per-project floors measured before being set (design spec D10), not assumed.**
  Measured 2026-09-01: root (`easycrm-backend`) LINE 93.89% / BRANCH 82.34%;
  `platform-primitives` LINE 84.13% / BRANCH 100.0%. Floors (round the measured value down to the
  nearest whole percent, then subtract one, so an unlucky run doesn't redden the build; ratchet up
  only): root LINE `0.92` / BRANCH `0.81`, `platform-primitives` LINE `0.83` / BRANCH `0.99`. **A
  conventional 80%/70% bar would have been slack against 93.9% measured line coverage** — the
  measure-first decision is vindicated, not merely defensible, by the actual number. One open
  question, decided but recorded for the eventual reviewer to reweigh: the module's BRANCH floor
  is `0.99` against a measured `100.0%`; a reviewer argued for `1.0` since branch coverage on a
  small, deterministic module doesn't have the "-1 for an unlucky run" excuse LINE does, and a
  single new untested branch among 22 already drops to 0.956 — well under either floor. Kept at
  `0.99` for rule-uniformity (all four floors computed the same mechanical way), not because the
  argument for `1.0` is wrong. `jacoco-report-aggregation` is deliberately not applied — the two
  projects (496 Spring-integration tests vs. 23 pure-value-type tests) have different enough
  character that one blended number would mask movement in either.

  **`.github/workflows/ci.yml`: `ubuntu-latest`, JDK 25 (Temurin), `./gradlew clean check
  --no-daemon`, triggered on `push: [main]` and `pull_request:`.** One command — the same one a
  developer runs — so CI and a local run produce the same verdict, with no CI-only checks.
  **CI is a post-merge smoke alarm, not a pre-merge gate, and that is a deliberate, reasoned
  choice, not an oversight.** Every slice so far merged with `git merge --no-ff` — `f265cfe`,
  `2fb2b85`, `f97c62c`, `c81f59f`, … — and that has not changed. **The `pull_request` trigger is
  no longer unproven, though:** PR #1 (`ci-verify-pr-oasdiff`, 2026-09-02) was opened purely to
  fire it, confirmed the whole job runs on that event, and was closed without merging. That
  retires the challenge-#33-shaped doubt about the trigger itself — but it changes nothing about
  the workflow's *shape*. Under `push: [main]` + `pull_request`, with PRs not part of the merge
  habit, CI still only runs *after* a merge lands: it can tell you `main` broke, it cannot stop `main` from breaking. Broadening the
  push trigger to every branch was considered and **rejected**: it would fire earlier, but
  still block nothing, because required-status-check branch protection is PR-shaped and cannot
  gate a direct push to `main` — so broadening buys earliness at the cost of Actions minutes on
  every docs/spec/plan-only commit (of which this repo makes many per slice), plus genuinely
  duplicate runs if PRs are ever adopted later, since the workflow's `concurrency` group is keyed
  on `github.ref`, which differs between a branch and its PR. Making CI genuinely blocking means
  adopting pull requests — a process change for the user to choose, not something this slice
  should decide quietly. Proven with a real red run, not just gate execution (Task 6 already
  proved the gates execute): pushing a deliberate `trimTrailingWhitespace` violation to a
  temporarily-widened trigger produced a FAILED run on `spotlessJavaCheck` with a non-zero exit;
  see the Task 7 report for the run URL and log excerpt. The workflow file and
  `TenantJobRunner.java` both end this slice byte-identical to their pre-slice state
  (`grep -n build-hygiene .github/workflows/ci.yml` returns nothing).

  **A fresh clone needs one command Spotless's own blame-ignore doesn't give it for free:**
  `git config blame.ignoreRevsFile .git-blame-ignore-revs`. GitHub's web blame honours
  `.git-blame-ignore-revs` automatically; local `git blame` does not until this is set. Verified:
  with it unset, 8 lines of `TenantJobRunner.java` still attribute to the reformat commit
  `2616049`; with it set, they fall back to the real original commit `a855056b`. See §5.

  **519 tests, 0 failures, 0 errors** (496 root + 23 `platform-primitives`) — unchanged from the
  `user-invitations` baseline, as this slice makes no application-code change. New challenges
  **#58–#61** (#58 SpotBugs baseline mechanism/XML pitfall, #59 the CI chicken-and-egg problem, #60
  the Flyway-checksum exclusion, #61 the version-catalog blind spot inside `buildSrc`); the
  annotations reference needed no new rows — this is a build-tooling slice, not application code.

- **Previous code work: user invitations** — **merged to `main` as `f265cfe`.** Off `main` at
  `830f4bd`; the task commits run from `42d20e2` (the `RoleGuard`
  extraction) through `4f8cc7f` (the expiry and race tests) to this docs wrap-up, with challenge #54
  logged in `0840c38` and #55 in `fb30786`. Eight tasks closing the last
  open piece of the P0-auth follow-up (§8 item 3), and the first thing in this codebase that lets a
  tenant have more than one user.

  **`invitation` (migration `V31__invitation.sql`) is the third GLOBAL, RLS-exempt table**, after
  `refresh_token` and `share_link`, for the reason all three share: accepting is **pre-auth**, so
  the tenant must be resolved *from the token* before any context exists. It carries a plain
  `tenant_id` column with no `@TenantId` and no RLS policy, and is therefore allowlisted in **both**
  isolation guards: `TenantScopingArchTest.GLOBAL_TABLES` (layer 2, now **four** entries — `Tenant`,
  `RefreshToken`, `ShareLink`, `Invitation`) and `RlsCoverageIntegrationTest.GLOBAL_TABLES` (layer 3,
  now **three** — `refresh_token`, `share_link`, `invitation`). **The two counts differ on purpose
  and always will:** `tenant` has no `tenant_id` column at all, so the RLS guard's query never sees
  it and it needs no exemption there. Any *further* global table must be added to both lists in the
  same change; the RLS guard fails on a *stale* exemption as well as a missing one, so they cannot
  quietly drift apart.

  **Three indexes ship in the creating migration** (the standing agreement in §8): `UNIQUE
  (token_hash)`; a **partial** `UNIQUE (tenant_id, lower(email)) WHERE status = 'PENDING'`, which
  makes at-most-one-live-invitation-per-address a database fact rather than a check-then-act race
  while letting accepted/revoked rows accumulate as history; and `(tenant_id, status, expires_at)`
  for the owner's pending list. **`V32__app_user_case_insensitive_email.sql` adds a fourth**, on
  `app_user (tenant_id, lower(email))`, alongside — not instead of — `uq_user_tenant_email`: the
  invitation table folds case and `app_user` did not, so the two layers disagreed about what
  "already a member" means and a case variant could become a second user for one human.


  **Five endpoints, split across two controllers by authentication posture** (mirroring
  `AuthController` / `PublicShareController`, which is what keeps the `permitAll` matchers a
  whole-controller statement rather than a per-method one):
  `POST /api/v1/invitations` (OWNER → 201 with the token embedded in `acceptUrl`, returned exactly
  once), `GET /api/v1/invitations` (OWNER → pending list, **no tokens**, `expired` derived at read
  time), `DELETE /api/v1/invitations/{id}` (OWNER → 204),
  `GET /api/v1/auth/invitations/{token}` (public preview → business name, email, role), and
  `POST /api/v1/auth/invitations/{token}/accept` (public → 201 `AuthResponse`). The two public
  routes sit under `/api/v1/auth/**` deliberately: that prefix already carries a rate-limit policy,
  so they inherit per-IP capping, whereas an unmatched path is *unlimited* by
  `RateLimitProperties.policyFor`.

  **`platform/security/RoleGuard` is the codebase's first shared authorization primitive.**
  `TenantService.requireOwner()` was the only role check that existed; `RoleGuard.requireOwner(
  message)` replaces it and `TenantService` switches over in the same change, so the extraction is
  a refactor with `TenantServiceTest` unchanged as the proof. It lives in `platform/security`, not
  `iam`, to avoid a package cycle (`iam` already depends on `tenant`), and so it compares against
  the literal `"OWNER"` rather than `Role.OWNER.name()` — `Role` lives in `iam` and `platform` must
  not depend on it. The caller-supplied `message` keeps each 403 body as specific as the
  hand-rolled one was.

  **Three things to know before touching this area.** (1) `InvitationService.revoke` contains the
  **only hand-written `tenant_id` comparison in the codebase** — a global table has no structural
  mechanism to lean on, so the filter is load-bearing rather than belt-and-braces, and a miss falls
  through to 404 (never 403, which would leak that the id is valid *somewhere*): challenge #54.
  (2) Every failed accept **and** every failed preview — unknown, revoked, already accepted,
  expired, **or belonging to a SUSPENDED tenant** — returns a **byte-identical 404**, asserted as
  bytes for every one of those states on both routes, so a `permitAll` route cannot be used as a
  token-enumeration oracle: challenge #55, which also spells out that the entity's own
  `ConflictException` is a backstop and that `@Version` plus the two unique indexes on `app_user`
  are what actually stop concurrent accepts. That contract now lives in **one** place —
  `InvitationService.requireLive(rawToken)`, which both public methods call — rather than in two
  identical copies. Adding a per-state message anywhere in it is the one change to never make. (3) `accept` is deliberately **not**
  `@Transactional`: it binds `TenantContext` *before* opening its own `TransactionTemplate`
  transaction, because a Hibernate session resolves its tenant when it opens and the `User` insert
  is `@TenantId` + RLS. Inverting those two lines does not throw — it silently writes an unbound
  row. Third arrival at the trap challenges #9 and #52 already record. (4) **An email address is
  one identity however it is spelled.** The membership check is `findByEmailIgnoreCase`, the
  invitation pre-check is `findByTenantIdAndStatusAndEmailIgnoreCase`, and `V32`'s
  `(tenant_id, lower(email))` unique index on `app_user` is the structural backstop for the case
  neither service check can see — two *different* invitations, spelled differently, racing to
  accept. Do not "simplify" any of the three back to an exact match.

  **Expiry is lazy, and that is a decision, not an oversight (design spec §7).** `expires_at` is
  checked when a token is presented and the pending list *derives* `expired` for display; there is
  no `TenantJobRunner` job, unlike the quotation slice immediately before this one. The difference
  is who observes the state: a quotation's `EXPIRED` is business-visible (list views, pipeline
  totals, audit, the shared link a customer sees) so it must be materialised; an invitation's
  expiry is observable only by whoever presents the token, and the lazy check is authoritative at
  exactly that moment. The one price laziness charges is paid on the **invite** path: an expired
  row stays `PENDING` forever, and the partial unique index is `PENDING`-scoped rather than
  expiry-aware, so re-inviting that address would be refused as a duplicate. `invite` therefore
  revokes a colliding expired row and carries on — with `saveAndFlush`, because Hibernate's action
  queue runs every insert before any update and a plain `save` would let the new row hit the index
  while the old one is still `PENDING` on disk.

  **519 tests, 0 failures, 0 errors** — 496 in the root project, 23 in `platform-primitives`, up
  from the 464-test `main` baseline (+55). The last six came from the whole-branch review's fix
  wave (four commits, `3903030` onward), described in §0. New challenges **#54–#57** (#56 is why this token is
  hashed when `share_link`'s is plaintext, and the criterion that decides it; #57 is the review
  wave's, on two tables that normalised an email address differently and the seam that opened
  between them); the annotations
  reference needed **no new rows** — every annotation this slice uses (`@Email`, `@Pattern`,
  `@Size`, `@NotBlank`, `@Enumerated`, `@GetMapping`, `@DeleteMapping`, `@PathVariable`,
  `@Component`, `@Value`, and the rest) was already documented by earlier slices.

  **What this slice does *not* do:** members management (no listing existing users, no role change,
  no disable/re-enable); any narrowing of `SALES_MANAGER`, which is now *invitable* but still
  collapsed into the unrestricted visibility tier — **do not read an invitation to that role as
  evidence the parent spec §6 three-tier rule is built, because it is not**; a real `EmailSender`
  (the stub logs; the owner delivers the link, D4); a resend endpoint (revoke + re-invite is the
  correct semantic — a resend must not leave two live links); or password reset, which is adjacent
  and token-shaped and genuinely reusable from this design, but has its own decisions. **And it
  does not ship the page the invite link points at** — see the `acceptUrl` caveat in the header
  block and in §8.

- **Previous code work: quotation auto-expiry** — **merged to `main` as `2fb2b85`** (branch deleted).
  Commits `63a2865`..`d843550` off `main` at `d7eae98`.
  Seven tasks delivering the codebase's first non-request execution path and a reusable seam for
  every scheduled job after it.

  **`platform/job/TenantJobRunner`** iterates every job-eligible tenant (`TenantStatus.TRIAL` or
  `ACTIVE`, via the new `TenantRepository.findByStatusIn`) and runs a caller-supplied body once
  per tenant, each in its own `PROPAGATION_REQUIRES_NEW` transaction with `TenantContext` bound
  **before** that transaction opens — the ordering that makes a scoped read return real rows
  instead of silently zero (challenge #52). The template is built by the runner itself rather than
  injected, closing two independent traps with one motion: a `@Transactional` method called from
  the runner's own loop would be a self-invocation (proxy bypassed), and Boot's autoconfigured
  `TransactionTemplate` is `PROPAGATION_REQUIRED`, so a caller that already holds a transaction
  would make the per-tenant work silently join it. `TenantJobRunnerTest.eachTenantsBodySeesOnlyItsOwnRows`
  is verified to fail with the ordering inverted; a sibling test pins the `REQUIRES_NEW` half the
  same way. One tenant's failure (including one optimistic-lock retry) never aborts the sweep for
  the rest. The principal bound is synthetic — `(tenantId, null, "SYSTEM")` — the same shape
  `AuthService` uses pre-authentication; `VisibilityPolicy` treats it as unrestricted and
  `AuditLog.actorUserId` is nullable so the null user id records honestly that no human did this.

  **`QuotationExpirySweep`** is the one job built on that seam so far. It reads candidates through
  `VisibleFinder.listQuotations(QuotationSpecifications.expirableAsOf(asOf))` — a correlated
  subquery over the current version's `validUntil`, never a hand-written repository query — calls
  the entity's own `Quotation.expire()` (now guarded by its own `SENT` precondition, matching
  `QuotationService.requireSent`'s message so the API contract is unchanged whether a human or the
  sweep triggers it), and publishes one `QuotationExpiredEvent` per quotation. Two
  `@EventListener`s pick it up exactly like the accept-event pair: `QuotationExpiredAuditListener`
  writes a `QUOTATION_EXPIRED` audit row (synchronous, same transaction, null actor), and
  `QuotationExpiredActivityListener` puts the expiry on the quotation's own timeline as a `SYSTEM`
  `NOTE` activity, so a salesperson sees *why* a quote stopped being live instead of finding a
  status that changed overnight with no explanation.

  **`asOf` is computed in IST, not the server's UTC clock (challenge #53).** `validUntil` is a
  `LocalDate` a user typed while thinking in IST; comparing it against a UTC-derived date gets the
  bug's direction backwards from the obvious guess — a UTC date is never later than the IST one,
  so the naive comparison matches fewer rows and *delays* expiry rather than hastening it.
  `DueWindow.todayDate(Instant)` sits beside the existing IST window arithmetic from the
  activity/follow-up slice (challenge #51) — one home for the zone, not two — and
  `QuotationExpiryJob`'s `@Scheduled(cron = "${easycrm.jobs.quotation-expiry.cron}", zone =
  "Asia/Kolkata")` pins the fire time to 00:30 IST regardless of the deploying server's own
  timezone. The cron is a property, not a constant, specifically so the test suite can disable it
  outright (`"-"`, Spring's `CRON_DISABLED` sentinel) — `QuotationExpiryJobSchedulingTest` asserts
  against the registered task list itself, not just the property value, so a disabled cron that
  somehow still registered a task would be caught.

  **464 tests, 0 failures, 0 errors** — 441 in the root project, 23 in `platform-primitives`, up
  from the 432-test `activity-follow-up` baseline (+32). New challenges #52–#53; annotations
  reference gained `@EnableScheduling` and `@Scheduled`. **What this slice does *not* do:** run
  more than one instance safely without duplicating work (no distributed lock — see the "Before
  any second app instance" note below); batch-load candidate versions (one `findById` per
  candidate — see the deferred-Minor backlog); or touch `QuotationService`, `Quotation`'s other
  transitions, or any existing REST endpoint.

- **Before that: activity log and follow-ups** — **merged to `main` as `f97c62c`**; the
  branch is deleted. Commits `212099f`..`48d35a6` off `main` at `830fd47`.
  Fourteen tasks delivering the two aggregates the design spec's §data-model names and nothing else
  (§2's out-of-scope recap: no scheduler, no notification table, no `OVERDUE` column, no attachments,
  no global feed). Both entities live under `com.easycrm.sales`, tenant-scoped and RLS-covered, in
  **four migrations, not one** — `V27__activity.sql`, `V28__rls_activity.sql`, `V29__follow_up.sql`,
  `V30__rls_follow_up.sql` — because the two tables land in different tasks and a single combined RLS
  migration would leave `RlsCoverageIntegrationTest` red between them (the design spec §5.3 originally
  described one file; corrected to the four-file split during the slice).

  **Two visibility strategies for two tables that only look symmetrical (challenge #50).**
  `follow_up` has its own `assigned_to` and joins the guarded set exactly like the four existing
  aggregates — `VisibilityPolicy.followUps()`, `VisibleFinder.findFollowUp`/`pageFollowUps`, and
  `FollowUpRepository` added to `VisibilityScopingArchTest.GUARDED_REPOSITORIES`. `activity` has no
  owner column at all — its visibility is derived, not intrinsic — so it is gated once, at its
  polymorphic subject, via the new `VisibleFinder.requireVisibleSubject(SubjectType, UUID)`, which
  switches over `CUSTOMER`/`ENQUIRY`/`QUOTATION`/`ORDER` onto the four existing `findX` methods and
  throws the house 404 on anything invisible or cross-tenant. That gate is made structural rather
  than promised: `ActivityRepository extends Repository<T, ID>` (the bare marker, not `JpaRepository`)
  with exactly three declared methods, none a by-id-alone lookup, so there is nothing unscoped left
  to inherit — `ActivityRepositoryScopingArchTest` asserts both the forbidden-supertype list (which
  also had to name `QueryByExampleExecutor`/`QuerydslPredicateExecutor`, found in review, not on the
  first pass) and that every declared method takes a subject.

  **A third assertion was added by the final review's fix wave, and it is the one to understand
  before touching this area:** the first two guard the repository's *shape*, but nothing stopped a
  future service from injecting `ActivityRepository` directly and passing a request-supplied
  `subjectId` without ever calling `requireVisibleSubject` — a `SALES_EXEC` could then have read a
  colleague's customer's whole call log with every guard green. `ActivityRepositoryScopingArchTest`
  now also pins an **allowlist of permitted caller classes, currently just `ActivityService`**,
  checking both `repo.method()` calls and `repo::method` references. Adding a name to that
  allowlist is a visibility decision, not a formality.

  **`OVERDUE` is a predicate, computed at read time, not a status column (challenge #51).** The
  parent spec's reminder scheduler is deliberately not built — see §8 for the annotation and the
  standing reason. `DueWindow` computes IST day boundaries as a pure function; `FollowUpScope`'s
  `OVERDUE`/`DUE_TODAY`/`UPCOMING` are disjoint and exhaustive over `PENDING` rows, so the dashboard
  `summary` endpoint's three counts sum to the total by construction.

  **REST surface:** `POST /api/v1/activities` (optional nested `nextFollowUp`, one transaction —
  §6.1), `GET /api/v1/activities` (by subject), `PATCH /api/v1/activities/{id}` (body carries
  `subjectType`/`subjectId`, since no by-id-alone lookup exists to gate on); `POST
  /api/v1/follow-ups`, `GET /api/v1/follow-ups/{id}`, `GET /api/v1/follow-ups` (owner + scope
  filters), `GET /api/v1/follow-ups/summary` (dashboard tile), `PATCH /api/v1/follow-ups/{id}`,
  `POST /api/v1/follow-ups/{id}/complete` (optional activity, same transaction — §6.2), `POST
  /api/v1/follow-ups/{id}/cancel`. A new `QuotationAcceptedActivityListener` (`@EventListener`, same
  synchronous/same-transaction shape as `OrderAcceptedAuditListener`) writes a `SYSTEM` activity on
  quote acceptance — the parent spec's "a new subscriber, not an edit to `QuotationService`" claim,
  now tested by someone other than its author (§6.3): `QuotationService` is untouched.

  **`AssignableUsers` extracted** (`com.easycrm.iam`) from the identical private
  `requireAssignableUser` copies duplicated in `CustomerService` and `EnquiryService` — closing
  deferred-backlog item 40 below — because `FollowUpService` needed a third copy and three was the
  line. **432 tests, 0 failures, 0 errors** — 409 in the root project, 23 in `platform-primitives`,
  up from the 352-test `record-visibility` baseline (+79). New challenges #47–#51; annotations
  reference gained `@JsonInclude` (`@EventListener`/`@Enumerated`/`@Configuration`/
  `@Bean` were already present). **What this slice does *not* do:** any scheduler, notification
  channel, or `OVERDUE` column (§3/§8 of the design spec, challenge #51); any narrowing of
  `SALES_MANAGER`, unchanged from the visibility slice; any change to the four existing aggregates'
  own tables.

- **And before that: intra-tenant record-level visibility filtering** — **merged to `main` as
  `c81f59f`.** Branch `record-visibility`, off `main` at `29b59ca`, now deleted. Nine tasks: `VisibilityPolicy` (role → `Specification`
  per aggregate, `unrestricted()` fail-open for every role but `SALES_EXEC`), `VisibleFinder` (the
  single permitted reader of the four guarded repositories), customer and enquiry reads/writes
  re-pointed at it, quotation/order visibility derived from their customer (a correlated `EXISTS`
  subquery, no new columns), the nested paths (`Contact`, PDF render, share-link mint) gated on
  their parent, `assignedTo` validated to name an `ACTIVE` user, a `VisibilityScopingArchTest`
  allowlist guard that fails the build on a new repository read bypassing the layer (prove-it-can-fail
  verified — see challenge #46), and a docs wrap-up, plus one whole-branch-review fix wave that
  strengthened three tests which passed for the wrong reason. **352 tests, 0 failures, 0 errors** —
  329 in the root project, 23 in `platform-primitives`, up from the 296-test `public-rate-limiting`
  baseline (+56). New challenges #43–#46; the annotations reference needed no new rows (this slice uses JPA
  specifications, not method security — `@PreAuthorize` appears nowhere). **What this slice does
  *not* do:** it does not implement the parent spec §6's three-tier rule — `SALES_MANAGER` is
  collapsed into the unrestricted tier because no team table or manager→report edge exists, and
  narrowing it later is a schema-plus-admin-surface slice of its own. It also does not make
  unassigned records confidential: `assigned_to IS NULL` is visible to every `SALES_EXEC`, the
  standard CRM shared-pool idiom — confidentiality begins at assignment, not at record creation.
  Backlog item #3 (§8) was left fully closed except for user invitations at the time; the
  `user-invitations` slice above has since closed that last piece too.

  **If you add a new tenant-scoped aggregate, read this.** The visibility layer does *not* extend
  itself, and its guard will not tell you so. `VisibilityScopingArchTest.GUARDED_REPOSITORIES` is a
  hardcoded list of exactly four repository names — a fifth aggregate with an `assigned_to` column
  gets no filtering at all, and every test stays green, because the rule only ever asks about those
  four. This is the opposite of how `TenantScopingArchTest` behaves: that one is keyed on the
  `@Entity` annotation, so a new entity is caught by default and must be *explicitly* allowlisted to
  escape. Extending visibility to a new aggregate means four deliberate edits: a `Specification`
  builder on `VisibilityPolicy`, a `find*`/`page*` pair on `VisibleFinder`, the service's by-id
  choke point re-pointed, and the repository added to `GUARDED_REPOSITORIES`. Miss the last one and
  the other three are the only thing standing between a new aggregate and a silent leak.

- **Earlier still: per-IP rate limiting on the public and auth routes** — **merged to `main`
  as `d7725b0`.** Branch `public-rate-limiting`, commits `bc542c2`..`18eaccd` off `main` at
  `e69d7ac`, now deleted: seven tasks, one whole-branch fix wave (`3bfb99d`), and
  the docs wrap-ups after it. Seven tasks: a `RateLimitPolicy` value type +
  `RateLimitProperties` `@ConfigurationProperties` binding (challenge #38), a `RateLimitStore` port
  with an `InMemoryRateLimitStore` implementation bounded by a Caffeine cache (challenge #39), a
  `RateLimitFilter` returning 429 + `Retry-After` on exhaustion, registering that filter ahead of
  Spring Security so failed-auth traffic is capped too, end-to-end integration tests proving the
  filter ordering, and this docs wrap-up. **296 tests, 0 failures, 0 errors** — 273 in the root
  project, 23 in `platform-primitives`, up from the 264-test `rls-force-and-guard` baseline (+23).
  Delivered: token-bucket limits (Bucket4j) keyed on `(policy name, client IP)` so one policy's
  allowance can't drain another's exhausted client's traffic against a different route (challenge
  #39); buckets keyed on `getRemoteAddr()` only — never `X-Forwarded-For`, which is client-supplied
  and would let any caller mint a fresh bucket per request (challenge #41); the bounded cache itself
  closing the mirror-image risk, an attacker-rotated-IP memory-exhaustion vector (challenge #41);
  and a `@DynamicPropertySource`-vs-`@TestPropertySource` fix so the limiter can default OFF for the
  other 62 integration test classes sharing one cached context while turning ON only for
  `RateLimitIntegrationTest` (challenge #40). New challenges #38–#41; annotations-reference gained
  `@ConfigurationProperties`, `@EnableConfigurationProperties`, `@DefaultValue`, and
  `@TestPropertySource` rows during the branch's own tasks (checked, not re-added, by this
  docs task). **What this slice does *not* do:** it caps request *rate*, not request
  *entitlement* — **PF19 remains open** (§8). **What multi-instance deployment now needs:** the
  store is in-process, so each app instance keeps its own buckets — with N instances behind a load
  balancer the effective limit for any client is N × the configured value, not the configured value.
  The design's Redis-backed `RateLimitStore` implementation is the prerequisite for running more
  than one instance; it does not exist yet.

- **Further back: RLS forced on all fourteen tenant tables, with a layer-3 guard** — **merged
  to `main` as `3c239d1`**. Branch `rls-force-and-guard`, commits `cfc8928`..`b8b2ecb` off `main`
  at `455c237`, closing **PF14 and PF15**. `V26__force_rls.sql` adds
  `FORCE ROW LEVEL SECURITY` to every tenant table (previously all fourteen were `ENABLE`d and
  none forced, so the owner role bypassed every policy silently), and
  `RlsCoverageIntegrationTest` is the guard layer 3 never had: it reads `pg_class` for every
  table carrying a `tenant_id` column and requires RLS enabled, forced and policied, allowlisting
  `refresh_token` and `share_link` in step with `TenantScopingArchTest.GLOBAL_TABLES`. A second
  test creates an un-forced probe table and asserts the guard trips on it — necessary because
  **Testcontainers' owner user is a superuser, so the failure being defended against cannot be
  reproduced behaviourally in this harness at all** (challenge #37). Forcing was behaviourally
  inert: no migration does DML and no test writes as owner. **264 tests, 0 failures, 0 errors.**
  Note this closes the gap *in the schema* — a deployment must still connect as `easycrm_app`;
  forcing removes the silent-failure mode, not the requirement.

- **Further back still: `platform-primitives` extracted into its own Gradle module** — **merged to
  `main` as `210545e`**. Branch `platform-primitives-module`, eight tasks, commits
  `4d43d75`..`6c255d4` off `main` at `ac4eaca`. Every task reviewed clean (Tasks 4 and 5 each took
  one fix round; Task 7 returned zero findings at any severity), and the whole-branch review found
  one more: the MF1 fix had made seller GSTIN *validation* symmetric with the buyer path but not
  *normalisation*, so a lowercase GSTIN was stored lowercase and printed that way on every PDF
  letterhead. Fixed in `6c255d4`. **262 tests, 0 failures, 0 errors** from a clean build — 239 in
  the root project, 23 in the new module — up from the 231-test PDF/share baseline (+31).

  **What it delivered.** `backend/platform/platform-primitives`, a plain `java-library` jar with
  **no runtime Spring dependency at all** (Spring is `compileOnly`), holding every zero-dependency
  primitive: the five exception *types* moved down out of `platform-web` (the handler stayed),
  `BigDecimalStringModule`, `Gstin`, `StateCode`, and a new `EventJson`. Specifically:
  - **The build is now two Gradle projects.** `settings.gradle.kts` includes
    `:platform:platform-primitives`; the root project takes it as `implementation(project(...))`.
    Ten files moved as pure renames, 0 lines changed. The Boot BOM is the single version source
    in both projects; the Spring Boot Gradle plugin is deliberately *not* applied to the module.
  - **`MoneyJacksonConfig` → `MoneyAutoConfiguration`**, registered through
    `AutoConfiguration.imports` rather than component scan, so the money wire format reaches a
    future `sales-svc` that scans from `com.easycrm.sales` and never sees `com.easycrm.platform`.
    `MoneyModuleWiringTest` proves the `.imports` file was actually read, not merely that the bean
    exists — the two are not the same thing (challenge #35).
  - **`EventJson`** — a second, separately-built `JsonMapper` for anything persisted or published
    (outbox JSONB, SNS, SQS), deliberately **not** the application `ObjectMapper`, so that slimming
    an API response with `spring.jackson.default-property-inclusion=non_null` cannot silently start
    dropping null fields from every event (challenge #32). Its settings are pinned explicitly even
    where they match today's Jackson 3 defaults; the LLD's Appendix B item 3 says exactly which is
    which, and the source says why they stay.
  - **Two ArchUnit rules.** R1: nothing outside the module may construct a JSON mapper. R2: the
    module may depend on nothing but `java..`, Jackson, its own packages, and `org.springframework..`
    wholesale (the last because `MoneyAutoConfiguration` legitimately needs it, and the rule covers
    every class in the module) — written as an allowlist closure, not an enumeration. The separate
    `carriesNoRuntimeSpringDependency` test is what actually confines Spring usage to the
    auto-configuration.
  - **Two live-bug fixes on the way through.** `Gstin.parse` now validates the state prefix, not
    just the checksum; and `AuthService.signup` now validates the *seller's* GSTIN and state code,
    which it never did — an invalid seller state code silently decides CGST+SGST vs IGST on every
    quotation that tenant ever issues (challenge #34, LLD finding MF1).
  - **New challenges #32–#36**; new annotations-reference rows for `@AutoConfiguration` and
    `@ConditionalOnClass`.

  **Two things not to overstate.** `EventJsonDivergenceTest` found **no** divergence between the
  application mapper and `EventJson` today — it is a tripwire for the future, most likely to fire
  the day someone sets `spring.jackson.default-property-inclusion=non_null`, and it has caught
  nothing. And this is **one module of six**: see §8 for why that does not make the module queue the
  next thing to do.
- **Prior branch:** `main` at `8b6644b` (quotation PDF/share; feature branch and its worktree
  deleted). All 10 tasks were reviewed clean, as was the whole-branch review.
- **Design-only work between them:** 9 commits (`15e9818`…`5d6bfd7`) adding the AWS re-platform
  design set. **Zero code, zero migrations, zero test impact — the 231-test baseline was untouched
  at that point.** See §2 item 25.
- **`docs/architecture/` is now fully tracked.** The four docs left uncommitted since 2026-07-29 —
  the as-built and target architectures (§2 items 23–24) plus two interview briefs — were reviewed
  and committed on 2026-08-27 as `a76e563` and `e9bbd81`. Docs only; the then-current 231-test
  baseline was untouched. **One caveat travels with them:**
  `2026-08-05-interview-challenges-and-aws-kafka.md` walks the system as it would look with Kafka on
  Amazon MSK, and the later AWS design went the other way — D3 chose a transactional outbox into SNS
  FIFO and SQS FIFO, D4 rejected Kinesis and DMS CDC outright at this event rate. Keep it as
  interview prep; do not read it as a plan.
- **The two long-uncommitted tracked files are gone from the outstanding list.**
  `docs/architecture/2026-08-19-aws-target-architecture-design.md` and
  `docs/superpowers/engineering-challenges.md` (challenge #31, head-vs-tail sampling) — flagged as
  uncommitted by three successive handoffs — were reviewed and committed as `ac4eaca`, immediately
  before the `platform-primitives` branch was cut from it. Nothing is uncommitted now.
- **Merged & done on `main`:** the design docs (including the order-lifecycle slice's
  `specs/2026-07-28-order-lifecycle-design.md` `8a6c9dd` and `plans/2026-07-28-order-lifecycle.md`
  `8c0703f`, both committed directly) + **P0 tenant-isolation foundation** + **P0-auth core** +
  **P1a master data** (merge commit `2f9a2f4`) + **P1b quotation engine** (merge commit `43e9642`)
  + **order + accept** (merge commit `ea11d3f`) + **enquiry** (merge commit `a68035d`) +
  **enquiry→quotation conversion** (merge commit `06e6014`) + **sales hardening** (merge commit
  `abc2bd3`) + **order lifecycle** (merge commit `8247579`).
- **Last feature slice merged to `main`: quotation PDF + `wa.me` share** (merge commit `8b6644b`). **231 tests was the baseline at that time; it is 262 now** — see the `platform-primitives` bullet above. 10 tasks
  (PDF engine spike + determinism, Indian digit-grouping money
  formatting, seller-profile columns on `Tenant`, a Thymeleaf quotation template, the
  authenticated PDF render endpoint, the global `share_link` table, the idempotent share
  endpoint + `wa.me` deep link, the public no-auth render endpoint, the `QuotationService.list`
  filter fix, and this docs wrap-up), every task reviewed clean (one adversarial, execution-based
  review on the public endpoint). Delivered: server-side quotation rendering (Thymeleaf →
  openhtmltopdf, byte-identical across renders of the same frozen version — challenge #28);
  `Tenant.address/phone/email` for the letterhead; `GET /api/v1/quotations/{id}/pdf?version=<n>`
  (JWT-gated, defaults to the latest SENT version); a global, RLS-exempt `share_link` table
  mapping a plaintext token to `(tenant_id, quotation_version_id)` — deliberately unhashed, unlike
  `refresh_token`, so `POST /api/v1/quotations/{id}/share` is genuinely idempotent (see the design
  spec §4 for the full blast-radius reasoning); `GET /public/q/{token}` serving the PDF with **no**
  JWT at all, `TenantContext.runAs` installing the resolved tenant before the rendering
  transaction opens (challenges #29–#30); and the `QuotationService.list` two-filter fix
  (challenge #24's pattern, closing backlog item #1 below). **231 tests passing** from a clean
  build, up from the 187 order-lifecycle baseline (+44).
- **Prior: order lifecycle** — merged to `main` as `8247579` (5 code/test tasks + a docs wrap-up + one final-review fix wave, each review clean). Delivered: the four-state guarded machine `CONFIRMED → DISPATCHED → CLOSED` (terminal) with `cancel()` legal from either active state, all three transitions guarded entity-side (`Order.dispatch()`/`close()`/`cancel(reason)`, each naming its own precondition rather than coupling to enum ordinal order); a required, non-blank `cancelReason` (`VARCHAR(500)`, migration `V23__order_cancel_reason.sql`); `POST /api/v1/orders/{id}/dispatch|close|cancel` (422 on an illegal transition, 400 on a blank cancel reason, 404 cross-tenant); a generic `OrderStatusChangedEvent` + synchronous same-transaction `OrderStatusChangedAuditListener` writing `ORDER_DISPATCHED`/`ORDER_CLOSED`/`ORDER_CANCELLED` audit rows; `OrderSpecifications.filter(status, customerId)` AND-composing both list filters (`OrderRepository` now extends `JpaSpecificationExecutor<Order>`), closing the challenge #24 dropped-filter bug for orders; and a 422 on `QuotationService.accept`'s idempotent branch when the existing order is `CANCELLED`, instead of silently handing back a dead order with 200 (challenge #27). The final whole-branch review added audit-detail-payload assertions (proving `from` carries the *pre*-transition status and `cancelReason` appears only on the cancel row) and cross-tenant coverage on the new `Specification` query path. **187 tests passing** from a clean build, up from the 166 sales-hardening baseline (+21).
- **Prior latest merged: sales hardening** — 2 code/test tasks + docs closing the two Minors deferred from the conversion review. **166 tests passing** from a clean build (`cd backend && ./gradlew clean test`), up from the 162 conversion baseline (+4). Delivered: (1) a global `@ExceptionHandler(OptimisticLockingFailureException.class)` → 409 so a lost-update race (concurrent `accept`/convert-at-create) returns 409 not 500 — a sibling of the challenge #15 `DataIntegrityViolation` backstop on the disjoint concurrency subtree; (2) `UNIQUE(tenant_id, enquiry_id)` on `quotation` (migration `V22` + entity `@Table`; NULLs distinct so enquiry-less quotes coexist) making one-quote-per-enquiry structural, a guard-bypassed/raced second insert now routing through the challenge #15 handler → 409. Both proven deterministically (no threads): a handler unit test, a single-threaded stale-write repo test, and repo constraint tests. Challenge #26 logged; challenge #25's 500-gap note updated to "closed".
- **Enquiry→quotation conversion** (prior): 2 code/test tasks + docs, merged as `06e6014` (162 tests). `QuotationService.create()` flips the enquiry to `CONVERTED` and stamps `quotation.enquiry_id` when raised with an `enquiryId`, atomically. Challenge #25.
- **Enquiry slice** (prior): 7 tasks + a post-review re-enquiry test + PATCH-contract docs, merged as `a68035d` (154 tests).
- **Enquiry scope:** the wedge's *head* (lead capture) — 7 tasks (phone normalizer, the `Enquiry` aggregate + guarded 5-stage lifecycle, migration/RLS/partial-index dedupe, create endpoint, get + filtered list via a JPA `Specification`, edit/advance/lose transitions, and this challenges/annotations/handoff wrap-up).
- **What enquiry delivered:** the `Enquiry` aggregate (table `enquiry` — not reserved; tenant-scoped, RLS-covered) carrying nullable `customerId` (walk-ins), raw contact fields (`contactName`, `contactPhone` + derived `normalizedPhone`, `contactEmail`), `source` (own `EnquirySource` enum — same six values as `crm.CustomerSource`, kept separate so `sales` stays decoupled), `requirementText`, `assignedTo`, `stage` (`EnquiryStage`), optional `expectedValue` (money, JSON-string wire), and `lostReason`. **5-stage guarded lifecycle** `NEW → CONTACTED → QUALIFIED → CONVERTED / LOST`: guards live in the entity (mirroring `Quotation`'s transition methods), `advanceTo` allows only a later *active* stage (skips ok, no backward/terminal-target), `lose` requires a reason, `markConverted()` was **reserved for the later conversion slice** at the time — it is now reached by `QuotationService.create()` when a quote is raised with an `enquiryId` (merged `06e6014`), so a phone is freed for re-enquiry via either `CONVERTED` or `LOST`. **Dedupe = one active enquiry per phone**, enforced structurally by a Postgres **partial unique index** `UNIQUE(tenant_id, normalized_phone) WHERE stage NOT IN ('CONVERTED','LOST')` plus an app-level active-only pre-check (→409) with the challenge #15 `DataIntegrityViolation`→409 backstop (challenge #23). **List** uses a JPA `Specification` that AND-composes any subset of `?stage=&assignedTo=&source=` — deliberately avoiding the "drops a filter when two are supplied" bug (challenge #24) that the order list had at the time, and which was fixed for orders in `8247579` and for quotations in `8b6644b` — all three list endpoints now AND-compose their filters. REST: `POST` (201), `GET /{id}` (cross-tenant 404), `GET` (filtered, offset `PageResponse`, cross-tenant empty), `PATCH /{id}` (active-only edit, re-dedupes on phone change), `POST /{id}/advance`, `POST /{id}/lose`. Lives under `com.easycrm.sales` (+ `.web`, `.web.dto`). Challenges #23–#24; no new annotations (`JpaSpecificationExecutor`/`Specification` noted in the annotations reference as concepts, not annotations).
- **P1a scope:** product/customer/contact/price-list CRUD — 13 planned tasks plus three execution-time additions (Task 7b global 409 handler, Task 13b test-hardening, and a final-review fix — see §4).
- **P1b scope:** the quotation engine on top of P1a's master data — 12 planned tasks (money-as-JSON-string wire format, GST calc, gapless document numbering, price resolution, the quotation/version/item aggregate + RLS, create/get/list/versions, edit with the frozen-version guard, send, revise, reject/expire, and its challenges/annotations/handoff wrap-up).
- **Order/accept scope:** the wedge's final stage on top of P1b — 7 tasks (the `Order` aggregate on physical table `sales_order`, gapless `ORD/FY/NNNN` numbering, the `accept` transition, `QuotationAcceptedEvent` + audit subscriber, order read endpoints, and this challenges/annotations/handoff wrap-up).
- **What order/accept delivered:** the `Order` aggregate — tenant-scoped, RLS-covered, physical table **`sales_order`** because `order` is a reserved SQL word (class stays `Order`, challenge #20) — carrying `orderNo`, `quotationId`/`quotationVersionId`, `customerId`, optional `poReference`/`poDate`, `subTotal`/`totalTax`/`grandTotal`, and `status` (**`CONFIRMED`** only *at the time of that slice* — `DISPATCHED`/`CLOSED`/`CANCELLED` and their guarded transitions arrived later in the order-lifecycle slice `8247579`; see the order-lifecycle bullet above). Gapless per-tenant/per-FY order numbering (`ORD/FY/NNNN`) reuses `DocumentNumberService`/`document_counter` under a distinct `"ORDER"` counter key (challenge #16's pattern, second doc type). `QuotationService.accept(id, AcceptRequest)`: validates the quotation is `SENT`, creates the `Order` inline (so the HTTP response carries it immediately), flips the quotation to `ACCEPTED`, then publishes `QuotationAcceptedEvent` for decoupled subscribers — a deliberate deviation from the parent spec's "the order handler subscribes" wording, keeping the event as a side-effect seam rather than a return channel while preserving same-transaction atomicity (challenge #22). `OrderAcceptedAuditListener` (`@EventListener`, synchronous, same-transaction) writes the `QUOTATION_ACCEPTED` audit row. Idempotency is **natural/state-based**, not a client idempotency key: a re-accept of an already-`ACCEPTED` quotation returns the existing order (`OrderRepository.findByQuotationId`), backed by `UNIQUE(tenant_id, quotation_id)` on `sales_order` plus the quotation's inherited `@Version` optimistic lock for the raced case (challenge #21). Read endpoints: `GET /api/v1/orders/{id}` and `GET /api/v1/orders` (status/customerId filters, offset-paginated `PageResponse`, cross-tenant → 404). Lives under `com.easycrm.sales` (+ `.web`, `.web.dto`).
- **What P1b delivered:** the `Quotation`/`QuotationVersion`/`QuotationItem` aggregate (tenant-scoped, RLS-covered); a price resolver (customer + product → effective rate off `PriceList`/`PriceListItem`, falling back to `Product.baseRate`); server-side GST calc (per-line round-then-sum, intra-state CGST+SGST vs inter-state IGST, keyed off `Tenant.state_code` vs the customer's GSTIN-derived state); gapless per-tenant/per-FY document numbering (`document_counter` + `SELECT … FOR UPDATE`, see challenge #16); the global `BigDecimal`-as-JSON-string wire format for money (challenge #17, also retroactively fixing P1a's money fields); and the full lifecycle — create → edit (header patch / full item replace, guarded to DRAFT only) → send (freezes the version, assigns the quote number) → revise (spawns a new DRAFT version copying the frozen items verbatim) → reject/expire (challenge #18). Lives under `com.easycrm.sales` (+ `platform.money.BigDecimalStringModule`).
- **What P1a delivered:** tenant-scoped REST CRUD for `Product`, `Customer` (+ GSTIN checksum validation and GST-state-code derivation via the new `platform.gst.Gstin`/`StateCode` value types), `Contact` (nested under customer), `PriceList`, and `PriceListItem` (override-rate/discount-percent mutually-exclusive pricing). New shared plumbing: `platform.error.ValidationException` → 422 with field errors, `platform.web.PageResponse` (offset-paginated list envelope). Cross-tenant reads return 404 (not 403/200), matching the P0 pattern. Lives under `com.easycrm.catalog` and `com.easycrm.crm`.
- **What P0-auth delivered:** self-serve auth on top of the isolation foundation — atomic signup (tenant + first OWNER in one transaction), bcrypt login, rotating opaque JWT refresh tokens (SHA-256 at rest), tenant-scoped audit log, public auth endpoints with generic 401s. Lives under `com.easycrm.iam` (+ `platform.persistence.UuidV7`, `platform.error.{Conflict,Unauthorized}Exception`). Working `signup → login → GET /api/v1/auth/me → refresh` loop, all verified against Postgres + RLS.
- **What P0 (isolation) delivered:** the 4-layer multi-tenant isolation, all provably enforced by tests:
  1. **JWT resolution** (`platform/security` — `JwtService`, `JwtAuthenticationFilter`, `SecurityConfig`)
  2. **Hibernate `@TenantId`** (`platform/tenancy` — `TenantIdentifierResolver`, `HibernateTenancyConfig`; `TenantScopedEntity`)
  3. **Postgres RLS** (`TenantAwareTransactionManager` sets `app.current_tenant` per transaction; policies use `NULLIF(current_setting('app.current_tenant', true), '')::uuid`)
  4. **ArchUnit** (`arch/TenantScopingArchTest` — every `@Entity` must extend `TenantScopedEntity` unless allowlisted in `GLOBAL_TABLES`)
  - Plus: `BaseEntity` (UUIDv7 ids, auditing, `@Version`), `TenantContext` (ThreadLocal + `runAs`), `TenantAwareTaskDecorator` (async propagation), `Tenant` (global entity), `DemoRecord` (isolation test subject — throwaway, replaced by real entities later), 404-not-403 error mapping, `DemoSeeder` + `backend/DEMO.md`.

## 4. Recently completed, and what was deliberately left out

**This section is history plus standing gotchas — the *next* task is chosen in §8, not here.**
Read it before extending any of the areas it describes, so you don't rebuild something that
exists or assume something that doesn't.

**The quotation PDF/share slice is DONE and merged to `main` (`8b6644b`).** Every test count in this subsection is the count *at that time*; the current baseline is 262. 10
tasks, every review clean: (1) a PDF-engine spike proving openhtmltopdf 1.0.10 renders on JDK 25
and can be made byte-deterministic (challenge #28 — a PDFBox writer branch silently ignores
`setDocumentId()` when the trailer already carries an inherited `/ID`); (2) Indian digit-grouping
money formatting (`java.text.DecimalFormat` cannot do it — single `groupingSize`, hand-rolled
instead); (3) `Tenant.address/phone/email` for the letterhead; (4) the Thymeleaf quotation
template (CGST+SGST xor IGST, base-14 fonts, no ₹ glyph); (5) `GET
/api/v1/quotations/{id}/pdf?version=<n>`; (6) the global `share_link` table; (7) `POST
/api/v1/quotations/{id}/share` + the `wa.me` deep link (RFC 3986 space encoding, a deterministic
primary-contact tie-break); (8) `GET /public/q/{token}` — no auth, tenant resolved from the
global table and installed via `TenantContext.runAs` before the rendering transaction opens
(challenges #29–#30); (9) the `QuotationService.list` two-filter fix (closes backlog item #1
below); (10) this docs wrap-up. **231 tests passing** from a clean build, up from the 187
order-lifecycle baseline (+44). See the §3 bullet above for the fuller feature list and the
design spec for the tenant-resolution seam and the plaintext-token reasoning.

**The order-lifecycle slice is DONE and merged to `main` (`8247579`).** 5 code/test tasks plus this docs wrap-up landed and reviewed clean: (1) `OrderStatus` widened to `CONFIRMED, DISPATCHED, CLOSED, CANCELLED` with `isTerminal()`/`isActive()` and entity-side guarded `dispatch()`/`close()`/`cancel(reason)` transitions, plus a required non-blank `cancelReason` (migration `V23`); (2) `POST /api/v1/orders/{id}/dispatch|close|cancel`, with `OrderResponse` gaining `cancelReason` as its 7th component; (3) a generic `OrderStatusChangedEvent` + `OrderStatusChangedAuditListener` writing the three new audit action rows; (4) `OrderSpecifications.filter` closing the challenge #24 dropped-filter bug for orders; (5) a 422 on `QuotationService.accept`'s idempotent branch when the existing order is `CANCELLED` (challenge #27). The whole-branch review then added audit-detail and cross-tenant assertions. Clean-build total was **187 tests** at the time, up from the 166 sales-hardening baseline.

**Prior:** the sales-hardening slice is DONE and merged to `main` (`abc2bd3`). 3 tasks landed and reviewed (optimistic-lock→409 handler + tests; `UNIQUE(tenant_id, enquiry_id)` migration/entity + tests; docs), each task-review clean, and the whole-branch review returned READY TO MERGE with no Critical/Important findings. It closes the two Minors that the enquiry→quotation conversion whole-branch review consciously deferred (both now struck from the deferred list below).

**Deferred out of enquiry scope** (explicit — do not assume any of this exists):
- ~~**Enquiry → quotation conversion wiring**~~ — **DONE, merged** (`06e6014`). `QuotationService.create()` flips the enquiry to `CONVERTED` and stamps `quotation.enquiry_id` when a quote is raised with an `enquiryId`. Note: still convert-*at-create* only; no standalone `/enquiries/{id}/convert` endpoint, and one enquiry maps to at most one quotation (a second create against a converted enquiry → 422).
- **`activity` / `follow_up` entities** — the spec's Activity section (CALL/WHATSAPP/EMAIL/VISIT/NOTE logs + first-class follow-up reminders) is still unbuilt.
- ~~**Order status transitions beyond `CONFIRMED`**~~ — **DONE, merged** (`8247579`). `OrderStatus` now has `CONFIRMED, DISPATCHED, CLOSED, CANCELLED` with entity-side guarded `dispatch()`/`close()`/`cancel(reason)` transitions and a required `cancelReason` — see the order-lifecycle summary above. Challenge #27.
- ~~**PDF generation** and the **`wa.me` WhatsApp share link**~~ — **DONE, merged** (`8b6644b`). Server-side quotation PDF rendering, a public tokenized share link, and the `wa.me` deep link all exist — see the §3/§4 summaries above. **Order PDF is still out of scope** (design spec §8 — the quotation is the document customers actually ask for at this stage), as are link expiry/revoke and rate limiting on the public route (see the backlog below).
- **Scheduled auto-expiry** — only a manual `expire` action exists on quotations; nothing runs on a schedule to expire quotations past `validUntil` automatically.
- **Record-level visibility filtering** — still open from P1a (§4 P1a notes); quotations, orders, and now enquiries inherit the same gap (every user in a tenant reads every enquiry in it).
- **Cursor pagination** — quotation, order, and enquiry list endpoints use the same offset-based `Pageable`/`PageResponse` as P1a; large tenants will need cursor pagination later.
- ~~**Optimistic-lock → 409 (codebase-wide)**~~ — **DONE, merged** (`abc2bd3`). A global `@ExceptionHandler(OptimisticLockingFailureException.class)` → 409 now maps a lost-update race (concurrent `accept` challenge #21, or convert-at-create challenge #25) to 409 instead of 500 — a sibling of the challenge #15 `DataIntegrityViolation` backstop on the disjoint transient/concurrency subtree. Challenge #26.
- ~~**Structural backstop for one-quote-per-enquiry**~~ — **DONE, merged** (`abc2bd3`). `UNIQUE(tenant_id, enquiry_id)` on `quotation` (migration `V22` + entity `@Table`; Postgres NULLs distinct, so enquiry-less quotes coexist) makes the one-quote-per-enquiry invariant structural. A guard-bypassed/raced second insert now routes through the challenge #15 handler → 409. Challenge #26.

**Testing note for anyone extending quotation flows:** quotation reads a real `Tenant.state_code` (to compute the intra-/inter-state GST split against the customer's GSTIN-derived state), so a phantom tenant — `TestTokens.owner(UUID.randomUUID())`, which mints a JWT for a tenant id that has no backing row — is **not enough** here, even though it's sufficient for RLS-only tables elsewhere in the codebase. Quotation tests use the new `TestTokens.provisionOwner(stateCode)`, which inserts a real `Tenant` row (with the given GST state code) before minting the token. Reach for `provisionOwner` whenever a test path reads anything off the `Tenant` row itself, not just whenever it needs *a* tenant id.

### What P1a changed vs its plan (read before extending master data)

Two things happened mid-execution that weren't in `plans/2026-07-25-p1a-master-data.md` verbatim:

- **Task 7b (added, not originally planned): a global `@ExceptionHandler(DataIntegrityViolationException.class)` in `ApiExceptionHandler`.** The plan's per-entity services already do an app-level "does this already exist?" pre-check before insert (e.g. duplicate GSTIN, duplicate SKU) and throw `ConflictException` → 409. That pre-check is a check-then-act race, not a guarantee: two concurrent creates can both pass it, and the update path has no pre-check at all. Added a global handler that catches the DB unique-constraint violation itself and still returns 409 (generic message) instead of a raw 500 — the backstop that makes the uniqueness guarantee hold under concurrency and on update. Logged as challenge #15.
- **Task 9 deviation: `ContactRequest.isPrimary` boxed from primitive `boolean` to `Boolean`.** Jackson 3 (Boot 4) fails a request body that omits a primitive field with a 400 before the controller ever runs — there's no way for a primitive to represent "absent." Boxed to `Boolean`, defaulted explicitly in `ContactService` (`Boolean.TRUE.equals(...)`). Logged as challenge #12.

Also logged from P1a's core design (not deviations, just the two hardest correctness problems it solved): the GSTIN Luhn-mod-36 checksum (challenge #13) and the override-rate/discount-percent XOR + `BigDecimal.compareTo`-not-`equals` (challenge #14).

**Deferred to P1b** (explicit, from the P1a plan's Global Constraints — do not assume these exist yet):
- **Money-as-JSON-string wire format.** P1a is the first code to put a `BigDecimal` on the wire (`Product.gstRate/baseRate`, `PriceListItem.overrideRate/discountPct`, etc.) and it currently serializes as a plain JSON **number**, not the string format challenge #2 specifies (`WRITE_BIGDECIMAL_AS_PLAIN` + string). P1b must add the global Jackson-3/Boot-4 serializer customizer before the quotation wire contract and frontend money handling ship — otherwise JS's `double` re-introduces the rounding error challenge #2 exists to prevent.
- **Price resolution** (customer + product → effective rate, reading `PriceList`/`PriceListItem`). Entities exist; no resolver yet.
- **Record-level visibility filtering** on `customer.assigned_to`. Column exists; nothing currently filters reads by it — every user in a tenant can read every customer in that tenant.
- **Cursor pagination.** P1a's list endpoints use offset-based `Pageable`/`PageResponse`; large tables will need cursor pagination later.

### What P0-auth changed vs its plan (read before extending auth)

Two design points in `plans/2026-07-25-p0-auth-core.md` did not survive contact with the stack and were changed (all logged in `engineering-challenges.md` #8–#11):

- **No `TenantBinder`.** The plan's Task 7 rebound an *open* transaction to a new tenant mid-flight. That can't work: Hibernate resolves a session's tenant **once, at session-open**, and never re-reads it — so `@TenantId` kept writing the wrong tenant and the owner insert failed RLS `WITH CHECK`. **Instead:** `Tenant` carries an **application-assigned UUIDv7 id** (`platform.persistence.UuidV7`, and `Tenant` implements `Persistable` so `save()` inserts), and signup sets the tenant context **before** the `TransactionTemplate` transaction opens. `AuthService.signup/login/refresh` all follow this "set context, then open the tx" shape rather than being `@Transactional` themselves. (#9)
- **RLS-scoped derived finders are `@Transactional(readOnly = true)`** (`UserRepository.findByEmail`, `AuditLogRepository.countByAction`). Spring Data doesn't wrap derived queries in a transaction by default, so without this the tenant GUC isn't set and RLS returns **zero rows** (fails safe, easy to miss). (#8)
- **`LOGIN_FAILED` audit uses `AuditService.recordIndependently` (`REQUIRES_NEW`)** so it survives the rollback caused by the 401 throw. Success-path audits stay on default propagation. (#11)
- **Jackson 3 gotcha:** Boot 4 ships Jackson under `tools.jackson`, not `com.fasterxml.jackson`. Tests extract JSON with jayway `JsonPath` to sidestep the mapper API. (#10)

**Design decisions locked** (don't relitigate): bcrypt (not Argon2), HS256 (not RS256), opaque refresh tokens hashed at rest, `refresh_token` is a *global* allowlisted table while `app_user`/`audit_log` are tenant-scoped, generic 401 (no enumeration).

## 5. Environment (macOS, already set up)

- **JDK 25** installed (`~/Library/Java/JavaVirtualMachines/openjdk-25.0.1`). Shell default is JDK 21, but the **Gradle toolchain uses 25** — do NOT change the shell default.
- **Gradle 9.6.1** (via Homebrew) — but always use the wrapper: `cd backend && ./gradlew ...`.
- **`backend/gradle.properties`** carries five `--add-exports jdk.compiler/...=ALL-UNNAMED` flags
  under `org.gradle.jvmargs` (plus a restated `-Xmx2g`, since setting `jvmargs` at all replaces
  Gradle's default heap). These exist for `palantirJavaFormat()` (Spotless), which reaches into
  `jdk.compiler` internals. **The JVM that needs opening up is the Gradle *daemon*, not the
  toolchain** — the daemon runs on the shell default (JDK 21 here), and the toolchain JDK 25 used
  to compile/test is a separate JVM entirely; it's easy to reach for a JDK-25 fix when the actual
  target is the daemon. In practice this repo's Gradle 9.6.1 daemon already supplies
  `--add-exports` for `jdk.compiler.{api,util}` by default, so palantir ran cleanly with zero
  configuration on both this machine and CI's — the five flags are kept anyway as insurance (3 of
  the 5 cover palantir codepaths this tree doesn't currently exercise, and a different Gradle/JDK
  combination could supply a different default set); do not read their presence as evidence they
  were load-bearing here. See engineering-challenges for the fuller version if one gets written up.
- **A fresh clone must run one command for local `git blame` to honour the whole-tree reformat:**
  `git config blame.ignoreRevsFile .git-blame-ignore-revs`. GitHub's web blame reads
  `.git-blame-ignore-revs` automatically; local `git` does not until this is set per-clone (it's a
  repo-level file but a per-checkout config, so it does not survive a fresh `git clone`). Verified:
  with it unset, `git blame` on `TenantJobRunner.java` attributes 8 reformatted-only lines to the
  reformat commit `2616049`; with it set, those lines correctly fall back to the real original
  commit `a855056b`.
- **Docker** must be running (Testcontainers needs it). Start Docker Desktop: `open -a Docker`, then wait for `docker info` to succeed. Note: a user Postgres container (`langfuse-postgres-1`) runs on `localhost:5432` — leave it alone; Testcontainers uses its own random-port container.
- **Run tests:** `cd backend && ./gradlew clean check` (the baseline command as of the build-hygiene slice — see §0; `./gradlew test` still runs tests only, but no longer proves what "the build is green" means on this repo). Integration tests spin up one shared Postgres container (singleton pattern) — 544 tests on `main`, run in well under a minute once the image is cached (it was ~4s before the PDF slice; rendering real PDFs is the difference).
- **The build is two Gradle projects** since 2026-08-27: `backend` (root) and
  `backend/platform/platform-primitives`. Unqualified `./gradlew clean test` spans both and is what
  every "expect N tests" claim in this document means; Gradle prints no combined total, so count it
  with the `find`/`awk` snippet in §0 item 1. **A `--tests` filter must be project-qualified**
  (`./gradlew :test --tests '…'` or `./gradlew :platform:platform-primitives:test --tests '…'`) —
  unqualified, Gradle applies the filter to both projects and fails on the one with no match.
- **Sandbox note:** in this harness, network + Docker operations may need the Bash tool's sandbox disabled (`dangerouslyDisableSandbox: true`). SDKMAN's reachability check is blocked by the sandbox even when network works.

## 6. Stack quirks already discovered (see challenges log for detail)

This is **Spring Boot 4.1 + Java 25 + Hibernate 7** — all recent. Watch for:
- **Spring Boot 4 split auto-config into per-integration modules.** `flyway-core` alone doesn't bring `FlywayAutoConfiguration` → use `spring-boot-starter-flyway`. `@AutoConfigureMockMvc` moved to `org.springframework.boot.webmvc.test.autoconfigure` (module `spring-boot-webmvc-test`). `HibernatePropertiesCustomizer` moved to `org.springframework.boot.hibernate.autoconfigure`. Jackson's auto-configuration is `org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration` in artifact `spring-boot-jackson` (transitive via `spring-boot-starter-web`); `spring-boot-autoconfigure` remains the right `compileOnly` coordinate for `@AutoConfiguration`/`@ConditionalOnClass`. **If an import "does not exist," search the resolved jars for the class's new package** rather than assuming the plan is wrong.
- **ArchUnit 1.4.1** (not 1.3.0) — 1.3.0 silently skips Java 25 bytecode. Two further traps, both hit in one afternoon and both producing a rule that passes while checking nothing: (a) **`noClasses().should(customCondition)` inverts every event the condition emits**, so a hand-rolled condition must emit `SimpleConditionEvent.satisfied(...)` for the case it forbids — challenge #33; (b) **`importPackages("com.easycrm")` now spans two build outputs**, since the `platform-primitives` jar shares the prefix, so an `isNotEmpty()` vacuity guard no longer proves the root project's own bytecode was read — challenge #36. **Never add an ArchUnit rule without deliberately introducing a violation and watching it fail.**
- **Jackson 3 moved the date/timestamp serialization switches.** `SerializationFeature.WRITE_DATES_AS_TIMESTAMPS` and `WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS` **do not exist**; they are now on `tools.jackson.databind.cfg.DateTimeFeature` (a `DatatypeFeature`), reached via `disable(DatatypeFeature...)`. `JsonWriteFeature.WRITE_NUMBERS_AS_STRINGS` is **off** by default (verified directly against `jackson-core-3.1.4`), whatever secondary sources claim.
- **Testcontainers BOM pinned to 1.21.3** (Boot 4 BOM doesn't manage those versions).
- **`BindResult<T>.orElseThrow()` lost its zero-arg overload.** Only `orElseThrow(Supplier<? extends X>)` exists in Boot 4.1 — call `.get()` instead when you just want the bound value or a `NoSuchElementException` if binding failed (`RateLimitDefaultsTest`).
- **RLS + custom GUC:** a referenced custom GUC resets to `''` not NULL, so policies use `NULLIF(current_setting('app.current_tenant', true), '')::uuid`. An RLS `USING` clause also acts as `WITH CHECK` for inserts.
- **Two DB roles:** Flyway runs as the **owner** (Testcontainers superuser); the app connects as **`easycrm_app`** (non-owner, no BYPASSRLS) — this is what makes RLS real. `IntegrationTest` wires both datasources.
- **`ddl-auto: validate`** is on — migration column types must match entity mappings exactly (e.g. `VARCHAR` not `CHAR` for a `String`).
- **`spring.jpa.open-in-view: false` is load-bearing, not a preference.** `GET /public/q/{token}` resolves its tenant from the `share_link` row and installs it with `TenantContext.runAs` **before** the rendering transaction opens. With OSIV enabled, the `EntityManager` opens in an interceptor *before* the controller runs, so Hibernate would pin the wrong tenant at session-open (challenge #9's rule) and the public endpoint would silently read under no tenant. **Nothing in the build would catch it** — no test fails, no exception is thrown. Do not flip this flag. See challenge #29.
- **A precompiled Gradle script plugin (anything under `buildSrc/src/main/kotlin/*.gradle.kts`)
  cannot see the version catalog.** Type-safe `libs.*` accessors do not exist there — `buildSrc`
  is its own Gradle build with its own catalog visibility, not an extension of the including
  build's. `easycrm.quality-conventions.gradle.kts` needs `palantirJavaFormat` and `findsecbugs`
  versions at configuration time and cannot reach `gradle/libs.versions.toml` for either, so both
  are literals in that file with a comment naming the catalog key they must be kept equal to (and
  the catalog's own comment says the reverse) — a second source of truth that must be updated by
  hand on every version bump. Challenge #61.

## 7. Working agreements (also in CLAUDE.md — enforced)

- **Commits:** author as `divyam <divyam.0444@gmail.com>` (repo git config is already set). Plain `git commit`. **Never** add a `Co-Authored-By: Claude` trailer or mention Claude/AI in commit messages.
- **Log engineering challenges:** when a task surfaces a non-obvious problem, append to `engineering-challenges.md` (Problem → why hard → Solution → Lesson) in the same change.
- **Keep the annotations reference current:** add a row when a new annotation appears.
- **TDD:** failing test → run-to-confirm-fail → minimal code → run-to-pass → commit. One task per commit.
- **Money is never `double`** (BigDecimal / NUMERIC / JSON string). P1a got the Java/Postgres side right (`NUMERIC`, `compareTo` not `equals`) but still shipped `BigDecimal` fields on the wire as plain JSON numbers; **P1b closed that gap globally** with `platform.money.BigDecimalStringModule` (challenge #17) — every `BigDecimal`, including P1a's already-shipped fields, now serializes as a JSON string. **Since 2026-08-27** that class lives in the `platform-primitives` Gradle module (same package, `com.easycrm.platform.money`) and is registered by `MoneyAutoConfiguration` through `AutoConfiguration.imports`, not component scan. **The event wire is a separate mapper on purpose:** use `EventJson.mapper()` for anything persisted or published, and inject Boot's `ObjectMapper` for HTTP — an ArchUnit rule fails the build if you construct your own anywhere else (challenge #32).
- **Tenant isolation is structural:** never hand-write `WHERE tenant_id`; rely on `@TenantId` + RLS; new entities extend `TenantScopedEntity` or get allowlisted (ArchUnit enforces). **There is exactly one deliberate exception in the codebase** — `InvitationService.revoke` filters `invitation` by tenant in code, because a global pre-auth table has no structural mechanism to lean on. Challenge #54 states the rule that governs it: name the exception explicitly, give it the rigor the missing structural check would have had, and make its failure a 404 rather than a 403. Do not treat it as licence to hand-write a filter on a tenant-scoped table.

## 8. The next chunk — pick one with the user

The wedge (**enquiry → quotation → order**) is functionally complete end-to-end and hardened,
including the order aggregate's own lifecycle; quotations can be rendered as a PDF and shared over
WhatsApp; and a tenant can now have more than one user. All four candidates below are scoped in the
design spec (`specs/2026-07-22-easycrm-design.md`), and **three of the four are now done** — read
the ranking paragraphs after the list before proposing anything, because what is left is thinner
than a four-item list looks. Present them, take the user's choice, and only then start
the workflow from §0 step 4.

1. ~~**`activity` / `follow_up` entities**~~ — **DONE**, merged as `f97c62c` (§0, §3). CALL/WHATSAPP/EMAIL/VISIT/NOTE logs against any of the four visibility-scoped
   aggregates, log-and-schedule in one transaction, complete/cancel/reschedule, and a `SYSTEM`
   activity on quote acceptance via the accept event seam — exactly as this item described. **The
   parent spec's `follow_up` data-model clause "first-class, with its own reminder scheduler" is
   deliberately not implemented** — see the design spec §3 for the standing reason (no channel to
   push into: no WhatsApp Business API, email has no delivery-tracking/dedupe design, no frontend
   for in-app), and challenge #51 for why the eventual fix is additive, not a redesign. Record this
   as a decision, not an oversight, if it's ever asked why no scheduler exists.
2. ~~**Scheduled auto-expiry**~~ — **DONE**, merged as `2fb2b85` (§0, §3).
   A nightly job at 00:30 IST expires every lapsed `SENT` quotation, with an audit row and a
   timeline activity — exactly as this item described. It also landed the codebase's first
   non-request execution path and a reusable `TenantJobRunner` seam: every future scheduled job
   should build on that runner rather than growing its own `TenantContext.runAs`-before-transaction
   loop (challenge #52 is why getting that ordering wrong is silent rather than loud). The IST
   day-boundary trap this raised for comparing a `LocalDate` column against the server's UTC clock
   is challenge #53.
3. ~~**P0-auth follow-up**~~ — **DONE. This item is now closed entirely; nothing of it remains.**
   It started as three things: rate limiting, record-level visibility, and user invitations, and all
   three have landed. Rate limiting landed in the `public-rate-limiting` slice (`d7725b0`; §3):
   `/public/q/{token}` and the auth routes are capped per-IP with a 429 + `Retry-After` contract —
   that closed the *abuse-of-rate* half of PF19 and **not** the *entitlement-metering* half, which
   stays open below. Record-level visibility landed in the `record-visibility` slice (merged as
   `c81f59f`; §3): every read and write on `Customer`, `Enquiry`, `Quotation`, and `Order` is now
   filtered by `assigned_to` through a single `VisibleFinder`, guarded by
   `VisibilityScopingArchTest`. **User invitations landed in the `user-invitations` slice** (merged as
   `f265cfe` — §0, §3): an owner invites an email + role, the invitee
   accepts pre-auth and becomes an `ACTIVE` user of that tenant, plus a pending list and revoke.
   **Because this was the last open piece, the whole P0-auth follow-up is now done — the ranking
   below is written on that basis.**

   Three qualifications to carry forward, none of which reopen the item:
   - **The invite link's `acceptUrl` points at a frontend route that does not exist yet** —
     `{easycrm.public-base-url}/invite/{token}` (design spec D10). The durable form was reserved
     deliberately, because these links are pasted into WhatsApp and must still resolve on the day
     the frontend lands rather than stranding or forcing a permanent redirect out of an API
     namespace. The consequence is honest and qualifies the "works on day one" claim: the **token**
     works and both public endpoints consume it, but the **page is not browsable**. Wiring
     `/invite/{token}` is the first thing to do when the frontend starts.
   - **`SALES_MANAGER` is now *invitable* but is still collapsed into the unrestricted visibility
     tier**, exactly as the record-visibility slice left it. The parent spec §6's three-tier rule
     remains **unbuilt**, and inviting a `SALES_MANAGER` must not be read as evidence otherwise —
     narrowing that tier is still a schema-plus-admin-surface slice of its own.
   - **Members management landed in its own slice** — see §3's top entry and the "Suggested
     ranking" list below for what it does and deliberately does not do. This qualification, as
     written for the invitations slice, is now historical: at the time invite + accept + revoke +
     pending list really was the whole surface.
4. **Cursor pagination** — quotation/order/enquiry lists are all offset-based `Pageable`/
   `PageResponse`; large tenants will need cursor pagination. Cross-cutting, lower urgency.

**A fifth candidate now exists, and it is deliberately not in the numbered list above:
continuing the platform-module track.** `platform-primitives` (LLD #1) is built; the next module by
**dependency order** is `platform-web` (LLD #2,
`docs/architecture/2026-08-26-platform-web-lld.md`), which now depends on `platform-primitives` for
the exception types that sank into it. Dependency order is not priority order, and nothing about
having built one module makes the queue urgent — read the next paragraph before proposing it.

**What still outranks the module queue.** The platform-LLD thread's own handoff
(`../architecture/2026-08-27-platform-llds-handoff.md` §3) records three findings that describe
code running **today**, not the future split. **Two of the three are now closed** — the
`rls-force-and-guard` slice took PF14 and PF15 (§3); PF19 remains open:

- ~~**PF14** — RLS `ENABLE`d on all fourteen tables and `FORCE`d on none.~~ **DONE** —
  `V26__force_rls.sql` forces the fourteen that existed then. **There are sixteen now**
  (`activity` and `follow_up` arrived later): a new tenant table does NOT get retrofitted into
  `V26`, it ships `ENABLE` + `FORCE` + its own `tenant_isolation` policy in its own migration,
  as `V28__rls_activity.sql` and `V30__rls_follow_up.sql` do. `RlsCoverageIntegrationTest` keys
  on the `tenant_id` column, so forgetting this fails the build rather than leaking quietly. Read the caveat in §3: this removes the *silent*
  failure mode, but a deployment still has to connect as `easycrm_app` for layers 3 to do
  anything; forcing means a wrong role now fails loudly instead of leaking quietly.
- ~~**PF15** — ArchUnit guards layer 2; nothing guards layer 3.~~ **DONE** —
  `RlsCoverageIntegrationTest` is the layer-3 twin, keyed on the `tenant_id` **column** rather
  than the `@TenantId` annotation (which is what makes it a real second layer and not a re-read of
  the first). Its allowlist must be extended in step with `TenantScopingArchTest.GLOBAL_TABLES`.
  One residual gap, noted rather than fixed: the guard keys on a column *named* `tenant_id`, so a
  tenant table naming it something else would slip past. Nothing in the repo does that today.
- **PF19** — the entitlement metric set does not respect the create/read boundary, and
  `/public/q/{token}` renders a PDF with no JWT, so there is structurally nowhere to put an
  entitlement check on the app's most expensive uncapped operation. **PF19 REMAINS OPEN** even
  after `public-rate-limiting`: that slice caps how *often* the route can be hit, which closes the
  abuse-of-rate reading of this finding, but it adds no entitlement metering at all — there is
  still nowhere in the request path that knows or charges *whose* quota a render came out of. Do
  not infer PF19 is finished from the rate-limiting slice landing; it addresses a different half of
  the same finding.

**PF19 was also a second, independent argument for #3's rate-limiting half** — arriving from
billing/COGS rather than from security — and that half is now done (§3). The entitlement-metering
half PF19 is actually about is still unstarted.

**Suggested ranking — read this before proposing anything. Updated 2026-09-02, after both
`openapi-contract` and members management.**

The numbered list above is down to one open item (#4), the correctness backlog is empty, and nine
slices in a row have hardened or extended the backend (RLS forcing, rate limiting, record-level
visibility, activity/follow-up, scheduled auto-expiry, user invitations, build hygiene, the
OpenAPI contract, then members management). **Two of the candidates that used to head this list
are now done.** Wave 3 is done — springdoc, the committed and guarded `docs/api/openapi.yaml`
snapshot, and the oasdiff changelog in CI, merged as `bead2f8`. Members management is done —
list, change role, disable, enable, merged after it. Strike both from the option set.
**The strongest remaining candidates are still not on the numbered list**, so the real option
set is the four below. Present them; do not default into #4 because it is the last number
standing.

- **Wave 1.5, supply chain — the cheapest real win, blocked on nothing.** `gitleaks`, Dependabot or
  Renovate, OWASP Dependency-Check, and `squawk` for unsafe Postgres DDL. Small, well-scoped, and
  higher security value than anything else on this list, on a repo that is now **public** and ships
  JWT auth, bcrypt and GST data. It is the natural next backend slice for the same reason
  build hygiene was: it finishes a programme already half-built rather than opening a new front. The
  one cost to plan for: Dependency-Check needs an NVD cache that makes CI meaningfully slower.
- **The frontend — the biggest product step, the only one a backend slice cannot finish, and the
  one this slice was for.** Nothing has ever been built. **It now has a contract to build against**:
  `docs/api/openapi.yaml` describes every endpoint, the money-as-JSON-string convention, the shared
  error envelope and the bearer-JWT scheme, and the drift guard means it stays true — which is
  exactly the thing that was missing when the previous ranking put OpenAPI ahead of it. Doing the
  frontend now means the contract shapes the client rather than the client defining the contract by
  accident. **Wiring `/invite/{token}` is still the first task when it starts** — the invitations
  slice ships a link that gets pasted into WhatsApp and has no page behind it. Unscoped, so it
  begins at brainstorming, and it is large enough to want decomposing into sub-projects before a
  spec; that size, not its value, is why Wave 1.5 sits above it.
- ~~**Members management**~~ — **DONE**, merged (§3). List, change-role, disable, enable, all
  owner-only. It was also the first real test of the drift guard on a slice that adds endpoints:
  cut before the guard existed, it had to regenerate and commit `docs/api/openapi.yaml` in the
  merge itself. **What it deliberately does not do**, and why: no delete — disable only, so
  `audit_log.actor_user_id`, `invitation.invited_by`, and the `assigned_to` references on
  `customer`/`enquiry`/`follow_up` stay intact rather than orphaned; no bulk reassignment — the
  gate refuses a disable and reports what blocks it, and moving the work happens through the
  existing per-record endpoints, with a reassign-in-one-call endpoint left as a reasonable
  follow-up once the frontend knows what it wants; no self-service profile editing (a member
  changing their own email/phone/password) — adjacent and token-shaped, but it shares a surface
  with password reset, which has its own decisions and is unbuilt; and `SALES_MANAGER` is still
  collapsed into the unrestricted visibility tier exactly as `record-visibility` left it — being
  assignable by an owner is not evidence the parent spec §6 three-tier rule is built, because it
  is not.
- **Wave 2, observability — real value, but sequence it deliberately.** Structured JSON logging, an
  MDC correlation filter (`requestId`/`tenantId`/`userId`), GSTIN/phone/email redaction, Micrometer
  with `/actuator/prometheus`, and tracing over OTLP. Two things make it more than housekeeping: the
  two known production hazards (the in-process rate-limit store, the unlocked 00:30 expiry sweep)
  are **invisible** today, and the "assert runtime behaviour" TODO earlier in this section
  (`datasource-proxy`/`hypersistence-utils`/`quickperf`/`flexy-pool`/`digma`) largely lives or dies
  with it — `digma` in particular is OTel-based and cannot be evaluated before tracing exists.
- **#4 cursor pagination — leads by elimination, not merit.** Cross-cutting and blocked on nothing,
  but no tenant is remotely large enough for offset paging to hurt. "Before the first large tenant"
  below is a better description of when it starts to matter. One thing did change: offset paging is
  now *published* — `page`/`size`/`sort` are in the committed contract on eight endpoints — so
  switching to cursors later is a breaking change oasdiff will report, not a quiet refactor.

**Still blocked or still weak, unchanged:** PF19's entitlement-metering half needs the billing
thread's *design* decisions, not effort — the public route has no JWT, so there is nowhere to hang a
per-tenant check. And `platform-web` (LLD #2) is next by dependency order only; nothing has made the
module queue more urgent, and `platform-primitives` landing did not change that.

**Before any second app instance:** the rate limiter's store is in-process (§3) — running N
instances behind a load balancer multiplies every configured limit by N, silently. Build the
design's Redis-backed `RateLimitStore` implementation before multi-instance deployment, not after;
today it does not exist. **The nightly quotation-expiry sweep has the same shape of problem, one
notch sharper.** `TenantJobRunner`/`QuotationExpiryJob` take no distributed lock, so with N app
instances all N run the cron at 00:30 IST and all N sweep every tenant. It cannot double-*write* —
the first writer's `Quotation.expire()` flips the row, `@Version` optimistic locking makes every
later instance's write attempt on the same row fail, and `TenantJobRunner`'s bounded one-retry
finds nothing left to expire the second time — but it does duplicate the *work*: N redundant reads
of the candidate set, N audit rows attempted (N-1 of them losing the race), N event-publish
attempts. This sits alongside the rate limiter's in-process store as the second thing that needs a
real fix (a leader-election lock, or a `SELECT ... FOR UPDATE SKIP LOCKED` claim step) before a
second instance runs, though it fails safe rather than silently, which the rate limiter's version
of this problem does not.

**Before the first large tenant:** the visibility predicate `assigned_to = :me OR assigned_to IS NULL`
has **no index behind it** on either `customer` or `enquiry` — it is a sequential scan within the
tenant partition, and today *every* row matches it because nothing has ever written the column. This
is fine at current volumes and is the one performance consequence the visibility design does not
otherwise call out. It sits alongside item 15 below (no index supports a status-only order-list
filter) as the second thing to look at when a tenant's tables grow.

**The activity/follow-up slice deliberately did not repeat that mistake**, which is the pattern to
copy: `follow_up` shipped `(tenant_id, assigned_to, status, due_at)` in its creating migration
because that is the dashboard's every-login query, and `activity` shipped
`(tenant_id, subject_type, subject_id, occurred_at DESC)` to cover the timeline read. Adding the
index when the table is created costs one line; retrofitting it costs a migration on a live table.

### Build-hygiene follow-ons — Wave 1.5, Wave 2, Wave 3 (raised 2026-09-01)

**Wave 1 (this slice, `build-hygiene`) is done: CI, Spotless, SpotBugs + find-sec-bugs, JaCoCo,
version catalog — see §3.** The design spec's own triage
(`specs/2026-09-01-build-hygiene-design.md` §3) laid out the rest of a three-wave-plus-a-split
programme; recording the waves and the deferrals here so choosing the next one doesn't require
re-deriving the reasoning from that spec each time.

- **Wave 1.5 — supply chain, a short follow-up, not yet started.** `gitleaks` (secret scanning),
  Dependabot or Renovate (dependency update PRs), OWASP Dependency-Check (published-CVE
  scanning), `squawk` (unsafe Postgres DDL linting for Flyway migrations), and **`actionlint`**
  (workflow linting with shellcheck over `run:` blocks — see the CI-tooling evaluation later in
  this section for what it does and does not catch). Deliberately split
  out of Wave 1 rather than folded in: it's a third distinct tool family, and Dependency-Check
  needs an NVD cache that makes CI setup meaningfully slower — bundling it with Spotless/SpotBugs/
  JaCoCo would have blurred one clean slice into two. Higher real security value than anything in
  the deferred list below it.
- **Wave 2 — observability, not yet started.** Structured JSON logging, an MDC correlation filter
  carrying `requestId`/`tenantId`/`userId`, a redaction rule for GSTIN/phone/email, Micrometer
  with `/actuator/prometheus`, and Micrometer Tracing over OTLP. Not academic: the two known
  production hazards this file already flags — the in-process rate-limit store that multiplies
  every limit by N per instance, and the unlocked 00:30 expiry sweep that duplicates work across
  instances (both above, "Before any second app instance") — are **currently invisible**; nothing
  would show either happening. **The runtime-assertion TODO immediately below (statement-count
  assertions, connection-pool tuning, Digma) is Wave 2-adjacent material** — it's about *test-time*
  assertions on query/pool/call behaviour rather than *production* observability, but both need
  the same underlying instrumentation, so plan them together rather than standing up tracing
  twice.
- **Wave 3 — OpenAPI. DONE** (merged as `bead2f8`; see §0 and §3). springdoc
  3.1.0, the committed and drift-guarded snapshot at `docs/api/openapi.yaml`, and an `oasdiff`
  changelog in CI — reporting rather than blocking, for the reasons in §3. It was the highest-value
  item on the user's original list: there are 16 controllers and the frontend has never been
  started, so this spec **is** the contract the frontend gets built against — and it is the honest
  substitute for consumer-driven contract testing in a repo with no consumer yet (see Pact below).
  The one deferral it leaves behind is the `continue-on-error: true` on the oasdiff step, whose
  flip trigger is the frontend existing and consuming the spec.
- **Deferred, each with a trigger, not a vague "later":** SonarQube (deferred on merit, not
  sequencing — SpotBugs + Spotless + JaCoCo + ArchUnit already cover most of what it would flag,
  and it earns its keep with a team and a PR queue rather than solo; the JaCoCo XML report stays
  on so it's a drop-in later); Pact (when the React frontend exists — no consumer today); Spring
  Cloud Contract (when the five-service AWS split is actually built); AsyncAPI (when
  `platform-outbox`, LLD #3, unbuilt, puts events on SNS/SQS — in-process `ApplicationEvent`s
  aren't an async API); Chaos Monkey for Spring Boot (after the service split, and after retries/
  timeouts/circuit breakers exist to validate — nothing to discover in a monolith with no
  downstream calls); AWS FIS (after there is AWS); a load baseline, k6 or Gatling (before the
  first large tenant, alongside the two missing indexes already flagged above); Trivy image
  scanning (blocked on there being a `Dockerfile` — there is none).

**The 32 baselined SpotBugs findings (§3) are a backlog item, not fixed by this slice — by
design (spec D9).** Split 29 root / 3 `platform-primitives`; by category `EI_EXPOSE_REP2` ×17,
`EI_EXPOSE_REP` ×8, `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` ×3, `CT_CONSTRUCTOR_THROW` ×3,
`MS_EXPOSE_REP` ×1. 26 of the 32 are the defensive-copy family and are largely noise on JPA
entities and records that never mutate their own fields after construction — the better home for
those is probably a permanent category exclusion in `config/spotbugs/exclude.xml` (currently
empty, ready for this) rather than baseline debt that looks like it's waiting to be "paid off."
The other 6 — 3× `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE`, 3× `CT_CONSTRUCTOR_THROW` — are a
different kind of finding and deserve someone actually reading each one, not a blanket exclusion.

### CI-pipeline testing tooling — evaluated 2026-09-02, mostly declined

Raised after the oasdiff base-SHA bug: the step diffed against `HEAD~1` (the previous *commit*)
instead of the branch's previous *state*, so a multi-commit push reported nothing for all but its
last commit. Nothing caught it — the workflow parsed, the step exited 0, and the summary said "no
changes", which is also what it says when there genuinely are none. The question this section
answers is which of the standard CI-testing tools would have helped, and the answer is mostly
"none of them, and here is the one that earns a slot anyway."

**Every claim below was measured against this repo's own `ci.yml`, not read off a description.**

- **`actionlint` — ADOPT, fold into Wave 1.5.** The only clear win. Run it on the real file and it
  reports shellcheck findings *inside* `run:` blocks (it embeds shellcheck), unknown runner labels,
  and malformed action refs. On our current file it already flags one live style issue
  (`SC2129`, the repeated `>> "$GITHUB_STEP_SUMMARY"` redirects). Given this repo's CI now carries
  ~45 lines of non-trivial shell — `set -uo pipefail`, three guard branches, fenced summary output
  — shellcheck coverage of that shell is real value for one Docker invocation.
  **Be clear about what it does NOT do:** it would not have caught our bug, and it does not catch a
  typo in the very expression we fixed. Injecting `github.event.beforre` and
  `github.event.pull_request.base.shaa` produced *no* actionlint findings — it does not
  deep-validate webhook payload properties. It catches shape, not meaning.
- **`Bats` — DECLINE, with a reason rather than a shrug.** It is the obvious tool for testing the
  shell inside `run:` blocks, and we already do that from JUnit: `OasdiffWorkflowTest` extracts the
  step's body out of the real `ci.yml` and executes it against throwaway git repos with `docker`
  stubbed. That approach wins on the property this repo cares most about — it runs inside
  `./gradlew clean check`, the single command that means "the build is green". Bats would be a
  second test runtime, a second command to remember, and a second thing CI has to install, to test
  the same 45 lines. Revisit only if pipeline shell grows past what is comfortable in a JUnit
  harness.
- **`act` (nektos/act) — DEFER, with a trigger.** It runs workflows locally in Docker and is the
  only tool here that could exercise a `pull_request` event without opening a PR. Two things make
  it a poor fit *today*: our job runs Testcontainers under Gradle, so act means Docker-in-Docker;
  and act emulates GitHub rather than being it — checkout's merge-ref behaviour and
  `github.event.before` are exactly the semantics we would be trusting it to reproduce, which is
  circular for our purposes. **Trigger to revisit:** a workflow change that cannot be verified by
  pushing, or a second workflow with matrix/conditional logic worth iterating on locally.
- **`yamllint` — DECLINE.** actionlint subsumes the workflow-specific half, Spotless already owns
  formatting for `src/**/*.yml`, and `OasdiffWorkflowTest` parses `ci.yml` with snakeyaml on every
  build, so a syntax break fails the suite already. Pure overlap.
- **Mocked env vars paired with `act` — ALREADY DONE, without act.** The valuable half is stubbing
  the external tool, and the test does that: a fake `docker` on `PATH` records the base document it
  was handed. That is what makes the test deterministic and offline.
- **GitLab Runner — not applicable.** This project is on GitHub Actions.

**What actually closed the gap** was not a tool. Two things did: the JUnit harness above, and
opening one throwaway pull request to fire the event that had never fired. Note the division of
labour, because it is the reusable lesson — actionlint checks that a workflow is *well-formed*;
only a test that runs the workflow's own logic checks that it is *correct*; and only a real run
proves the platform populates what you assumed. A tool that would have caught the `HEAD~1` bug
does not exist, because the bug was a wrong answer to a question the file never asks out loud.


### TODO — assert runtime behaviour, not just outcomes (raised 2026-09-01)

Every test in this repo asserts what came *out* of a call. Nothing asserts **how** it got there — how
many SQL statements a transaction issued, how the connection pool behaved, or how many outbound HTTP
calls a method made. Three tools close that, and they belong with the Wave 2 observability slice
rather than in build hygiene:

- **`datasource-proxy` + `hypersistence-utils` — assert the SQL statement count per transaction or
  method.** `SQLStatementCountValidator` (hypersistence-utils) wraps a call and asserts exactly how
  many selects/inserts/updates/deletes it issued; `datasource-proxy` is the JDBC interception layer
  underneath that makes the counting possible. **This is the highest-value item of the three here,
  because this codebase already has a known N+1 and no way to detect the next one.** The
  quotation-expiry sweep does one `findById` per candidate version (deferred-minor #47–49's area),
  the visibility predicate `assigned_to = :me OR assigned_to IS NULL` runs unindexed, and
  `VisibleFinder` puts a correlated subquery behind four aggregates — all places where an innocuous
  refactor turns one query into N with no failing test. A statement-count assertion is the only
  cheap, structural guard against that, and it fails loudly rather than getting slower quietly.
- **`flexy-pool` — size and monitor the connection pool.** It adds adaptive sizing plus metrics
  (acquisition time, timeouts, overflow) on top of HikariCP, which is what turns pool tuning into a
  measurement rather than a guess. Note the interaction with what already exists: `open-in-view` is
  **false** and load-bearing (challenge #29), and `TenantJobRunner` opens a `REQUIRES_NEW`
  transaction *per tenant* in a loop, so the nightly sweep's pool demand scales with tenant count in
  a way nothing currently observes.
- **Evaluate `quickperf` — and decide it *against* the bullet above, not alongside it.** QuickPerf
  is an annotation layer over the same idea: `@ExpectSelect(1)`, `@ExpectMaxQueryExecutionTime`,
  `@ExpectJdbcBatches`, `@ExpectNoJoin`, and — most relevant here —
  `@DisableSameSelectTypesWithDifferentParams`, which detects an N+1 *by shape* rather than by a
  hand-counted number, so it keeps working when the row count changes. It also measures JVM heap
  allocation (`@ExpectMaxHeapAllocation`), which `SQLStatementCountValidator` does not do at all.
  **These two are alternatives for one job, not two items to adopt.** The trade-off worth deciding
  deliberately: QuickPerf is declarative and reads well on a test method, but it is another test
  runtime with its own JUnit 5 extension and Spring integration to keep working across Boot
  upgrades — and this project is on Boot 4.1 / Java 25 / Hibernate 7, where §6 shows most
  third-party tooling has needed a version hunt. `SQLStatementCountValidator` is a plain assertion
  in a plain test with almost no integration surface. **Evaluate QuickPerf's Boot 4 / JUnit 5
  compatibility first** — if it lags, the plain validator is the lower-risk way to get the same
  N+1 guard. Either way, run it in measure-mode first to discover today's real query counts before
  asserting on them; do not guess the expected numbers.
- **Count client-side REST calls by asserting on `RestTemplate`.** Worth recording, but be honest
  about the trigger: **there are zero outbound HTTP calls in `src/` today** — no `RestTemplate`,
  `WebClient`, `RestClient` or `HttpClient` anywhere, which is also why Pact and Spring Cloud
  Contract are deferred. This becomes real the moment the first one lands: a real `EmailSender`
  (today's is a logging stub), a payment or GST-validation integration, or the AWS service split.
  `MockRestServiceServer` covers the assertion side; a counting `ClientHttpRequestInterceptor`
  covers it in integration tests.
- **Evaluate `digma` — complementary to the bullets above, not an alternative to them.** Digma is
  an OpenTelemetry-based continuous-feedback tool (IDE plugin + a self-hosted backend) that reads
  *actual traces* and surfaces N+1 query patterns, slow queries, bottlenecks and scaling issues in
  the editor. The distinction from `hypersistence-utils`/`quickperf` is the one that matters:
  those are **test-time assertions** that fail a build deterministically once you already know
  what to assert; Digma is **runtime observation** that tells you what you did not know to look
  for. Discovery versus regression-prevention — you would plausibly want one of each, not one
  instead of the other. Two sequencing facts decide when this is worth doing: (1) it is OTel-based,
  so it depends on Wave 2 (observability, above) landing tracing first — evaluating it before that
  means standing up instrumentation twice; (2) it needs traffic to observe, and this app has no
  frontend and no production traffic, so the realistic trace source today is **the test suite
  itself**, which is a first-class Digma workflow and is unusually well-suited here: 530 tests,
  many of them Testcontainers integration tests hitting real Postgres, would exercise the exact
  `VisibleFinder` correlated-subquery and expiry-sweep paths the bullets above are worried about.
  Cost to weigh: it runs its own backend in Docker plus an IDE plugin, materially heavier setup
  than adding an assertion to a test.

### Smaller deferred-Minor backlog

Open and non-blocking. This list is the complete record of every `minor (deferred)` line the SDD
ledgers of **eight** slices accumulated — items 1–22 from the quotation PDF/share slice (ten tasks),
items 23–24 from the `platform-primitives` slice (eight tasks), items 25–32 from
`public-rate-limiting` (seven tasks), items 33–41 from `record-visibility` (nine tasks plus a
whole-branch fix wave), items 42–46 from `activity-follow-up` (fourteen tasks), items 47–49 from
`quotation-auto-expiry` (seven tasks plus a whole-branch fix wave), items 50–51 from
`user-invitations` (eight tasks plus a whole-branch fix wave), and items 52–58 from
`members-management` (nine tasks plus a whole-branch fix wave) — each cross-checked
line-by-line against its ledger before that workspace was deleted at merge. Every one of those
workspaces is now gone, so this list is the durable copy. So it really is **self-contained**:
don't go looking for an SDD ledger to corroborate it, there won't be one. Roughly highest-value
first *within* each slice's block; 23–24 are not lower-value than 22, they are just newer.

**The `user-invitations` slice's deferred minors were triaged by the whole-branch review, and
most are now closed.** Its SDD ledger (since deleted with the workspace at merge)
carried ten `minor (deferred)` lines; the review's fix wave (`3903030` onward) closed the two that
were flagged to it — the duplicated filter chain, now the single `InvitationService.requireLive`, and
the barrier-less accept race, now a `CyclicBarrier(2)` — along with the in-memory scan of a
tenant's `PENDING` rows (replaced by a derived query) and the unused imports.

**Exactly two are deliberately still open, and both are deferrals rather than oversights.** They
are now items **50** and **51** below — that workspace was deleted at merge, so the list is the
only copy.

1. ~~**`QuotationService.list` has the dropped-filter bug**~~ — **DONE.** Closed by the quotation
   PDF/share slice's Task 9: `QuotationSpecifications.filter` mirrors `OrderSpecifications`,
   `QuotationRepository` now extends `JpaSpecificationExecutor<Quotation>`, and a two-filter
   regression test (`?status=` + `?customerId=` together) guards it.
2. **Non-Latin script silently renders as `#` in the quotation PDF — the most user-visible
   limitation this slice ships with.** Base-14 Helvetica (the template's font) covers WinAnsi
   (Latin-1) only. Confirmed empirically through the real `PdfEngine`: Devanagari text came back
   as `Shri Ram #### Traders`. A `businessName`, `billingAddress` or product name in Devanagari,
   Gujarati, Tamil or any other Indian script is entirely ordinary for this product's actual
   customers, and the result is a corrupted document sent over WhatsApp with no exception, no log
   line, and no test failure — the substitution is silent. See design spec §2 for the full
   reasoning. Fix: embed a Unicode-capable font (Noto Sans or DejaVu Sans, subset) — the
   jar-weight/font-licensing trade-off §2 already declined once for the `₹` glyph alone, now with
   its real cost visible.
3. ~~**No rate limiting on `/public/q/{token}`**~~ — **DONE.** Closed by the `public-rate-limiting`
   slice (§3, merged as `d7725b0`): a per-IP Bucket4j token bucket in front of `/public/q/{token}` and the
   auth routes, 429 + `Retry-After` on exhaustion. **This closes only the rate half of PF19, not the
   entitlement-metering half** — PF19 stays open above. It also does not yet support more than one
   app instance: the store is in-process, so N instances multiply the effective limit by N until the
   Redis-backed `RateLimitStore` is built.
4. **No expiry or revoke on a share link, and no way to invalidate one by any means today.** A
   link minted once renders forever. Resharing the same version does **not** replace anything —
   `ShareLinkService.share()` returns the version's existing stored token (that is the point of
   the plaintext-idempotency design in the design spec §4), and there is no delete path anywhere
   in the codebase. The `share_link` row is exactly where expiry/revoke columns belong when this
   is prioritized — see the design spec §4/§8 for the reasoning already on record.
5. ~~**`Totals.totalTax` is carried in the quotation PDF's view model but rendered nowhere in
   `quotation.xhtml`.**~~ **DONE.** A "Total tax" row now sits between the tax rows and Grand
   total, matching the design spec §2 template contract (`subTotal`, `totalTax`, `grandTotal`).
6. **Cancelling an enquiry-linked order has no path back to that enquiry** (challenge #27). The
   422 message says "raise a new quotation", which only fully works for enquiry-less quotations:
   `Enquiry.requireActive()` rejects a second `markConverted()` and `UNIQUE(tenant_id,
   enquiry_id)` blocks a second quotation, so the replacement must go in with `enquiryId: null`,
   silently severing lead traceability. Re-opening the enquiry on cancel, or relaxing
   one-quote-per-enquiry, is an **open design decision, not a bug** — decide it deliberately.
7. **PDF rendering runs inside `@Transactional(readOnly = true)`** (`QuotationPdfService`),
   holding a database connection open for the duration of CPU-bound render work. Fine at today's
   volumes; if rendering gets heavier, consider fetching inside the transaction and rendering
   outside it.
8. **PATCH endpoints house-wide are full-header-replace**, not partial merges — an omitted
   nullable field is cleared. The PUT-vs-PATCH-vs-partial decision is deliberately deferred until
   the frontend lands and can state what it needs. This semantic is documented on
   `Tenant.updateProfile` (the PDF/share slice's new tenant-profile PATCH) but, house-wide, is
   asserted by no test — a regression test would be cheap if this is ever revisited.
9. **`OrderSpecifications`, `EnquirySpecifications`, `QuotationSpecifications`, `CustomerSpecifications`
   (added by the record-visibility slice, §3), and now `FollowUpSpecifications` (added by
   `activity-follow-up`, §3) all use string-keyed `root.get(...)`** rather than a JPA static
   metamodel, so a field rename fails at runtime rather than compile time. All five have immediate
   test coverage. If fixed, fix them together — doing one alone just makes the others inconsistent.
   Fixing this now means touching five classes, not four.
10. **Only `Seller`'s optional fields have a null-render test in `QuotationPdfRendererTest`.**
   `Buyer.gstin`, `Buyer.address`, `validUntil`, payment/delivery terms and notes are all
   `th:if`-guarded in `quotation.xhtml`, but no test renders any of them absent — the same
   category of gap as the `OrderTest` item below, just on the newer surface.
11. **`OrderTest`'s three rejected-transition tests assert only the exception type**, not that
    `status`/`cancelReason` are left unmutated; only the blank-reason test snapshots state. Safe
    today (every guard runs before any assignment), but a future guard reorder would go uncaught.
12. **Four near-identical order-building test fixtures** now exist across the sales test classes
    (`OrderReadTest`, `OrderTransitionTest`, `OrderStatusAuditTest`, plus
    `QuotationAcceptAuditTest`'s inlined variant). Extracting a shared sales test-fixture helper
    is a candidate cleanup; it was consciously declined to keep slices independent.
13. **`Enquiry.advanceTo` couples to enum ordinal order** (guarded, but a reorder changes
    behaviour). `Order`'s transitions deliberately avoid this by naming each precondition — that
    is the pattern to copy if `Enquiry` is ever revisited.
14. **`expectedValue` / `contactEmail` lack `@PositiveOrZero` / `@Email`** on the enquiry DTOs.
15. **No index supports a status-only order-list filter.** `sales_order` has
    `(tenant_id, customer_id)` and `(tenant_id, id)`; `?status=` alone has none. Irrelevant at
    current volumes — worth revisiting before the first large tenant.
16. **`PdfEngine` catches broad `Exception` in both its render and metadata-stamp paths.** Matches
    the brief's reference code as given; narrowing it to something that distinguishes malformed
    input from an environment failure belongs with whichever caller first needs to tell the two
    apart. No caller does yet.
17. **Two dead null-checks guard a value that's never actually null.**
    `QuotationPdfService.requireCurrentVersion`'s null-`currentVersionId` branch (`create()`
    always sets it) and `ShareLinkService.share`'s equivalent `q.getCurrentVersionId() == null`
    check are both harmless defensive code inherited from the plan. Deliberately left as-is:
    fixing one without the other would just make them inconsistent, so revisit together if ever.
18. **Quotation-totals `sum()` references `java.util.function.Function` / `java.util.Objects`
    fully-qualified inline** rather than importing them — a style inconsistency inherited from
    the plan, not introduced by this slice.
19. **A malformed `/public/q/{token}` containing a literal `/` returns 401, not 404.** Ruled
    acceptable as-is: the path never resolves as this route at all, and the 401 reveals nothing
    beyond "`/public/**` is auth-gated" — it isn't a usable oracle for probing real tokens.
20. **`PdfEngineTest.sameInputRendersToIdenticalBytes` renders XHTML with no `<title>`,** so
    byte-determinism is never exercised with a title present. The re-reviewer decompiled the
    renderer and confirmed title is a pure function of input, so this is a coverage gap, not a
    suspected risk — lowest priority on this list.
21. **`easycrm.public-base-url` has a bare `http://localhost:8080` dev default with no validation
    that a real deployment overrode it to an `https://` origin.** A deploy that forgets
    `PUBLIC_BASE_URL` mints and WhatsApps a `localhost` link to a customer — silent, because the
    only place the bad URL surfaces is the customer's chat, not the server's logs. The default
    itself is intentional (no production profile exists yet; local dev and the demo flow need it
    to work out of the box, same shape as the `JWT_SECRET` dev default) and is commented in
    `application.yml` accordingly. What's still open: bind it through a validated
    `@ConfigurationProperties` class that requires an `https` scheme outside a dev profile, so a
    misconfigured deployment fails loudly at startup instead of shipping a broken link silently.
22. **The inter-state PDF assertion cannot distinguish the IGST row from the Total tax row.** In
    the endpoint test's fixture both happen to be `Rs. 180.00`, so a regression that dropped the
    inter-state Total tax row specifically would not turn the test red. The row does render
    (verified by inspection); the intra-state branch has no such ambiguity. Asserting on the
    literal `Total tax` label, or choosing a fixture where the two amounts differ, closes it.

**From the `platform-primitives` slice (2026-08-27).** Both were raised by the whole-branch review,
judged real, and deliberately deferred rather than fixed — each is a decision better made once,
when module 2 lands and there are three places to keep in step instead of two.

23. **`PrimitivesModuleArchTest.carriesNoRuntimeSpringDependency` exempts by *name suffix*.** The
    rule is `noClasses().that().haveSimpleNameNotEndingWith("AutoConfiguration").should()
    .dependOnClassesThat().resideInAnyPackage("org.springframework..")`, so **any** class named
    `*AutoConfiguration` silently opts out of the module's no-runtime-Spring guarantee — a naming
    convention, not a real constraint. Today it exempts exactly one class
    (`MoneyAutoConfiguration`) and is not over-exempting anything. Scoping the exemption by the
    `@AutoConfiguration` annotation, or by package, would not be forgeable. Worth doing when
    `platform-web` and `platform-tenancy` arrive with auto-configurations of their own, because
    that is when the convention starts carrying weight it was never designed to carry.
24. **The Spring Boot version is pinned in two independent places and can drift silently.** The
    root project pins it via the plugin (`backend/build.gradle.kts`, `id("org.springframework.boot")
    version "4.1.0"`), and the module pins it via the BOM
    (`backend/platform/platform-primitives/build.gradle.kts`,
    `platform("org.springframework.boot:spring-boot-dependencies:4.1.0")`). Nothing checks the two
    agree, and a mismatch would surface as a confusing resolution error rather than as "you edited
    one of two". The right fix is a Gradle version catalog (`gradle/libs.versions.toml`) or a shared
    convention plugin — a build-structure decision worth making **once**, at module 2, rather than
    twice.

**From the `public-rate-limiting` slice (2026-08-28).** Items 25–28 are per-task review findings;
29–32 came from the whole-branch review, which triaged all four as genuinely safe to defer (its two
must-fix findings — the eviction window and the context path — were fixed on the branch, not deferred).

25. **Jackson's `AUTO_CLOSE_TARGET` closes the servlet output stream** when `RateLimitFilter.reject`
    writes the 429 body via `writeValue(response.getOutputStream(), …)`. Harmless today because the
    deny path is terminal — nothing runs after it. It stops being harmless the moment anything is
    layered after the limiter in the chain.
26. **`org.springframework.lang.NonNull` is `@Deprecated` since Spring 7.0** (JSpecify annotations are
    the replacement). `RateLimitFilter` is its only use in `src/main/java`. Cosmetic, but it is the
    kind of thing that becomes a compile warning wall later; fix it when JSpecify is adopted repo-wide.
27. **Three of the five `RateLimitFilterTest` cases would also pass against a pass-through filter**
    (allowed / unmatched / disabled). They are negative controls. The two load-bearing cases — deny,
    and the bucket key — are properly falsifiable, and four integration tests cover the rest, so this
    is thin rather than wrong.
28. **`HarnessRateLimitDisabledTest` guards the shared context only.** It proves the suite-wide
    `enabled=false` default still applies to a plain `IntegrationTest` subclass, and the re-review
    proved it falsifiable by deleting the annotation and watching it go red. It cannot catch a future
    test class that forks its own context with the limiter ON, nor a new `@SpringBootTest` base that
    does not extend `IntegrationTest`. All 64 current subclasses inherit cleanly.
29. **`UrlPathHelper.getPathWithinApplication` URL-decodes the path**, so a `%2F` inside a share token
    would split into segments and miss the `/public/q/*` pattern where the raw URI matched. Not
    reachable today: Tomcat rejects encoded slashes with a 400 before the filter runs. It becomes
    reachable if `ALLOW_ENCODED_SLASH` is ever turned on, or behind a proxy that normalises differently.
30. **`refillPeriod` carries `@NotNull` but no positivity constraint,** so `refill-period: 0s` binds
    successfully at startup and then throws inside Bucket4j on the first request — the one
    misconfiguration in this properties class that still fails late rather than fast. `capacity` is
    correctly `@Positive`.
31. **CGNAT collateral: customers behind one carrier NAT share a bucket.** A share link forwarded to
    several people on the same mobile carrier can 429 legitimate recipients. This is inherent to the
    per-IP keying decision (design spec §3 argues why per-token is worse), not a defect — but it is the
    failure mode to look for first if a distributor ever reports "my customer says the link is broken."
32. **The shipped `auth` policy is never exercised end-to-end.** `RateLimitIntegrationTest` overrides
    `policies[1]` with its throwaway `api-protected` policy, so the 30/minute login cap is covered only
    by `RateLimitDefaultsTest`'s matching assertions. Adequate — the mechanism is identical and proven
    on the other policy — but no test ever drives a real login to 429.

**From the `record-visibility` slice (2026-08-29/30).** Items 35–41 are open. Items 33 and 34 were
open when the slice's docs task ran and were **closed afterwards** by the whole-branch review's fix
wave — that review ran *after* the docs task, which is why items 37–41 below exist at all: they are
its findings, and they were never going to appear in a handoff written before it. Four other per-task
`minor (deferred)` findings from this slice's ledger resolved themselves before the slice ended
(`VisibilityPolicy.enquiries()` lacked a direct test in Task 1 — covered end-to-end by Task 4;
`VisibleFinder`'s javadoc forward-referenced `VisibilityScopingArchTest` before Task 8 created it;
no test drove a `page*` method with a non-null caller filter — covered by Task 3's `?active=` list
test; and Task 3's reviewer flagged that `CustomerService`'s changed constructor arity was checked
for other direct-construction call sites but not independently re-verified — Task 5's reviewer did
that independent check two tasks later, grepping the whole codebase and confirming no manual
`new XService(...)` call site exists anywhere, closing the concern) and are not carried forward as
open items. The `PriceResolver`/`QuotationService` comment sweep this same ledger flagged for Task 9
is also done — see §3.

33. ~~**Unused `@Autowired TestTokens` field in `VisibilityPolicyIntegrationTest`.**~~ **DONE** —
    removed by the whole-branch fix wave.
34. ~~**`activeFilterStillWorksForAnOwner` never creates an inactive customer.**~~ **DONE** — the fix
    wave deactivates a customer in the fixture and asserts both directions (`?active=false` returns
    it and omits the active one; `?active=true` the reverse). It was the only test of
    `CustomerSpecifications.filter`, the new code that replaced the deleted
    `CustomerRepository.findByActive`, so its positive behaviour had been unproven.
35. **`CustomerService.update` validates `assignedTo` before `find(id)`; `EnquiryService.update`
    validates it after.** Considered as an existence oracle and dismissed during Task 7's review — a
    plain `GET` already discloses visible-vs-not, so the ordering leaks nothing incremental.
    Cosmetic inconsistency only.
36. **The two ArchUnit tests in `com.easycrm.arch` now use visibly different idioms** —
    `TenantScopingArchTest` uses declarative built-in rules, while `VisibilityScopingArchTest`
    (Task 8) needs a hand-rolled `ArchCondition` because method-call inspection has no built-in
    predicate for "was this call routed through class X." Noted for a future reader only, not a
    defect.

**From the whole-branch review (2026-08-30).** Three of its findings were fixed before merge (the
count query that never ran, four list tests that asserted only absence, and `activeFilterStillWorks`
above). These five were judged safe to defer. **Items 37–39 are all blind spots in the same guard**,
and they are worth fixing together rather than one at a time.

37. **`VisibilityScopingArchTest`'s allowlist is keyed on a bare method name, not a
    `(repository, method)` pair.** So `findByQuotationId` is permitted from *any* class, even though
    its §6.1 justification — "reached only from an already-checked quotation" — is a property of its
    single current call site, not something the guard enforces. Likewise `findByGstin` and
    `findByNormalizedPhone` are permitted on all four repositories rather than only the one each
    belongs to. Fix: allowlist `owner + "." + name` pairs. This is the most substantive of the three
    and the one that makes the allowlist mean what it says.
38. **A field declared as `JpaRepository<Customer, UUID>` would be invisible to the guard.** The rule
    keys on `getTargetOwner()`, which resolves to the *statically declared* receiver type — that is
    exactly what makes it catch inherited methods like `findById` as calls against
    `CustomerRepository`. The flip side is that a field typed as the Spring Data supertype (which
    Spring resolves by type perfectly happily) has a target owner outside `GUARDED_REPOSITORIES`.
    Obscure, but it is the one way to reach these rows that the allowlist cannot see.
39. **Method references to *inherited* repository methods still bypass the guard.** The fix wave
    unioned `getMethodReferencesFromSelf()` into the rule, which closes `someRepo::someDeclaredMethod`
    — but a reference to an inherited method (`customers::findById`) resolves its ArchUnit target
    owner to `CrudRepository`, not to the local interface, so it slips through. There are no such
    references in the codebase today. This residual is disclosed in a comment in the test itself
    rather than being quietly claimed closed; closing it properly means widening
    `GUARDED_REPOSITORIES` or matching on the declaring hierarchy.
40. ~~**`requireAssignableUser` is duplicated verbatim, javadoc included, in `CustomerService` and
    `EnquiryService`.**~~ **DONE** — closed by the `activity-follow-up` slice (§3): `FollowUpService`
    needed a third copy, which was the extraction trigger. Both call sites now delegate to
    `com.easycrm.iam.AssignableUsers.require(UUID)`.
41. **The three count-query tests assert row *count* but not row *identity*.** After the fix wave,
    `pagingAppliesVisibilityToBothTheDataAndCountQueries` and its quotation/order siblings page with
    `PageRequest.of(0, 1)` to force the count supplier to run, and assert `getContent()` has size 1
    — dropping the `containsExactlyInAnyOrder(mine, pool)` identity check the pre-fix customer test
    had. A broken filter still surfaces through the total, so the defect they were written for stays
    covered, but they discriminate less on the data-query side than their own docstrings claim.
    Asserting the returned row is one of the visible set closes it.

**From the `activity-follow-up` slice (2026-08-30).** All four open. Item 45 is a deliberate,
disclosed deviation from `CLAUDE.md`'s "log it in the same change" rule, not an oversight — the other
three are ordinary per-task findings judged safe to defer.

42. **`FollowUpService` imports `org.springframework.data.domain.Sort` but never uses it.** Cosmetic;
    no checkstyle gate is configured to catch an unused import at build time.
43. **`ActivityEditEndpointTest.aMismatchedSubjectIs404` passes for the wrong reason.** It supplies a
    random `subjectId`, so it 404s at `VisibleFinder.requireVisibleSubject` before the
    subject-scoped repository lookup (`findByIdAndSubjectTypeAndSubjectId`) is ever reached — it
    proves the gate works, not that a *mismatched-but-real* subject is rejected. The underlying
    property this test's name promises **is** covered, just by different tests:
    `ActivityRepositoryTest.findByIdIsScopedToTheSubjectItWasFiledUnder` proves it at the repository
    level, and `ActivityRepositoryScopingArchTest` proves it structurally (§3, challenge #50). Fix:
    seed a second real, visible enquiry and use its id as the mismatched subject, so the test
    actually exercises the repository-level scoping it's named for.
44. **`@JsonInclude(Include.NON_NULL)` sits on the whole `ActivityResponse` record**, so it also
    suppresses a null `outcome`, not just the `followUpId` field it was added for. Dormant today —
    the client already treats a missing key and an explicit `null` the same way — but the blast
    radius is wider than the one field that motivated it. Narrow it to `@JsonInclude` on the
    `followUpId` field alone if it ever bites.
45. **Challenge #50's log entry (the bare-`Repository` mechanism) was written in the slice's
    docs task (Task 14) rather than in the task that introduced the mechanism (Task 2, `ff456fb`)** — a
    literal deviation from `CLAUDE.md`'s "same change" rule. Accepted because the guard that
    demonstrates *why* the mechanism matters (`ActivityRepositoryScopingArchTest`, Task 3, `642c94c`)
    only landed a task later; logging at Task 2 would have had no guard to point to yet.
46. **Cross-assignment on `follow_up` is a one-way door, undecided by design, not a bug.** A
    `SALES_EXEC` who creates a follow-up assigned to a colleague gets a `201` carrying a
    `followUpId`, then `404`s on `GET /follow-ups/{id}` and cannot cancel or reschedule it — only
    the assignee or an unrestricted role can, because `VisibilityPolicy.followUps()` filters
    strictly on `assignedTo = me` (design spec §4.1). The API is coherent with that policy but
    returns a link the caller cannot follow, and nothing documents or tests the boundary today
    beyond the spec paragraph added alongside this item. Whether a creator should retain any
    visibility or control over work they assigned to someone else is an open design question —
    who may assign work to whom is out of scope for this slice.

**From the `quotation-auto-expiry` slice (2026-08-31).** Three items (47–49), all open.
Item 47 is the one with a real trigger: it is the thing to fix *before* the first large
tenant, not after, for the reason its own entry gives.

47. **`QuotationExpirySweep.run` issues one `findById` per expiry candidate** to read that
    candidate's `QuotationVersion` (for `validUntil`, to attach to `QuotationExpiredEvent`), rather
    than batch-loading the versions for the whole candidate set in one query. Irrelevant at current
    volumes — a tenant's nightly lapsed-quote count is small — but if a tenant ever accumulates
    thousands of lapsed quotes overnight, this is N+1 by construction. Fix: fetch all candidate
    version ids from `expirableAsOf`'s own subquery result and batch-load them with a single
    `findAllById`, keyed by the quotation's `currentVersionId`.

48. **`TenantJobRunner` hardcodes which tenant statuses a job sweeps.** `JOB_ELIGIBLE =
    {TRIAL, ACTIVE}` is a constant with no override, but that is the *quotation-expiry* product
    rule (design decision D4) baked into a seam meant to be reused. The two future jobs the spec
    names — entitlement metering and billing — legitimately need `SUSPENDED` tenants, since you
    still meter and bill a suspended account. The runner is otherwise clean of caller-specific
    assumptions; this is the one place it is not. Fix when the second job arrives, not before: add
    a `forEachTenant(String, Collection<TenantStatus>, ToIntFunction<UUID>)` overload and leave the
    two-arg form delegating to it with the current default.

49. **The auto-expiry slice's cross-tenant isolation is proved over `customer`, not over
    `quotation`.** The design spec §8 asked for "tenant B holds an expirable quote; sweep tenant A
    only; B's quote is untouched"; what exists is `TenantJobRunnerTest.eachTenantsBodySeesOnlyItsOwnRows`,
    which makes the same proof using customers. Defensible — the GUC/`@TenantId` mechanism under
    test is shared, and both `quotation` and `quotation_version` carry `FORCE ROW LEVEL SECURITY`
    (`V26__force_rls.sql`) — so the specific claim in `QuotationSpecifications.expirableAsOf`'s
    Javadoc ("the subquery cannot reach another tenant's versions") is untested but practically
    unfalsifiable, since version ids are UUIDs. Noted because the spec singled the test out as
    load-bearing, not because the isolation is in doubt.
50. **`InvitationService` is two services sharing a constructor, and the split is deferred, not
    missed.** The owner-authenticated half (`invite`, `listPending`, `revoke`) and the pre-auth
    half (`accept`, `preview`, `requireLive`) have different callers, different security postures
    and almost different dependency sets — the constructor takes twelve collaborators, against
    `AuthService`'s eight, and only five are shared. The controllers are *already* split this way
    (`InvitationController` vs `PublicInvitationController`), so the seam is visible; the service
    just has not followed. The whole-branch review deferred it on the grounds that the split buys
    posture clarity rather than fewer parameters, and the pre-auth half has exactly one client
    today. **Revisit when password reset lands** — that is the moment the pre-auth half gets a
    second client and the boundary starts paying for itself. The sanctioned shape is an
    `InvitationAcceptService` owning `accept`, `preview` and `requireLive`.
51. **`invitations.save(...)` is redundant in both `revoke` and `accept`.** Both call it on an
    entity already managed inside the same transaction, where Hibernate's dirty checking would
    flush the change anyway. It is harmless. It is listed only because the redundancy is
    **identical in both places**: removing it from one alone would make two structurally identical
    paths look deliberately different, which is worse than leaving both. Remove both or neither.
52. **`VisibilityScopingArchTest.ALLOWED_METHODS` is keyed on a bare method name**, not on
    `owner#method`, so an allowlisted name is exempt on *all five* guarded repositories at once.
    `countByAssignedToAndStatus` is generic enough that adding a same-named finder to, say,
    `OrderRepository` later would inherit the exemption silently. Pre-existing property of the
    allowlist (`save`, `findByGstin` share it), not introduced by this slice. Fix by keying the
    set on `owner#method`.
53. **An already-`DISABLED` member who holds open work gets a misleading 409.** `requireNoOpenWork`
    runs before `member.disable()`, so the response says "still holds open work" rather than
    "already disabled". Narrow, but actively misleading when it happens.
54. **`MemberService.changeRole` calls `Role.valueOf(role)`**, which throws
    `IllegalArgumentException` → 500 if a future caller reaches the service without
    `ChangeRoleRequest`'s `@Pattern`. A catch mapping to `ValidationException` would make the
    service safe standalone.
55. **`users.findAll(Sort.by("email"))` sorts by database collation**, so casing affects the
    members list's order. Cosmetic but user-facing.
56. **A malformed non-UUID `{id}` returns Spring's default 400**, not the house `{error:{code}}`
    envelope — `ApiExceptionHandler` has no `MethodArgumentTypeMismatchException` handler.
    House-wide and pre-existing on every controller with a UUID path variable.
57. **`RefreshTokenService.revokeAllForUser` calls `saveAll` on already-managed entities** —
    redundant under dirty checking, but mirrors the adjacent `revoke()` method. Fix both together
    or neither.
58. **The 409's prose pluralizes bluntly ("1 customers").** The machine-readable `fields` map is
    the actual contract for the frontend; the prose is a fallback.
