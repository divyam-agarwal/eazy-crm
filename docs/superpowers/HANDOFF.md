# EasyCRM — Handoff

## 2026-09-20 — START HERE: F0b is merged and pushed; next is F1

**The 107 controller rulings made during F0b's build** — several load-bearing for F1 and visible nowhere
else — are preserved in
[`docs/superpowers/2026-09-20-f0b-ruling-record.md`](./2026-09-20-f0b-ruling-record.md), since the
working directory they came from (`.superpowers/sdd/2026-09-16-f0b-frontend-foundation/`) is gitignored
and does not survive the slice.

**State.** F0b (the frontend itself — scaffold, API client, session lifecycle, login/signup/invite
pages, app shell, coverage floor, CI) is **MERGED AND PUSHED**. `main` and `origin/main` are both at
**`6d7b970`** (fast-forward of 38 commits, `389f23b..6d7b970`). The `f0b-frontend` branch and its
worktree are **deleted** — do not look for them. The full suite was re-run on the merged tree, not
inherited from the branch: lint, typecheck, 307 Vitest tests / 33 files, `./gradlew clean check` with
688 backend tests, 11/11 Playwright E2E, six routes under the 200 KB budget at +0.0 KB drift.
**CI run `35493017772` is green on all five jobs** — the first run of the new `frontend` (58 s) and
`e2e` (2 m 40 s) jobs on `main`, from a cold cache. Verify with two separate invocations:
```
git rev-parse --short main         # 6d7b970
git rev-parse --short origin/main  # 6d7b970
```
`main` and `origin/main` are in sync (both `389f23b`) — nothing from this branch has reached `origin`.

**Correction to the standing caveat below and in every earlier section of this file:** every prior
entry says the five frontend specialist reviewers were "not callable by `subagent_type`" and prescribes
a `general-purpose`-agent-reads-the-file-verbatim fallback. **That is now obsolete.** Across this
entire F0b build (16 implementation tasks plus this closing one) the five lenses —
`frontend-review-architecture`, `frontend-review-performance`, `frontend-review-security`,
`frontend-review-a11y-i18n`, `frontend-review-testing` — were dispatched **by name**, as real
`subagent_type` values, every time a review was requested, including the plan review recorded below.
The fallback is dead weight for this repo; a future session should call them directly and drop the
fallback instructions from its own report.

**Baselines, all measured this session:**
- **Backend: 688 tests, 0 failures**, `./gradlew clean check` → `BUILD SUCCESSFUL` (was 677 before
  F0b; +11 net — F0b's own backend-side additions, chiefly `OpenApiRequiredFieldsTest` (P1) and
  `FrontendWorkflowTest`'s six CI-guard assertions (Task 16)). Count is the sum of every
  `build/test-results/test/*.xml` `tests="…"` attribute across both Gradle modules, not a claim.
- **Frontend: 303 tests, 33 files, all green** (`pnpm test:coverage`). **Updated by the final fix wave
  (2026-09-20, see `final-fix-report.md`): 307 tests, 33 files** — four new tests, each added to close
  a gate the whole-branch review proved could not fail (P15's coordinator lock, the resubmit guard, the
  budget script's zero-files check, and RootLayout's invite-route exemption from `onSessionExpired`).
- **Coverage floor (Task 17 Step 1), set from this exact measured run, each floored to a whole
  percent:** Statements 97 (measured 97.41%), Branches 92 (92.51%), Functions 96 (96.81%), Lines 98
  (98.44%). Proven to bite: temporarily setting `lines: 100` in `vite.config.ts` produced `ERROR:
  Coverage for lines (98.44%) does not meet global threshold (100%)`; restored, reran green. **After
  the final fix wave: 97.45% / 92.64% / 96.82% / 98.46% — still clears the same floor**, unchanged.
- **E2E: 11/11 PASS** (`pnpm e2e`, two real backends on :18080/:18081, two `vite preview` instances on
  :41731/:41741 — deliberately not Vite's default :4173, see `e2e/playwright.config.ts`'s own comment)
  — `cross-tab-logout` (3), `guards` (axe + CSP) (2), `invite-accept`, `invite-invalid`,
  `invite-signed-in`, `signup-reload-logout`, `refresh-rotation` (2). Still 11/11 after the final fix
  wave.
- **Per-route gzipped JS (`pnpm budget`, budget 200 KB/route), headroom tightest first:**
  `/signup` 177.0 KB (**23.0 KB** headroom) · `/invite/:token` 176.0 KB (**24.0 KB**) · `/login` 172.7 KB
  (**27.3 KB**) · shared entry (`index.html`) 144.6 KB (**55.4 KB**, inherited by all four routes). The
  baseline-diff column (challenge #102) printed `+0.0 KB` on every route against `budget-baseline.json`
  — no drift since it was last checkpointed. **Largest standing lever if a route needs headroom:**
  react-router's data API, ≈17.2 KB gzipped, deliberately kept for `errorElement` and route-level
  `lazy:` rather than the plain component-mode router (P9-adjacent decision, not separately numbered).
  **Updated by the final fix wave (I1, I7 — see `final-fix-report.md`):** I7 moved `cn` out of the
  entry chunk (`RouteSkeleton` no longer needs it), and I1 added the two previously-unmeasured lazy
  routes. Re-measured: **entry 133.8 KB** (-10.8 KB) · `/login` 171.8 KB · `/signup` 176.2 KB ·
  `/invite/:token` 175.2 KB · `/` 146.8 KB (new) · `*` 134.1 KB (new) — all under budget;
  `budget-baseline.json` updated via `pnpm budget --update`.
- `pnpm lint`, `pnpm typecheck`, `pnpm gen:api && git diff --exit-code -- src/api/schema.d.ts` (no
  drift) all clean.

**The recorded red runs (Tasks 15–16), one line each — these are what make the gates trustworthy, not
just present:**
- Task 15 Step 4, P11 (`maxInFlight` cross-tab refresh serialization): rebound `noopLocks`, ran the
  existing E2E assertion four times — reliably red (`Expected: <= 1, Received: 2`) every time; reverted.
- Task 15 Step 4, P15 (login/signup/accept/logout sharing refresh's Web Lock): removed
  `withCookieLock(...)` from `useLogin.ts`, ran a throwaway two-tab spec three times — reliably a `401`
  on the next refresh (the shared cookie jar was left holding a dead token); restored the lock, ran
  twice more — reliably `200`. Neither binding survived past its recorded run (challenge #106).
- Task 16, `jobsAreBlocking`: added `continue-on-error: true` to the `frontend` job — red.
- Task 16, `gateStepsAreUnconditional`: added `if: false` to one gate step — red (checked-count
  assertion `assertEquals(12, checked)` is the non-vacuity proof this isn't skipped silently).
- Task 16, `onlyUploadsAreConditional`: changed the bundle-report upload's condition from `!cancelled()`
  to `failure()` — red. Getting this assertion to pass at all required its own red/green cycle
  (challenge #107): `${{ !cancelled() }}` (GitHub's own recommended escaping) parses through SnakeYAML
  to a different literal string than the bare, single-quoted `'!cancelled()'` the guard asserts on.
- Task 16, `gateBodies`: narrowed the E2E step to `pnpm e2e --grep cross-tab` — red (the "must run every
  project and test" assertion catches a silently narrowed CI run, not just a missing one).
- Task 16, `continueOnErrorIsOnlyOnRegisteredGates`: added a new, unregistered step carrying only
  `continue-on-error: true` (no `if:`, not in `GATES`) — red, closing the exact blind spot
  `gateStepsAreUnconditional` and `onlyUploadsAreConditional` each miss on their own.
- Task 16, `postgresImageIsPinned`: floated the E2E Postgres tag to `postgres:16-alpine` — red.

**How to run this locally:**
```
cd backend && docker compose up -d && SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun   # backend + db
cd frontend && fnm exec --using=24 -- pnpm dev                                       # frontend
```
E2E prerequisites, in order: `cd backend && ./gradlew bootJar`; `cd frontend && pnpm build`;
`pnpm exec playwright install chromium` (already cached on this machine at
`~/Library/Caches/ms-playwright`); then `pnpm e2e`. Every `pnpm` command needs the Node 24 activation
from the section below — an agent's non-interactive shell still starts on the system Node.

**The manual walkthrough (Task 17 Step 2/2b). Its full report lived in the git-ignored SDD workspace and
is gone with the worktree; the headline findings were copied here before that, and are now the only
record:**
- **R64's open question is answered, but narrowly — read the scope before trusting it further:** no
  Devanagari clipping was found in either `Input` or `SelectField`'s fixed `h-9` (36px) box, **at
  `text-base` only** (the font-size/line-height — 16px/24px — that actually computes below the `md:`
  breakpoint), tested with representative strings and worst-case stacked conjuncts/chandrabindu (श्री,
  गुरुद्वारा, हूँ, मूँछ). **What this did NOT test, and is still open:** `label.tsx:13`'s
  `leading-none` (a label's line-height is far tighter than `Input`'s, and conjuncts/chandrabindu are
  exactly where a zero-slack line-height clips); `alert.tsx:41`'s `line-clamp-1` (a single clamped
  line is a different failure mode — truncation, not vertical clipping); the `md:text-sm` variant
  (untested at the smaller size/line-height pair `Input`/`SelectField` also carry above the `md:`
  breakpoint); and true viewport emulation — this session's browser automation could not shrink the
  real rendering viewport (`window.innerWidth` stayed pinned at 1920 despite the resize tool reporting
  success) or trigger real browser page-zoom, so the 320px/200%-zoom conditions were reproduced by
  forcing the exact computed font metrics a real narrow viewport would apply, rather than by an actual
  device-toolbar emulation. Treat all four as open, not as covered by this result, before F1 ships
  Hindi content into these primitives.
- **The P14/Security-1 critical path was verified by hand, live, against a real stopped/restarted
  backend: PASS.** Signed out with the backend killed → blocking "Sign-out did not complete — retrying"
  screen shown; reloaded while still down → still blocking, not signed back in; restarted the backend →
  the retry completed on its own and landed on `/login`. This is the scenario P14 exists for, and it
  behaved exactly as designed.
- Tab order verified correct on `/login` (Workspace → Email → Password → Show password → Sign in →
  Create a workspace) and into `/signup` (Business name → Workspace name → State); wrong password
  announces a focused `role="alert"`; the alert's contrast was measured live in the browser (Canvas 2D
  compositing the actual `oklch()`/`oklab()` computed colors, not a manual formula) at **6.79:1**,
  clearing the 4.5:1 AA minimum. Password-toggle-with-Space works on both `/login` and `/signup`. All
  three invite states work end-to-end (anonymous accept-with-Enter, signed-in "Sign out and accept",
  invalid token) against a real backend-minted token. `AppShell`'s header uses `flex flex-wrap`
  (verified by reading the code, not by narrowing a real viewport — same tooling caveat as above).
- **One sub-item could not be verified as asked:** arrow-key selection on the native `<select>` while
  it's actually open is a well-known gap in low-level CDP `Input.dispatchKeyEvent` against OS-rendered
  select popups (Playwright's own docs recommend `selectOption()` instead of real key simulation for
  exactly this reason) — not an app defect, since `SelectField` is a plain native `<select>` (P9) and
  the browser's own arrow-key handling for a focused, open select is standard, ubiquitous behaviour this
  automation session simply couldn't drive. Worked around with a direct `.value` + `change` event to
  keep the walkthrough moving; a human should still confirm this one sub-item by hand.

**Notes for F1, so the next slice inherits these decisions rather than rediscovering them:**
- `src/api/types.ts` keeps all 8 re-exports through F0b. **F1's first non-auth schema is the moment to
  split it**: features own their own type aliases, and `api/types.ts` keeps only what `src/session/`
  needs (R43).
- Two copy items queued for F1's i18n pass: `errors.fields.*` and `validation.*` currently duplicate the
  same English strings with nothing keeping the two in sync; and length-constraint error messages state
  the rule ("must be at least 8 characters") but not the value the user actually entered (R45).
- `SelectField` is a native `<select>`, not shadcn's `Form`/Radix `Select`, **for a CSP reason, not just
  a low-end-Android one**: Radix's `Select` pulls in `react-remove-scroll`, which injects a runtime
  `<style>` tag that `style-src 'self'` refuses. A lint rule (R83, R86) now blocks that import family —
  F1 should not "fix" a perceived regression by reaching for Radix `Select` again.
- Only `zod/mini` is allowed, never bare `zod`: a bare import costs ~92 KB gzip against `zod/mini`'s
  ~5 KB, and `@hookform/resolvers`' own README examples lead you to the expensive import. Lint-enforced
  (R67, R73) — a new form in F1 that fails this lint should fix the import, not disable the rule.
- **P20** (Part 10 of the F0 spec): `applyApiError` actually resolves a field message through **four**
  levels — `errors.fields.<field>.<CODE>` → `errors.fields.<CODE>` → `errors.codes.<code>` → server
  text — not the three §4.6 originally described. This was implemented but never declared as a spec
  amendment until this session (ruling R30); F1 form work should read the four-level order from Part 10,
  not from §4.6's older prose.
- Task 12's `InvitePage` and `RootLayout` both assert "a status-region update is a *mutation*, not a
  fresh insertion" (so a screen reader doesn't announce it as brand-new content), but by different
  means: `RootLayout` asserts it directly, by DOM node identity across renders; `InvitePage`'s test
  proves the same property only *incidentally*, via a duplicate-element error the testing library throws
  if the node were re-inserted rather than mutated. If `InvitePage`'s status region is ever refactored,
  its test may stop catching a regression here even though it still passes — worth tightening to a
  direct identity assertion if that region changes.
- The plan's own "expected major versions" list was wrong and is superseded by what was actually
  installed and passed the environment probe: **vite 8.3.0, @vitejs/plugin-react 6.1.1, vitest 5.0.1,
  typescript 5.9.3** (pinned to `^5`, not `^6`, because `typescript-eslint` 8 caps at `<6.1.0`). The
  plan's original trio (vite 7 / vitest 5 / typescript-eslint 3-ish) fails the environment probe outright
  — don't re-attempt it in F1.
- **A cold-start E2E flake was seen once** (a 6.1 s wait against a 5000 ms `toBeVisible`), never
  reproduced in 8+ subsequent runs by three different people/sessions. Mitigated by raising that one
  assertion's timeout to 15 s rather than chasing a repro. CI's first run of any given day is always a
  cold start (fresh runner, cold caches) — watch that specific run if this flake ever resurfaces.
- **P11's non-flakiness is empirical, not structural.** `maxInFlight`'s cross-tab overlap assertion
  relies on two real network requests naturally overlapping in time, not a forced hold — 6/6 consistent
  red runs against `noopLocks` (challenge #106), but a sufficiently loaded CI runner could in principle
  serialize the two requests by accident and produce a false pass. If this test ever flakes, that's the
  first hypothesis, not a broken lock.

**F1 tickets from the final whole-branch fix wave — found, deliberately NOT fixed on this branch
(each is real but small enough to defer without blocking hand-off):**
- `RetryableRequestError` has no branch in `api/errors.ts`'s classifier, so "refresh unavailable" renders
  as the generic "check your connection" message instead of its own wording.
- `UnreachableScreen.tsx:29` uses the native `disabled` attribute (the a11y fix applied to the three
  auth-page submit buttons — `aria-disabled` — was never carried here).
- `test/fixtures.ts:26`'s `errorBody()` has no type link to the generated contract, so it can silently
  drift from the real `ApiErrorResponse` shape.
- `check-budget.mjs:250`'s `import.meta.url === file://${process.argv[1]}` direct-run check no-ops on a
  path containing a space (the URL is percent-encoded, the raw path is not).
- `errors.fields.businessName.SIZE` and `slug.SIZE` are unreachable — `SignupRequest.java` has `@Size`
  on `password` only — and `slug.PATTERN` drops the 3–64 length constraint from its message.
- The i18n lint zone omits `src/components/**` and misses `aria-label`/`placeholder`/`title` attributes,
  so a hardcoded string in any of those three attributes, or anywhere under `components/`, is invisible
  to the enforcement Task 9 built.
- `establishSession` is called from three pages (`LoginPage`, `SignupPage`, `InvitePage`) rather than
  from inside each mutation itself, so a fourth caller could forget it.
- **I7's `cn`-out-of-entry win (this same fix wave) has no regression test of its own.** Re-importing
  `Skeleton`/`cn` into anything statically reachable from `router.tsx` would cost ~13 KB gz back and
  **would not trip `pnpm budget`**, since every route has 23–55 KB of headroom to absorb it silently.
  The right fix is structural — an ESLint rule barring `cn`/`components/ui/*` imports from
  router-reachable files (same shape as the Radix-`Select`-via-`react-remove-scroll` lint rule, R83/
  R86), or an explicit "these packages must not appear in the entry chunk" assertion alongside
  `check-budget.mjs` — and is better made by whoever owns F1's lint-zone work than bolted on here.

**Deployment gaps recorded for SP2 (final fix wave) — there is no deployment layer in this repo, so
these are recorded, not fixed, and SP2 (`docs/ROADMAP.md`'s "SP2 — AWS foundation, dev environment")
owns them:**
- **The CSP ships ONLY from `vite preview`** (`frontend/vite.config.ts`'s `preview.headers`, there so
  E2E can catch a real violation) — no `<meta http-equiv="Content-Security-Policy">` fallback exists in
  `index.html`, so a deployment that serves `dist/` any other way ships with **no CSP at all** unless
  SP2's hosting layer sets the header. The list is also missing **`form-action`**.
- **`/public/q/{token}` is mapped OUTSIDE `/api`** (`PublicShareController.java:26`,
  `@RequestMapping("/public/q")`). A standard SPA fallback rule (serve `index.html` for any
  unrecognized path) would swallow it before the backend ever sees it, and once served `index.html`,
  `router.tsx`'s catch-all `*` route (`NotFoundPage`) would shadow it — turning a shared-quotation link
  into a 404. That URL shape is **already in sent WhatsApp messages**, so it cannot be renamed later
  without breaking links already out in the wild. SP2's routing config must special-case `/public/q/*`
  (alongside `/api/*`) ahead of any catch-all SPA-fallback rule. Recorded in spec Part 8 too.

**Carried forward, still open (unchanged from before F0b, plus nothing new this session):** F0a's
follow-ups (grace-use audit, logout-vs-grace race, `IssuedSession.toString()`, a class-level arch test,
`CustomerService` `fieldCodes` before F1), H7 (freeze the seller on a sent quotation), item 3b
(cross-service data access design), the DNS provider (rest of D-g), the skipped test-tooling Dependabot
group (Testcontainers 2 / JUnit 6 — does not compile), and P8 (the toast container arrives with F1's
first background failure).

**Next: F1 (master data).** Brainstorm → spec → plan, same as F0 did. `superpowers:using-git-worktrees`
for a fresh worktree off the current `main` (`6d7b970`).

---

## 2026-09-19 — START HERE: the F0b plan is reviewed and revised, Node 24 is installed; **build F0b next**

**State.** No application code changed. All five frontend specialist reviewers reviewed
[`plans/2026-09-16-f0b-frontend-foundation.md`](plans/2026-09-16-f0b-frontend-foundation.md); **all five
returned "Ready with fixes"**, and the fixes are applied. **`main` is pushed at `2b8c23e`**
(`7e24783` = the plan revision, `2b8c23e` = this handoff + roadmap; both docs-only). Reviewers were
**still not callable by `subagent_type`** — the fallback (a `general-purpose` agent told to read
`.claude/agents/frontend-review-<lens>.md` and follow it verbatim) worked again.

> **Correction (2026-09-20, see the top section):** this "not callable by `subagent_type`" note was
> carried across three sessions and turned out to be stale — every review across the F0b build that
> followed this one dispatched the five lenses by name. Left as-written below for the historical
> record of what this session actually observed; do not repeat the fallback in new work.

Verify (separate invocations):
```
git rev-parse --short main           # 2b8c23e, or later
git rev-parse --short origin/main
git rev-list --count origin/main..main   # 0
```
**CI on `2b8c23e` was not checked before this was written** — it is docs-only, but confirm the run is green
before assuming it.

### Node 24 is installed — but an agent shell does NOT get it automatically

Installed 2026-09-19 at the owner's request: **fnm 1.39.0** (Homebrew) and **Node v24.21.0**, which is fnm's
`default`. `eval "$(fnm env --use-on-cd --shell zsh)"` was appended to `~/.zshrc` (backup:
`~/.zshrc.bak-2026-09-19`), so the owner's **interactive** shells now get v24.21.0 and `--use-on-cd` picks up
`frontend/.nvmrc`.

**The catch for the build agent:** the Bash tool's shell is non-interactive, so it does **not** source
`~/.zshrc` — `node --version` there still prints the system **v25.2.1** (Homebrew node at
`/opt/homebrew/bin/node`). The plan pins `engines.node >=24 <25` with `engine-strict=true`, so every
`pnpm` command would fail. Both of these were verified to work:

```bash
eval "$(fnm env)" && fnm use 24 && node --version   # v24.21.0 — do this once per shell, or
fnm exec --using=24 -- pnpm install                 # per command
```
`pnpm` is 10.33.0 (Homebrew) and works under both, so `packageManager` stays `pnpm@10.33.0` as Task 2 assumes.
**Do not change the owner's global Node further without asking.**

**What the review changed — new plan decisions P14–P19, each named in the plan with its finding id:**

- **P14 (the one Critical, security).** A failed sign-out could sign the previous user back in.
  `signing-out` lived only in the tab that started it, while the cookie it guards is durable and shared by
  every tab; other tabs were told `logout`, so they showed `/login` and a reload re-established the
  session. Now: a durable `localStorage['easycrm.logoutPending']` marker that **boot settles before it
  refreshes**, a `signing-out` broadcast, and a retry that stops as soon as any tab signs in — otherwise
  the retry logs the *new* user out. Verified against `AuthController` before accepting.
- **P15.** Login, signup, accept and logout now take the **same Web Lock as refresh**. Confirmed in the
  code: `AuthController:53,64` and `PublicInvitationController:61` call `cookie.read(request).ifPresent(auth::logout)`,
  and `RefreshTokenService.revoke` revokes an already-rotated token's **successor** — so an unlocked login
  racing a boot refresh leaves the jar holding a dead cookie and kills the new session ~15 min later. This
  is also the client-side mitigation for the logout-vs-grace backend follow-up below.
- **P16.** The session **read** side moves to `src/session/` (below `features/`), the write side stays in
  `features/auth`. Without it F1's role-aware UI would have to weaken the layer lint or prop-drill `me`.
- **P17.** `networkMode: 'always'` — TanStack Query's default *pauses* requests when the browser reports
  offline, so the error UI never runs and `/invite/:token` would show its skeleton forever.
- **P18.** The per-route JS budget lands in Task 2, not Task 13; every task re-runs it.
- **P19.** Every gate task ends by breaking one named line and recording the red run — nearly every
  "Expected: FAIL" in the first draft failed only at *import*, which proves nothing (challenges #75–79).

**Smaller, also applied:** forward the caller's `AbortSignal` through `authFetch`; catch an unknown role
at boot (ROADMAP 4a's platform-admin role would otherwise strand the splash forever); focus + scroll the
form alert and darken its text (measured ~3.99:1, under AA); a real-wiring test for refresh-on-401; Task 8
becomes test-first and its booting test can now fail; preload only the `common` i18n namespace; hash
`splash.css`; document **400 and 429** in the contract so the mocks can be typed; a general required-fields
rule with a frozen legacy baseline; E2E for the principal switch and the failed sign-out; a latch instead
of a fixed 1.5 s wait in the cross-tab refresh test. **Challenge #86** (durable pending logout) is drafted
in Task 17 alongside #84 and #85.

### Next: build F0b

1. **Cut the worktree** with `superpowers:using-git-worktrees`: branch `f0b-frontend` off `main`
   (`2b8c23e`). Task 1 Step 1 confirms the backend baseline is **677 tests, 0 failures** before anything else.
2. **Run the plan with `superpowers:subagent-driven-development`**, task by task, the way F0a ran. Give each
   subagent the Node-24 activation line above — a subagent that skips it will hit `engine-strict` and may
   "fix" it by loosening `engines`, which is the wrong fix.
3. **Do not push the branch**; pushing is the owner's call.

**Read the plan's decision table first (P1–P19).** P14–P19 came out of the specialist review and change
behaviour the earlier tasks describe; a subagent that implements only the code blocks without the reasoning
will reintroduce what the review caught. The three highest-risk tasks are **6** (boot and logout — P14/P15),
**8** (now test-first, Testing-2/4) and **15** (the E2E red runs).

**Known stopping points, each with a fallback written into its step:** the jsdom `AbortSignal` /
`AbortSignal.any` probe (Task 2, fallback happy-dom); springdoc ignoring type-level `requiredProperties`
(Task 1, fallback per-component `requiredMode`); why the contract drops the already-annotated **400**
(Task 1 Step 4b — diagnose before writing the customizer); `zod/mini` with `@hookform/resolvers` 5;
Playwright exposing the `Cookie` header in Task 15's test 7 (**stop and report, do not delete the
assertion**); `onlineManager` behaviour for P17's offline test.

Everything in the section below still holds, except that "not yet reviewed" is now done.

---

## 2026-09-16 (later) — `main` is pushed and green; the F0b plan is written (SUPERSEDED above: it is now reviewed and revised)

**State.** No application code changed in this session.
- **`main` pushed:** `ce1db36..2d9a2aa`, at the owner's direction. **CI run `35014714570` is green on all
  three jobs** (check 4m, supply-chain 13s, dependency-check 11m). The Gradle wrapper 9.7.1 / Spotless 8.10.2 /
  jjwt 0.13.0 bumps and the whole F0a auth rework have now passed on CI Linux, not just one Mac. One
  annotation: dependency-check wrote no `dependency-check-report.*`, so its upload step found nothing. That job
  only reports, so this is not a failure, but nobody has looked into why.
- **Baseline unchanged:** 677 tests (642 root + 35 primitives), 0 failures.
- **F0b plan written:** [`plans/2026-09-16-f0b-frontend-foundation.md`](plans/2026-09-16-f0b-frontend-foundation.md).
  17 test-first tasks with code for every step. It implements spec Parts 4–6 and the F0b items of Part 7.
  **Neither the plan nor this handoff/roadmap update was committed as of writing.** Check `git status`; if
  they are still uncommitted, commit them before cutting the worktree.

Verify (as separate invocations):
```
git rev-parse --short main           # 2d9a2aa, or later docs-only commits
git rev-parse --short origin/main
git rev-list --count origin/main..main
```

### Next, in order

1. **Dispatch the five frontend specialist reviewers on the plan** (all five match: architecture, performance,
   security, a11y-i18n, testing), in parallel, **before building anything**. **They were NOT callable by
   `subagent_type` in this session either.** Use the fallback: a `general-purpose` agent told to read
   `.claude/agents/frontend-review-<lens>.md` and follow it verbatim. Check every finding against the code and the
   spec before editing the plan (`superpowers:receiving-code-review`).
   > **Correction (2026-09-20, see the top section):** this "not callable by `subagent_type`" note was
   > carried across three sessions and turned out to be stale — every review across the F0b build that
   > followed this one dispatched the five lenses by name. Left as-written above for the historical
   > record of what this session actually observed; do not repeat the fallback in new work.
2. **Install Node 24 on the owner's Mac first.** The Mac has Node v25.2.1 and no version manager. The plan pins
   `engines.node >=24 <25` with `engine-strict`, so Task 2 stops and asks. Suggested: `brew install fnm && fnm
   install 24`. Do not change their global Node without asking.
3. **Build it:** worktree `f0b-frontend` + `superpowers:subagent-driven-development`, the same way F0a ran. Do not
   push; that is the owner's call.

### What the plan decided (its "Plan decisions" table, P1–P13, has the reasoning)

The five handoff gaps from the section below are closed like this:
- **Gap 1, refresh header:** the bare client sends `X-EasyCRM-Client` at runtime. The contract's required
  header parameter also makes the call fail to compile without it (P3).
- **Gap 2, `AuthResponse` optional fields:** fixed in the **backend** (Task 1). Type-level
  `@Schema(requiredProperties=…)` goes on `AuthResponse`, `MeResponse`, `InvitationPreviewResponse`,
  `SignupStatusResponse`, `ApiErrorResponse` and `ApiError`, guarded by a new `OpenApiRequiredFieldsTest`, with a
  snapshot regen (P1).
- **Gap 3, spec §3.8 wording:** fixed in Task 17.
- **Gap 4, retry vs `Retry-After`:** a 429 honours `Retry-After`, clamped to 1–60 s, because RateLimitFilter runs
  before any token is read. Otherwise retries run 2 s → 5 s → 15 s → 30 s (P2).
- **Gap 5, oasdiff wording:** Task 16 rewords the comment and the test message to name branch protection.

Departures from the spec that a reviewer should look at (Task 17 appends them to the spec as Part 10):
- **P4:** HTTP retry is openapi-fetch's `fetch` option, snapshotting the body bytes once per request.
- **P5:** a fifth session status, `signing-out`.
- **P6:** a boot result arriving after an interactive sign-in is discarded.
- **P7:** the splash sits outside `#root`, with its CSS in an external file.
- **P8:** **no toast container in F0.** Sonner injects a `<style>` tag that `style-src 'self'` refuses, and F0 has
  no background failures.
- **P9:** hand-written field components and a **native `<select>`**, because Radix Select's scroll-lock injects a
  `<style>` tag.
- **P10:** E2E runs `java -jar` on one `bootJar` twice, not `bootRun` twice.
- **P11:** **E2E test 6 asserts at most one refresh in flight across tabs.** The spec's "stale cookie lands last"
  cannot be forced, because Playwright's `route.fetch()` shares the cookie jar. Test 7 restores the pre-rotation
  cookie explicitly so the grace path is really exercised.
- **P12:** layer lint uses `import-x/no-restricted-paths` zones, not `eslint-plugin-boundaries`, whose config
  syntax churns between majors.
- **P13:** the dependency ledger is measured from the production build.

Challenges **#84** (principal change across tabs sharing a cookie jar) and **#85** (P11's E2E design) are drafted
in the plan's Task 17 and get logged when that task lands. The next free challenge number is still **84**.

### Unverified assumptions in the plan — likely places for Task-level surprises

Each has a concrete fallback written into its step:
- **jsdom vs Node `AbortSignal` in `Request`:** Task 2 runs a probe test; the fallback is happy-dom.
- **springdoc honouring type-level `requiredProperties`:** the fallback is per-component `requiredMode`.
- **`zod/mini` checks (`z.trim()`, `z.toUpperCase()`, `z.refine`) with `@hookform/resolvers` 5:** untested.
- **Library majors:** the plan lists the majors it was written for; `pnpm add` must pin to those if newer ones
  resolve.
- **Spring list-property override for the E2E rate limits:** the launcher restates all three policies because Boot
  replaces a whole list.
- **Playwright exposing the `Cookie` header via `allHeaders()` on an intercepted request (test 7):** the step says
  to stop, not delete the assertion.
- **Action versions and the exact Postgres image tag:** resolved by lookup commands in Task 16 Step 1.

### Everything below is still true

The 2026-09-16 section still holds:
- the F0a contract F0b builds against;
- F0a's deliberately-unfixed follow-ups, including **`CustomerService` `fieldCodes` before F1**;
- the skipped test-tooling Dependabot group;
- H7, item 3b and the DNS provider.

**Resolved since then:** "Push `main`" is done. The registry-import question is **answered: yes**. A fresh session
receives `docs/reviewers/registry.md` through `CLAUDE.md`. **The reviewer agents are still not callable by name**,
so use the fallback above.
> **Correction (2026-09-20, see the top section):** this "not callable by name" note was carried
> across three sessions and turned out to be stale — every review across the F0b build that followed
> this one dispatched the five lenses by name. Left as-written above for the historical record of what
> this session actually observed; do not repeat the fallback in new work.

---

## 2026-09-16 — F0a is merged to `main`; next is the F0b plan (SUPERSEDED in part by the section above: `main` is now pushed, the plan is written)

**State.** F0a (backend auth prep for the frontend) is **merged into local `main` at `186adc4`**
(fast-forward from `8465feb`; branch `f0a-backend-auth` and its worktree are deleted). **`main` is NOT
pushed: 33 commits ahead of `origin/main` (`ce1db36`), and CI has run on none of them.** That range is
the F0 spec + F0a plan commits, the four merged Dependabot bumps, and the F0a build. Pushing is the
owner's call — **raise it before anything else.** When pushed, watch the `check` job first: the Gradle
wrapper (9.7.1), Spotless (8.10.2) and jjwt (0.13.0) all moved and have only ever built on one Mac.

**Baseline: 677 tests (642 root + 35 `platform-primitives`), 0 failures, 0 errors** — `./gradlew clean
check` from `backend/`, counted from JUnit XML on the exact tree now at `main`. (Was 626.) Verify:

```
git rev-parse --short main           # 186adc4
git rev-parse --short origin/main    # ce1db36 until pushed
git rev-list --count origin/main..main
```

**Docs for this slice:** spec [`specs/2026-09-14-f0-frontend-foundation-design.md`](specs/2026-09-14-f0-frontend-foundation-design.md)
(Part 3 = F0a, Parts 4–6 = F0b, Part 9 = the specialist review record) · plan
[`plans/2026-09-14-f0a-backend-auth-prep.md`](plans/2026-09-14-f0a-backend-auth-prep.md) ·
[`../api/error-codes.md`](../api/error-codes.md) (new) · challenges **#80–83**.

### What F0a changed — the contract F0b builds against

- **Refresh token lives only in the `easycrm_rt` cookie** — `HttpOnly; Secure; SameSite=Strict;
  Path=/api/v1/auth; Max-Age=30d`. Set by signup, login, invitation accept and refresh; read by refresh
  and logout; cleared by logout. `RefreshRequest` and `TokenResponse` are **deleted**; no body carries a
  refresh token (`IssuedSession` keeps it out of `AuthResponse` by construction).
- **Every session response is `AuthResponse(accessToken, userId, tenantId, tenantSlug, email, role)`**,
  so a browser boots with ONE `POST /auth/refresh` and can detect a principal change.
- **`POST /auth/refresh` and `POST /auth/logout` require `X-EasyCRM-Client: web`** (403 with the error
  envelope otherwise), checked before any token is touched. No CORS exists; a preflight test fails if
  someone adds it. **Logout returns 204 whether or not a cookie was sent.**
- **Rotation is race-safe and lost responses are recoverable once.** Conditional native UPDATEs; a lost
  race is 401, never 409. A token rotated within **30 s** whose successor is still **unused** may be
  presented once more (V35 `refresh_token.grace_used_at`). Logout (and the stale-cookie revoke) ends the
  whole chain and burns the grace. **Web Locks serialization on the client is load-bearing:** two
  concurrent same-token refreshes over HTTP both "succeed" and the later one kills the earlier successor.
- **Login, signup and invitation accept revoke a stale incoming `easycrm_rt`** after issuing the new
  session. Refresh is refused for a SUSPENDED tenant or non-ACTIVE user.
- **Rate limits:** new `session` bucket (120/min per IP) for refresh, logout and me, listed before `auth`
  (first match wins); `auth` stays 30/min for login, signup, signup/status, invitation preview/accept.
- **Error envelope gains optional `fieldCodes`** (field → `SCREAMING_SNAKE` code). Bean validation emits
  the constraint name (`NOT_BLANK`, `SIZE`, …); GSTIN/state/slug errors emit domain codes. Existing error
  bytes unchanged when absent. Rules and the code table: `docs/api/error-codes.md`.
- **Contract:** zero `*/*` responses (all `application/json`; PDF routes `application/pdf`, guarded by
  `OpenApiMediaTypesTest`); header param, logout 204, signup/accept 201 documented.
- **Signup switch:** `SIGNUP_ENABLED` (default **open everywhere**); closed → 404 before any slug lookup;
  `GET /api/v1/auth/signup/status` → `{open}`. A live toggle waits on ROADMAP item 4a.
- **Login email is case-insensitive** (was a latent bug vs V32).
- **`AuthSessionBoundaryArchTest`** pins that only `AuthService` rotates tokens (calls AND method
  references) and only the two auth controllers touch `RefreshCookie`.

### Next: write the F0b plan (`superpowers:writing-plans`)

The spec is approved and already reviewed by all five frontend lenses; **do not re-brainstorm.** Write the
plan for **spec Parts 4–6** against the regenerated `docs/api/openapi.yaml`, then run it the same way F0a
ran (worktree + `superpowers:subagent-driven-development`). Dispatch the specialist reviewers on the
**plan** (all five plausibly match) before executing. Carry these into the plan explicitly — they are
known gaps, not surprises:

1. **The bare refresh client must still send `X-EasyCRM-Client: web`** (spec §4.4, corrected after the
   final review — the first draft would have 403'd every boot). Boot treats a refresh 403 as a client bug,
   never as `anonymous`; test that the header is present.
2. **`AuthResponse` has no `required` list in the schema**, so openapi-typescript makes every field
   optional. Either add `@Schema(requiredMode = REQUIRED)` on the backend DTOs (a small backend commit
   with a snapshot regen) or handle it in the client — decide in the plan.
3. **Spec §3.8 wording** still says "login/accept" for the stale-cookie revoke; signup does it too.
4. **Boot's first refresh retry is ≤5 s (to land inside the 30 s grace), but a 429 carries
   `Retry-After`** — the plan must say which wins.
5. **F0-12:** oasdiff stays non-blocking; F0b updates the `ci.yml` comment and `OasdiffWorkflowTest`'s
   message to name branch protection (roadmap item 8) as the trigger.
6. **The named reviewer agents were NOT callable by `subagent_type` in the F0a session** (they weren't in
   the session's agent list). The fallback worked: a `general-purpose` agent told to read
   `.claude/agents/<name>.md` and follow it verbatim. Try the name first in a fresh session.
   > **Correction (2026-09-20, see the top section):** confirmed — every review across the F0b build
   > dispatched the five lenses by name with no fallback needed. Left as-written above for the
   > historical record of what the F0a session actually observed; do not repeat the fallback in new
   > work.

### Follow-ups found by F0a's reviews — deliberately NOT fixed (none blocks F0b)

- **Grace use is not audited** — a replay within the 30 s window leaves no trace. Needs a tenant-context
  design for audit on the global `refresh_token` path.
- **Logout racing a same-token grace refresh** can leave the grace successor live
  (`RefreshTokenRepository.findReplacedById` is a plain read; `FOR UPDATE` would close it). Narrow: both
  must be in flight together, and logout is outside the client's Web Lock.
- **`IssuedSession.toString()` still prints the 15-min access token** via `AuthResponse` (raw refresh
  tokens are redacted). Nothing logs it today; one-line fix.
- **`AuthSessionBoundaryArchTest` is class-level**, not handler-level: a new cookie-reading handler
  *inside* `AuthController` would not fail it (spec §3.8 asked for handler level).
- **Logout's grace burn is unbounded in time** — someone holding the immediately previous token can end
  the current session until it next refreshes. Kept on purpose (logout semantics; doubles as a theft signal).
- Test hardening: the race tests' `pg_stat_activity` wait isn't tied to the winner's pid
  (`pg_blocking_pids` would be exact); no negative tests for the `session` path pattern; no test that
  member-disable blocks grace; the clearing-cookie test checks only `Max-Age`/`Path`.
- `CustomerService`'s own buyer state/GSTIN mismatch `ValidationException` has no `fieldCodes` code —
  **needed before F1's customer form.**
- `RefreshCookie`'s field in both auth controllers has a scoped SpotBugs `exclude.xml` Match: its
  `write`/`clear` names trip SpotBugs' setter-name mutability heuristic (reason recorded in the file).
- No `distributionSha256Sum` / wrapper validation for the Gradle wrapper (pre-existing).

### Still open from before

- **Dependabot `test-tooling-0181f48dd2`** (Testcontainers 2.0.5 / JUnit 6.1.3 / ArchUnit 1.5.0) —
  **skipped**: module coordinates fail to resolve in `compileTestJava`. A real migration, not a bump.
- **Push `main`** (above) · **H7** (seller not frozen on a sent quotation) · **item 3b** · **DNS
  provider** (rest of D-g) · the UNVERIFIED registry-import question below.
- `.tessl/` and `docs/architecture/pre-screening-answers.md` are untracked and not from any slice — leave them.

> **(Superseded by the 2026-09-16 section above** — application code has since changed, the F0 brainstorm
> is done, and F0a is merged; the paragraph below is kept as the record of 2026-09-14 morning.)

**Last updated:** 2026-09-14 — **No application code changed. The frontend is next, and it is
mid-brainstorm.** Three things happened, none of them backend code:

1. **D-g's name and hostname are settled.** The owner bought **`easycustomerrelationship.site`**
   (GoDaddy registrar and DNS, registered 2026-09-13, **expires 2027-09-13**, verified by RDAP at
   `rdap.radix.host`). Public links live at **`https://app.easycustomerrelationship.site`**; the apex is
   reserved for the marketing site. `application.yml` already reads `PUBLIC_BASE_URL`, so this is set per
   environment at SP2 and **no code changes**. Only the **DNS provider** is still open (roadmap
   recommends Cloudflare DNS with the registrar left at GoDaddy). Commit `185bf4c`, pushed. The owner was
   told to turn on auto-renew and 2FA: a lapsed name would send every `/public/q/{token}` in buyers'
   WhatsApp history to whoever re-registers it.
2. **The owner put the frontend (roadmap item 4) ahead of H7**, and approved its decomposition (D-d):
   **F0** foundation + auth + invite page → **F1** master data → **F2** the wedge → **F3** daily work + team;
   the import wizard and owner analytics are deferred (no backend API exists for either). ROADMAP Phase 1
   has the contents of each.
3. **Specialist reviewer agents exist**, because the owner is new to frontend and wants experienced eyes on
   specs and plans. Commit **`d019524`** — pushed together with this handoff update; **its CI run was not checked at the
   time of writing**, so confirm the `check` job (whose first step is now the registry check) is green.
   - Five read-only lenses in `.claude/agents/`: `frontend-review-{architecture,performance,security,a11y-i18n,testing}`.
     Checklists are paraphrased from vetted sources (Bulletproof React, TkDodo, react.dev, Vercel's
     guidelines, web.dev, IETF browser-apps BCP, OWASP, WCAG 2.2, Kent C. Dodds) — sources and licences in
     `docs/reviewers/frontend/protocol.md`, which also fixes severity, evidence rules and output format.
   - **`docs/reviewers/registry.md`** has one "Use when" sentence per reviewer plus the choosing rule, and is
     **imported into `CLAUDE.md`** so any agent requesting a review can pick specialists.
     `docs/reviewers/README.md` is the recipe for adding a reviewer in any domain.
   - **`scripts/check-reviewer-registry.sh`** fails when the registry and agent files disagree; it runs as the
     first step of CI's `check` job. All seven drift modes were made to fail on purpose (exit 1); the clean
     tree exits 0. actionlint and `SupplyChainWorkflowTest` (13/13) pass on the edited workflow.
   - `.gitignore` now tracks `.claude/agents/` while ignoring the rest of `.claude/`.

**Two things are UNVERIFIED — check them first.**
- **Does the `@docs/reviewers/registry.md` import reach agents?** A probe subagent in the session that
  created it saw `CLAUDE.md` but **not** the registry. The likely cause is that the session had loaded
  `CLAUDE.md` before the import was added — but that is not proven. In a fresh session, ask a throwaway
  subagent (no tools) whether it sees "Specialist Reviewer Registry". If not, inline the registry into
  `CLAUDE.md` and point the drift script at it.
- **Are the reviewer agents callable by name?** Claude Code loads agent definitions at session start. If
  `subagent_type: frontend-review-security` is rejected, dispatch a `general-purpose` agent with the agent
  file's body as its prompt — same review.
  > **Correction (2026-09-20, see the top section):** answered — yes, they are. Every review across
  > the F0b build that followed this one dispatched the five lenses by name with no fallback needed.
  > Left as-written above for the historical record; do not treat this as still an open question.
- (Resolved, for the record: the same probe reported that a subagent **does** have the Agent tool, so the
  registry's rule is "if you *cannot* dispatch, name the specialists", not "subagents never dispatch".)

## What the next agent should pick up (2026-09-14 — SUPERSEDED by the 2026-09-16 section at the top)

**UPDATE (later on 2026-09-14): the F0 brainstorm is DONE and the spec is written** —
[`specs/2026-09-14-f0-frontend-foundation-design.md`](specs/2026-09-14-f0-frontend-foundation-design.md).
The owner chose **B (signup page included)**, guarded by a `SIGNUP_ENABLED` switch that defaults open
everywhere; local-only; Web Locks + atomic rotation for the refresh race; English-only i18n plumbing; a
remembered workspace field on login; F0a (backend) merged before F0b (frontend). A **platform admin
role** was added to the roadmap as item 4a. **Next: the owner reviews the spec, then dispatch the five
frontend reviewers on it, verify findings, then `writing-plans`.** The text below is kept for the
backend-gap detail, all of which the spec's Part 2 re-verifies.

**1. (Superseded) Continue the F0 brainstorm** (`superpowers:brainstorming`, architectural path). Classified, context
explored, decomposition approved. **The pending question the owner has not yet answered:**

> **Does F0 include self-serve tenant signup?** `POST /api/v1/auth/signup` exists, but the design spec
> describes onboarding via an internal SOP "before self-serve launch".
> **A (recommended):** no signup page in F0 — login, logout, silent refresh, `/invite/{token}`; pilot
> tenants are created by the owner; keeps an abusable public form offline until it has protection.
> **B:** include a signup page.

Then keep asking one question at a time, propose approaches, present the design in sections, write the spec
to `docs/superpowers/specs/`, **dispatch the relevant specialist reviewers on the spec in parallel** (all five
plausibly apply to F0), verify their findings, and only then `writing-plans`.

**Backend gaps F0 must close — found while exploring, verified against the code:**
- **The refresh token travels in JSON.** `AuthResponse.refreshToken` and `RefreshRequest.refreshToken` in
  `docs/api/openapi.yaml`; there is **no cookie or CORS code** anywhere in `backend/src/main/java`. Spec §5
  requires an httpOnly Secure SameSite cookie. So F0 starts with a backend auth change (cookie issuance on
  login/refresh, cookie-read refresh, cookie clear on logout, CSRF defence-in-depth on those endpoints, a
  deliberate OpenAPI snapshot update).
- **458 responses in the contract are typed `'*/*'`, only 32 `application/json`.** `openapi-typescript`
  keys generated types by media type, so the generated client would be badly typed. Needs a springdoc
  `produces` fix and a regenerated snapshot.
- **No `/imports` API** and **no dashboard aggregates** (only `/follow-ups/summary`) — why the import wizard
  and owner analytics are deferred.
- **Same-origin deployment** (`app.` serves the SPA, `/api/*` routes to the backend) means no CORS and
  `SameSite=Strict` works; Vite's dev proxy mirrors it locally.
- **Rotating refresh tokens + multiple tabs** is a real race (both tabs present the same token) — check
  `iam/AuthService.refresh` for reuse detection before designing the client's refresh coordination.

**2. H7** (seller not frozen on a sent quotation) — deferred behind F0 by the owner, not dropped. The
2026-09-13 notes below still describe it exactly.

**3. Roadmap item 3b** and **the DNS provider** — unchanged.

**Housekeeping:** five Dependabot branches are open on `origin` (spotless 8.10.2, gradle wrapper 9.7.1,
jjwt 0.13.0, springdoc 3.1.1, a test-tooling group) and untriaged. springdoc may change `openapi.yaml`
(and F0 is about to change it deliberately); jjwt touches auth. Clear them before or alongside F0's backend
prep. `docs/architecture/pre-screening-answers.md` is still untracked and not from any slice — leave it.

---

**Previously:** 2026-09-13 (final) — **Wave 1.6 is MERGED. H4 is closed.** Fast-forwarded to `main`
at **`f81362b`** and pushed; the `wave-1.6-module-boundaries` branch is deleted. Thirteen commits
(`8f40b62`..`f81362b`) from `main` at `5ec82f1`. **`platform` now has ZERO outbound domain imports**,
down from 18 across three files, so the one shared library every future service consumes no longer
drags `sales`, `crm` and `tenant` along with it. **Baseline is now 626 tests (598 root + 28
`platform-primitives`), 0 failures, 0 errors**, `./gradlew clean check` green end to end; it was 604.
[spec](specs/2026-09-13-wave-1.6-module-boundaries-design.md) ·
[plan](plans/2026-09-13-wave-1.6-module-boundaries.md).

**CI is green on the merge — including the one axis that was never measured locally.** Run
`34774259997` on `f81362b`: `check`, `supply-chain` and `dependency-check` all green. That matters
specifically because `ModulithDocsSnapshotTest` compares generated C4 documentation byte-for-byte and
**fails `check`** rather than reporting, and until this run the bytes had only ever been compared on one
machine. Modulith emits `Rel(...)` edges and `Component(...)` declarations from unordered collections, so
the test canonicalizes both by sorting before comparing (challenge #79) — and that canonicalization now
demonstrably holds across macOS/JDK 25 locally and CI's Linux/JDK 25. **WA4's remaining risk is narrowed,
not eliminated:** one machine pair is not every machine pair, and a JDK patch bump is still untested.

**If it ever does fail on a new machine, the answer is NOT to delete the guard and NOT to keep
regenerating.** Diff the two generations after sorting every line. If the multisets match, it is another
unordered collection and the fix is to canonicalize that family too — which is exactly how
`Component(...)` came to be covered. If they genuinely differ, make the guard report-only and say so in
the spec. `./gradlew updateModulithDocs` regenerates the snapshot.

**The design was reshaped by a spike before any code was written, and that is the part worth reading.**
The Modulith evaluation doc's Appendix A admitted `verify()` had never actually been run. It was, on a
throwaway branch, and **three of that doc's positions did not survive**: the violation count was 446, not
a floor of 3; **declaring `platform` OPEN makes `verify()` pass with ZERO violations by suppressing all 12
cycles**, because every cycle routed through `platform` (isolated empirically — with `platform` still
OPEN, a planted `catalog → sales` cycle IS caught); and Part 6's prescribed remedy was unavailable,
because `VisibleFinder` returns `Optional<Customer>` and `Page<Quotation>` where the `AssignedWorkload`
precedent returns a `long`, so no port declared in `platform` could name them. **So `platform.visibility`
was DELETED rather than inverted, and the H4 gate is a hand-written ArchUnit direction rule — NOT
`verify()`, which cannot see these cycles.** Challenge #75.

**Four things to know before touching visibility again.**
1. **The rule is now derived independently in `crm.CustomerVisibility` and `sales.SalesVisibility`**, from
   the JWT role claim, with no shared method — deliberate, because post-split those are separate
   deployables that cannot share a decision. The cost is that a divergence fails **OPEN**.
2. **`sales.SalesVisibility.viaCustomer` delegates the Customer predicate to `crm`**, so the two
   `"SALES_EXEC"` literals are jointly load-bearing *asymmetrically*: if `sales` says restricted while
   `crm` says unrestricted, the restricted path degrades to a bare
   `EXISTS (SELECT id FROM customer WHERE id = q.customer_id)` and a `SALES_EXEC` sees **every** quotation
   whose customer row exists. Guarded two ways — both constants anchored to a shared test-scope value, and
   the behavioural contract tests — and **this is the trap waiting for H6**: implementing the
   `SALES_MANAGER` tier in one interpreter only reproduces it exactly.
3. **`viaCustomer` keeps an early return for the unrestricted case** (`if (unrestricted()) return
   unrestrictedSpec();`). The plan's code had dropped it. With zero FK constraints in the schema and
   `quotation.customer_id` a bare `UUID NOT NULL`, an orphaned quotation is representable, and
   always-`EXISTS` would have flipped it from visible to invisible for an OWNER. Pinned from both sides
   now (`SalesVisibilityPolicyTest`).
4. **`viaCustomer` is still a cross-service SQL join.** Moving it into `sales` fixed the package cycle and
   made it *look* local. Roadmap item 3b owns the real fix.

**The recurring lesson of this slice, five times over: a check that passes may be measuring nothing.**
`platform` OPEN suppressing the cycles it was adopted to catch; `clean check` failing fast at `:test` so
Spotless never ran; a fail-open test that survived mutation of the very line it pinned (it read a
quotation, whose path routes through crm's own fail-open default); an arch rule holding a deleted class's
FQN as a *string* constant, silently matching nothing; and `Documenter` "proven" byte-stable by a spike
that ran both generations in one JVM. Every gate added here therefore carries a non-vacuity assertion, and
every guard was verified by making it fail on purpose. **Challenges #75–79** record them.

**Two items went on the board, neither fixed here.** **H7** is a live correctness bug of H1's exact class —
the seller is not frozen on a sent quotation, and `interState` is computed from the frozen `placeOfSupply`
against the **live** tenant `stateCode`, so changing registered state flips an already-`SENT` quotation
between CGST/SGST and IGST on re-render, through the buyer's share link. It is small and it mis-states tax
on a document a customer holds; it arguably outranks most of the roadmap. **Item 3b** is the cross-service
data access design SP8 assumed existed: Wave 1.6 *froze* Layer 2 behind a register with a named exit per
edge, it did not resolve it.

## What the next agent should pick up (2026-09-13 — SUPERSEDED by the 2026-09-14 section above)

*The owner has since put the frontend ahead of H7 and settled the domain name and hostname. Kept for the detail on H7 and 3b.*

**Roadmap items 1, 2 and 3 are all done and merged.** In the order I would take them:

1. **H7 — the seller is not frozen on a sent quotation.** Small, and a live correctness bug of H1's exact
   class. `QuotationPdfService` reads the buyer from the frozen `BuyerSnapshot` but the seller live from
   `tenant`, two lines apart — and computes `interState` from the frozen `placeOfSupply` against the
   **live** tenant `stateCode`, so a tenant changing registered state flips an already-`SENT` quotation
   between CGST/SGST and IGST rows on re-render, through a share link the buyer already holds. Needs its
   own freeze decision: which seller fields freeze, and at `send()` or at version creation. Challenge #69's
   test decides it — *was anything already frozen derived from this field?* For `stateCode`, yes. The
   buyer-snapshot spec never mentions the seller, so there is no prior art to inherit.
2. ~~**Settle D-g and register the domain.**~~ **Name bought 2026-09-13 (after this handoff was
   written): `easycustomerrelationship.site`, at GoDaddy, expires 2027-09-13, on GoDaddy DNS.** **Public-link
   hostname settled 2026-09-14: `https://app.easycustomerrelationship.site`**, the value
   `PUBLIC_BASE_URL` takes in every deployed environment, with the apex reserved for the marketing site.
   Only the DNS provider is still open. Also turn on auto-renew, since a lapsed name hands every
   share token in WhatsApp history to whoever registers it next. The original reasoning follows: `easycrm.public-base-url` still defaults to `http://localhost:8080`, and it feeds
   `/public/q/{token}` and `/invite/{token}`, both of which end up in other people's WhatsApp history.
3. **Roadmap item 3b — cross-service data access (Layer 2).** Wave 1.6 *froze* this; it did not fix it.
   Nine cross-domain repository reads are registered in `CrossDomainRepositoryArchTest.ALLOWED`, each with
   its exit already decided by the service-scope doc (synchronous internal call / freeze / no action). The
   sharp one is `viaCustomer`: it is a cross-service SQL join that Wave 1.6 relocated into `sales`, where it
   now *looks* local. SP8 assumed this design existed. It does not.
4. **Item 4 — the frontend**, which still wants a decomposition pass (D-d) before a spec.

**If you are about to add a gate, read challenges #75–79 first.** This slice hit five separate checks that
passed while measuring nothing. The habit that caught them: never trust a green you have not made go red on
purpose, and give every rule a non-vacuity assertion beside it.

**Everything below this line predates the Wave 1.6 build and is unchanged.**

---

**Previously:** 2026-09-13 (first pass) — **`main` is pushed and Wave 1.6 is designed, not built.** Two things
happened this session and neither is code.

**First, `main` was pushed** — `ac2fc63..b858429`, 14 commits — and **Wave 1.5's scans ran in CI for
the first time**: run `34713136667`, green on all three jobs (`check`, `supply-chain`,
`dependency-check`). Every Wave 1.5 claim used to travel with "never executed in CI." That caveat is
discharged. Dependabot also woke on the push and opened its first update runs.

**Second, Wave 1.6 was designed, and a spike reshaped it before a line was written** —
[`specs/2026-09-13-wave-1.6-module-boundaries-design.md`](specs/2026-09-13-wave-1.6-module-boundaries-design.md).
The Modulith evaluation doc's Appendix A said `verify()` had never actually been run; it was, on a
throwaway branch, and **three of that doc's positions did not survive**. (1) The violation count is
**446, not a floor of 3** — 12 cycles plus 434 encapsulation findings. (2) **Declaring `platform`
OPEN makes `verify()` pass with ZERO violations, suppressing all 12 cycles**, because every cycle
routes through `platform` — so M2 (take `verify()` as the gate) and M4 (declare `platform` OPEN)
conflict, and adopting Modulith the documented way would have produced a green gate certifying the
graph that blocks SP8. Isolated, not inferred: with `platform` still OPEN a planted
`catalog → sales` cycle **is** caught. **Challenge #75.** (3) Part 6's remedy for MF1 — "a port in
`platform`, following `AssignedWorkload`" — **is not available**, because `VisibleFinder` returns
`Optional<Customer>` and `Page<Quotation>` where `AssignedWorkload` returns a `long`; the domain
aggregates *are* the return values, so no port in `platform` can name them. `platform.visibility` is
therefore **deleted**, not inverted, and the H4 gate is a hand-written ArchUnit direction rule that
OPEN cannot suppress. The evaluation doc now carries a Part 7 recording all of this.

**Two items went onto the board, both found by designing rather than building.** **H7 is a live
correctness bug of H1's exact class:** `QuotationPdfService` reads the buyer from the frozen
`BuyerSnapshot` but the seller **live** from `tenant`, two lines apart — and computes `interState`
from the frozen `placeOfSupply` against the **live** tenant `stateCode`, so a tenant changing
registered state flips an already-`SENT` quotation between CGST/SGST and IGST rows on re-render,
through the share link the buyer holds. `send()` guards the customer's divergence with a 422;
nothing guards the seller's. The buyer-snapshot spec never mentions the seller. **It is recorded,
not fixed** — it needs its own freeze decision (challenge #69's test: was anything already frozen
*derived* from this field? For `stateCode`, yes). And **roadmap item 3b**, a cross-service data
access design that does not exist and that SP8 assumed: Wave 1.6 closes Layer 1 (package
acyclicity) but can only *freeze* Layer 2 (four direct cross-domain repository reads, plus the
`viaCustomer` cross-service SQL join) behind a second ArchUnit rule whose allowlist is a register
with a named exit per edge. Layer 3 needs nothing — **zero FK constraints in all 34 migrations**.

**Next step: `writing-plans` for the Wave 1.6 spec.** Nothing is in flight; the tree is clean and
`main` == `origin/main` at `b858429`, 604 tests. The one uncommitted file is
`docs/architecture/pre-screening-answers.md`, untracked and not from this work.

**Everything below predates 2026-09-13 and is unchanged.**

---

**Last updated:** 2026-09-07 — **The buyer snapshot is done.** Branch `buyer-snapshot`, tip
`c24ae5e`, branched from `main` at `82733f1`; six task commits (`a3d89f1`..`c24ae5e`) plus this
docs commit. **Sub-project 1 / F11 / roadmap item 1 / hazard H1 — the repo's only live correctness
bug — is closed.** `QuotationVersion` now carries a `BuyerSnapshot` (`@Embeddable`, three flat
columns added by `V34`) frozen at `send()`, and `QuotationPdfService` renders from it:
`import com.easycrm.crm.Customer` is **deleted** from the render path, which is simultaneously the
bug fix and D10's architectural payoff — the last live `crm` read is gone from the most exposed,
most cached route in the system (`/public/q/{token}`). The slice started, per the repo's practice,
with a deliberately failing test (`a3d89f1`) that renders a `SENT` quotation, edits the customer,
re-renders, and asserts the bytes are identical. **The baseline is now 591 tests, 0 failures, 0
errors** (563 root + 28 `platform-primitives`), verified 2026-09-07 by `./gradlew clean check` from
clean; the previous baseline was 586.

**Three decisions in it are worth knowing before touching quotations again.** The buyer freezes at
`send()`, not at version creation like everything else on the version, because a draft that sits
for two weeks while someone corrects a typo'd GSTIN must send the *corrected* one — but
`placeOfSupply` still freezes at creation, because the per-line CGST/SGST/IGST were computed
against it, so `send()` now **rejects with 422** when the customer's `stateCode` no longer matches
the frozen `placeOfSupply` (the escape is a *new* quotation, not `revise()`, which copies the stale
split forward). D10's `buyer_snapshot JSONB` column was **reversed** to three flat columns, matching
`QuotationItem`'s `name_snapshot`/`hsn_snapshot`/`uom_snapshot` precedent and keeping
`ddl-auto: validate` able to see them. And the service-scope doc's Appendix A fold of **S2** (freeze
the primary contact in the same migration) was **declined and rescheduled** — the `wa.me` link is
built at share time and is a routing address, not a document. All three are recorded with reasons in
[`specs/2026-09-07-buyer-snapshot-design.md`](specs/2026-09-07-buyer-snapshot-design.md); §7 is the
S2 one. **No API surface moved:** `QuotationVersionResponse` and `docs/api/openapi.yaml` are
untouched and `OpenApiSnapshotTest` is green as written.

**This slice owed two challenge-log entries and paid both.** **Challenge 68** — `V34` is the
repo's *first* DML migration (`V1`–`V33` contain none), and under `V26`'s `FORCE ROW LEVEL
SECURITY` a plain cross-tenant backfill `UPDATE` run by Flyway as `easycrm_owner` matches zero
rows, commits, and reports success; worse, the obvious post-check counts unfrozen rows through the
same absent-GUC policy and passes vacuously, so the guard fails exactly the way the thing it guards
fails. The backfill loops over `tenant`, `set_config`s per tenant, and puts the `RAISE EXCEPTION`
**inside** the loop. Every future DML migration in this repo follows that shape. **Challenge 69** —
why two fields on the same frozen document have different correct freeze points: what decides is
whether anything already frozen was *derived* from the field. **One test gap, stated rather than
papered over:** Testcontainers starts empty, so the backfill loop iterates zero tenants under test
and no test in this repo can prove it works — its only real check is the in-migration `RAISE`.

**Before it, members management was merged to `main` as `f2465b8`.** Eight task commits
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
it, the `ConflictException` structured-fields addition, and migration `V33`. **The baseline it
left on `main` was 586 tests, 0 failures, 0 errors** (558 root + 28 `platform-primitives`),
verified 2026-09-02 by `./gradlew clean check` from a clean state, with CI green on the tip — since
superseded by the 591 above.

**If you came here expecting 561, read this before you stop and reconcile.** The 561 figure was
measured *on the branch*, and the branch later merged `main` into itself (`0afe2f8`) to pick up
the OpenAPI slice — so 561 never included OpenAPI's 25 tests and was obsolete the moment that
merge happened. The arithmetic closes exactly: 519 (pre-OpenAPI) + 25 (OpenAPI) + 42 (members
management, of which 37 root and 5 in `platform-primitives`) = **586**. Nothing is missing and
nothing is double-counted. The general trap, worth carrying: **a test count measured on a branch
that later merges `main` into itself is not comparable to the post-merge total**, and §0's
"if that number differs, stop and reconcile" will fire on a perfectly healthy build unless the
merged number is written down. Restate the count *after* the merge, not before.

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

### Nothing is in flight. Wave 1.5 — supply chain — is merged into `main`, and `main` is unpushed

**Roadmap item 2 is done.** The `supply-chain` branch merged into `main` fast-forward on
2026-09-12 and was deleted; there is no branch to resume and no work half-finished. `main` is at
`7f6a700`, verified green from clean at **604 tests, 0 failures, 0 errors**.

> ### ⚠ `origin/main` is at `ac2fc63`. Thirteen commits are unpushed, and CI has seen none of them.
>
> This is the same shape as the warning the buyer-snapshot slice left here, and it matters more
> this time: **the three blocking scans this slice added have only ever run on one macOS laptop.**
> `gitleaks`, `actionlint` and `squawk` were each proven able to fail locally, but no GitHub
> Actions runner has executed the `supply-chain` job even once. The first push fires CI across all
> thirteen commits at once, and it is the real debut of every gate in this slice.
>
> Pushing is an outward-facing act and was deliberately left to the user. **Discuss it before
> doing it.**

**Verify the position directly rather than trusting the last thing written here** — this file has
stated it wrongly twice:

```
$ git rev-parse --short main
7f6a700
$ git rev-parse --short origin/main
ac2fc63
$ git rev-list --count origin/main..main
13
```

(`git rev-parse --short main origin/main` as a single combined invocation fails outright on this
git — `fatal: Needed a single revision` — so resolve each ref separately, as above.)

The thirteen include this slice's own design spec (`5191cf6`) and implementation plan (`3b00188`),
which were committed to `main` before the branch was cut, plus the eleven below. The seven
implementation commits:

| Commit | What it did |
|---|---|
| `5053d42` | ci: scan for committed secrets (gitleaks) |
| `a6edff6` | fix(ci): gitleaks — default ruleset is blind to `${VAR:default}`, add a rule that isn't |
| `78a01e5` | ci: lint the workflow and its shell (actionlint) |
| `81aa631` | ci: lint new migrations for unsafe DDL (squawk) |
| `6c2728d` | build: open weekly dependency update PRs (Dependabot) |
| `25b6e5c` | ci: scan dependencies for published CVEs (OWASP Dependency-Check) |
| `0f139ce` | fix: correct the false NVD-key diagnostic and make the check blank-safe |

…followed by four documentation and hardening commits:

| Commit | What it did |
|---|---|
| `c2df8fe` | docs: record the supply-chain slice |
| `36fd495` | docs: correct the main/origin sync claim in the handoff and roadmap |
| `3b9d1e6` | docs: stop presenting a command that never ran as a transcript |
| `7f6a700` | fix(ci): make the supply-chain scans survivable and the guard test load-bearing |

**`7f6a700` is the one to read if you only read one.** The whole-branch review found the guard
test did not guard: `if: false` on the `supply-chain` job disabled every scan while all eight
assertions passed, `|| true` on a scan body swallowed its exit status undetected, and deleting
`fetch-depth: 0` silently collapsed gitleaks to a single commit. It also found `squawk` would have
failed the *next* migration PR on 267 pre-existing findings across rules this repo has always
violated by choice — a gate whose cheapest escape is deletion. `backend/squawk.toml` and five more
assertions came out of that.

**604 tests, 0 failures, 0 errors** (576 root + 28 `platform-primitives`), verified by
`./gradlew clean check` from clean after the final fix wave — 591 before this slice, plus 13 in
`SupplyChainWorkflowTest` (8 from the implementation commits, 5 more from the fix wave: the `if:`
guard, the exit-status-swallowing guard, the `fetch-depth: 0` guard, the moving-tag sweep, and a
continue-on-error guard that checks the key is absent rather than merely not `true`).

**Two things this slice recorded as unverified, honestly, rather than guessed:**
- **Dependabot's coverage of `buildSrc`.** The `gh api` check for this repo's Dependabot alerts
  returned HTTP 403 ("Dependabot alerts are disabled for this repository"), and no scan cycle has
  run yet. Whether Dependabot's weekly update PRs actually reach `buildSrc`'s own dependencies (as
  opposed to just the root and `backend` catalogs) is **unverified — not assumed either way.**
- **The Dependency-Check finding count.** There is no local NVD API key, and plugin 13.0.0
  hard-fails in ~4 seconds with `NvdApiException: Invalid API Key` rather than running slowly, so
  no finding count exists anywhere yet. The first real CI run, with a valid `NVD_API_KEY` secret in
  place, produces it. **Acting on whatever it reports is explicitly the next decision — it is not
  something this slice did.**

**Four deferred, minor findings, recorded so they aren't silently lost:**
- The custom gitleaks rule's keyword list (`password|secret|token|credential|creds|passwd|
  api[-_]?key`) does not cover a bare `key` property such as `signing-key:`. Nothing in the repo
  uses that shape today.
- The design spec preferred a checksummed release binary over `npm install -g` for squawk; the
  implementation pins `squawk-cli@2.65.0` instead. The reproducibility concern is met, but the
  stated preference was never revisited.
- **Nothing updates the three tool pins this slice added.** Dependabot covers the `gradle` and
  `github-actions` ecosystems; `zricethezav/gitleaks:v8.30.1`, `rhysd/actionlint:1.7.12` and
  `squawk-cli@2.65.0` all live inside `run:` blocks, which no Dependabot ecosystem reads. Same for
  `tufin/oasdiff:v1.31.0`, pinned in the fix wave. They are exact, and they will rot silently —
  `SupplyChainWorkflowTest` asserts the exact coordinates, so bumping one means editing the test
  too, which is the only reminder that exists. Bump them by hand when touching this workflow.
- `dependency-check` is a non-blocking job, so a failed run (a missing or invalid `NVD_API_KEY`
  secret) leaves the overall workflow green. Its summary step is honest about that — it writes "No
  report produced — see the step log" and never fabricates a zero-findings result — but nobody is
  prompted to open that job. PR-level visibility is a follow-up worth a roadmap note.

**A pattern worth naming, the most transferable thing this slice produced: six separate written
claims turned out not to match reality, and every one was caught by someone running the claim
rather than reading it.**

1. The `.gitleaks.toml` allowlist comment claimed a hardcoded value on those lines would still
   fail the scan. False — gitleaks is structurally blind inside `${VAR:default}` (challenge #71).
2. The Dependency-Check log message diagnosed a slow, rate-limited scan where the real failure is
   a hard `NvdApiException` in ~4 seconds.
3. That same message, after being corrected, still fired at **configuration time on every Gradle
   invocation** — `./gradlew help` printed it twice with no scan in the task graph. The wording was
   fixed; the trigger was not, until the final fix wave.
4. This very section, in an earlier pass, asserted `main` was "fully in sync with `origin/main`".
   It was not, by two commits.
5. The correction to (4) shipped a **fabricated transcript** — it showed
   `git rev-parse --short main origin/main` printing two SHAs, in the paragraph warning about
   untested claims. That command errors and prints nothing.
6. **Still open:** challenge #74's Lesson calls the swept-up `tufin/oasdiff:latest` "a two-year-old
   `:latest`". It was introduced in `85a4b69` on 2026-09-02 — ten days before that sentence was
   written. Left unfixed deliberately rather than silently: it was found after the fix wave closed,
   and it is a one-line correction whenever someone next touches that file.

Not one of these was caught by re-reading the claim. Treat any confident statement about tool
behaviour or repo state in this codebase as a claim to verify, not a fact to relay — and be most
suspicious of the ones that sound reassuring.

Engineering-challenges log entries **70–74** came out of this slice; read them before touching
`.github/workflows/ci.yml`, `.gitleaks.toml` or `backend/squawk.toml` again. **#73** (a migration
linter whose findings are all unactionable is a step someone will delete) and **#74** (a test that
guards a CI scan must assert on how scans actually get disabled, not on whether they exist) are the
two with the widest application beyond this slice.

### So what do I do next?

**Three things are owed before new feature work, and the first two are decisions, not slices.**

1. **Push, or decide not to.** Thirteen commits, CI has run none of them, and the scans this slice
   added have never executed on a runner. Raise it with the user; do not push unasked.
2. **Two follow-ups that only the first CI run can settle** — enable Dependabot alerts on the repo
   (the `gh api` probe returns 403 today, which is why `buildSrc` coverage is unverified), and
   watch the first `dependency-check` job. It is non-blocking, so if `NVD_API_KEY` is wrong the job
   fails while the workflow stays green; the summary says "No report produced", but only to someone
   who opens it. That run also produces the CVE count that does not exist yet.
3. **Then roadmap item 3 — Wave 1.6, Modulith plus the H4 cycle fix.** Small, unblocked, and it
   settles decision **D-e** (packages or Gradle modules as the boundary source of truth) inside the
   slice. Two version traps are already recorded in
   `../architecture/2026-09-03-spring-modulith-evaluation.md`: Modulith 2.1.1 is the Boot 4 line
   (1.x is Boot 3), and Maven Central's *search API* is stale for that coordinate — read
   `repo1.maven.org`'s `maven-metadata.xml` instead. `spring-modulith-core` also pulls ArchUnit
   1.4.2 at compile scope while this repo pins 1.4.1 deliberately; Gradle resolves highest-wins, so
   that bump happens silently unless it is made explicitly in the catalog with a comment.

**Running alongside all of it: D-g, the domain name.** It is not a build item — a decision plus a
registrar checkout — and it is the one open thing that gets more expensive the longer it waits,
because every public link minted before it is settled is a link that later has to be stranded or
permanently redirected. Since the 2026-09-12 reprioritisation it gates **item 4, the frontend**,
not the marketing site. The RDAP results in `../ROADMAP.md` track H were checked on 2026-09-05 and
`eazycrm.in` expires 2026-12-15 — re-check before deciding.

**Two more parked findings, real but not blocking:**
- `backend/squawk.toml`'s `excluded_paths` disables **all** rules on the five frozen migration
  files it names, while the comment above it reads as though only the listed rules are excluded.
  Harmless — those files cannot change without breaking a Flyway checksum — but the comment is
  narrower than the mechanism.
- A PR touching **only** one of those five excluded files makes squawk exit 1 with
  `Failed to find files for provided patterns`. That reddens the job in the safe direction, but the
  message has nothing to do with the real problem (a Flyway checksum violation).

### Before that: buyer snapshot (SP1) — done, merged, and pushed

Sub-project 1 — the buyer snapshot — is **done, merged, and pushed**. The `buyer-snapshot` branch
fast-forwarded into `main` on 2026-09-08 and was deleted; `main` reached `4baa4b4` at that point,
verified green from clean with **591 tests, 0 failures, 0 errors** (563 root + 28
`platform-primitives`), up from the 586 baseline. Every task was reviewed clean, the whole-branch
review approved it for merge, and the merged result was re-verified before the branch was deleted.
That merge and everything since (through `ac2fc63`) is now pushed — see above.

Eight commits, in the order they were built:

| Commit | What it did |
|---|---|
| `a3d89f1` | The deliberately failing test: send a quotation, render, edit the customer's `businessName`/`gstin`/`billingAddress`, render again, assert the bytes are identical. This is F11, reproduced |
| `0452cda` | `V34` — three nullable columns on `quotation_version` (`buyer_business_name`, `buyer_gstin`, `buyer_billing_address`), widths mirroring `customer` exactly, plus the per-tenant backfill (challenge 68) |
| `e19fded` | `BuyerSnapshot` `@Embeddable` and the `@Embedded` mapping on `QuotationVersion`; `annotations-reference.md` rows for `@Embeddable` and `@Embedded` |
| `b29fec7` | `QuotationService.send()` freezes the buyer |
| `45d43e0` | `send()` rejects with **422** when the customer's `stateCode` no longer matches the version's frozen `placeOfSupply` |
| `c24ae5e` | `QuotationPdfService` renders from the snapshot; `import com.easycrm.crm.Customer` deleted from the render path |
| `09359bd` | The documentation the working agreements owed: challenges **68** and **69**, H1 struck from the roadmap, F11 closed in all four architecture docs that carried it open, S2 recorded as declined |
| `4baa4b4` | Post-review fix wave: renamed a test that over-claimed (its "draft has none" half asserted a pre-existing rule, not the snapshot — challenge #33's lesson), and de-staled one S2 row |

The columns stay **nullable** deliberately: a `DRAFT` genuinely has no buyer yet, so the invariant
is *not-null when `SENT`*, enforced in code, and a `SENT` version with a null snapshot throws an
`IllegalStateException` naming the invariant rather than rendering a blank buyer block. `revise()`,
`create()` and `ShareLinkService` are all untouched — a revision is a `DRAFT` with no snapshot that
freezes its own buyer at its own `send()`, which is exactly how a corrected GSTIN reaches a
revision.

**One open follow-up this slice created, deliberately left unfixed — the zombie draft.** Found by
the whole-branch review, judged not merge-blocking, and surfaced to the user rather than silently
fixed. A `DRAFT` quotation whose customer's `stateCode` is *corrected* becomes permanently
un-actionable: `send()` 422s (the new guard), and `reject()`, `expire()` and `revise()` all require
`SENT`, and `QuotationController` has **no delete mapping**. The draft sits in the list forever.
Concretely: a clerk types state `27` instead of `29`, raises a draft, notices the GSTIN's first two
digits disagree, fixes the customer — that draft is now a zombie.

The guard is still strictly better than what it replaced: `replaceItems()` already recomputed
per-line tax from the customer's *current* `stateCode` against a creation-time `placeOfSupply`, so
before this slice that draft would have sent happily as a self-contradictory GST document. Refusing
is right; only the exit is missing. **The fix is a new feature — either a draft-discard route, or
re-deriving `placeOfSupply` and rebuilding the tax split when the version is still `DRAFT`** (which
would make the guard unreachable for drafts and leave it guarding only the revise-inherits-stale-
split case). Both are out of scope for a slice whose spec did not ask for them. Raise it with the
user before building either.

### The roadmap was reprioritised on 2026-09-12 — read it before proposing work

At the user's direction, **"Domain + static marketing site" moved from priority 2 to priority 16**,
and everything between renumbered up by one. The reasoning that had it at 2 (the only item that
acquires a customer) still holds and was outweighed: there is no product to send an acquired
customer to until the frontend ships.

**The naming decision did not move with it, and this is the part to not get wrong.** D-g — the
domain name and DNS provider — is now the gate in front of the **frontend** rather than in front of
the site. `easycrm.public-base-url` still defaults to `http://localhost:8080` and feeds both
`/public/q/{token}` and `/invite/{token}`, which get pasted into WhatsApp and stay in other
people's chat history. Register the name early even though the site waits; the redirect you would
otherwise owe is permanent. Phase 0 item 4 and the Part 6 sequencing traps both say so explicitly.

**So the next items are: 2 — Wave 1.5 (supply chain), 3 — Wave 1.6 (Modulith + the H4 cycle fix),
and settling D-g,** which is a decision plus a registrar checkout rather than a build item. Then 4,
the frontend, with its own session and a decomposition pass. Note the domain table in track H was
checked by RDAP on 2026-09-05 — **`eazycrm.in` expires 2026-12-15, so re-check before deciding.**

**What the backfill can and cannot recover.** For a `SENT` version whose customer was already
edited, the true historical buyer was never stored and is unrecoverable. The backfill writes the
*current* customer — which is precisely what that version renders today — so it changes no rendered
output. It freezes the status quo and stops it moving again. That is the honest ceiling on this
fix, and it is why the fix was urgent rather than merely tidy.

### Before that: nothing was in flight

**Docs-only session, 2026-09-05 — a roadmap now sits above this file.** Committed as `4df665f`.
No code changed; the baseline is still 586 tests on `main`. Four files: **`docs/ROADMAP.md`** (new — the programme-level
plan: eight tracks, six phases, a ranked 16-item priority list, sequencing traps, and the open
decisions that gate work), **`docs/architecture/2026-09-03-spring-modulith-evaluation.md`** (new —
adopt Modulith's structure-verification half as Wave 1.6, decline the events half; M1–M7, MF1–MF4),
plus §8 and `CLAUDE.md` updated to point at both.

**Two findings out of it that change what to do next, both in §8's ranking below:**

1. ~~**Sub-project 1, the buyer snapshot (F11), has never been done and is a live correctness
   bug.**~~ **RESOLVED 2026-09-07 — `c24ae5e`.** It was verified open on 2026-09-05
   (`QuotationVersion` carried no buyer fields, so `QuotationPdfService` read
   `businessName`/`gstin`/`billingAddress` live from `customer` at render time); it was made item 1
   on the strength of that, and it is now done. H1 is struck from `docs/ROADMAP.md` §1.5 and F11 is
   closed in all three architecture handoffs. See the top of this section for the branch. **Item 2
   below is now the head of the queue.**
2. **`platform` imports `crm`, `sales` and `tenant` — three dependency cycles**, from
   `VisibleFinder`/`VisibilityPolicy` (2026-08-29) and `TenantJobRunner` (2026-08-31). Both landed
   *after* the service-scope doc was last touched (2026-08-24), so S1–S10 could not have seen them,
   and none of the four ArchUnit tests guards package dependencies. Under the split every service
   would inherit `sales` entities from the module meant to be shared by all five.

**Domain names: all four obvious candidates are taken** (RDAP, 2026-09-05). `eazycrm.com` (Dynadot,
on Afternic nameservers, so listed for sale), `easycrm.com` (parked on a GoDaddy lander),
`eazycrm.in` (expires 2026-12-15), `easycrm.in` (renewed to 2031). `easycrm.co.in` and
`eazycrm.co.in` are also gone. Available: `geteasycrm.com`, `tryeasycrm.com`, `useeasycrm.com`,
`easycrmindia.com`, `easycrm.net`. Costs and the full table are in `docs/ROADMAP.md` track H; the
naming decision is open (D-g) and gates roadmap item 2 *(written 2026-09-05; since the 2026-09-12
reprioritisation D-g gates item 4, the frontend — see §0)*, **and the domain gates every durable
public URL the product mints** — `easycrm.public-base-url` feeds both the `/public/q/{token}` share link
and the `/invite/{token}` accept link, and both get pasted into WhatsApp.

**Nothing challenge-worthy was solved this session** — the cycles are a *finding*, recorded as MF1/
MF2 in the evaluation. The challenge-log entry is owed when the port inversion actually lands.


**`main` now carries both of the branches that were running concurrently, and neither remains
open.** Members management (this file's top entry) was merged after the OpenAPI contract slice;
its 16 commits were built in a locked worktree by a separate session, reviewed task-by-task and
then whole-branch, and verified green before merging. `openapi-contract` was merged as `bead2f8`
and its branch deleted.

**Housekeeping pass done 2026-09-02, after the members-management merge.** The state below is
verified, not assumed:

- **`main` is at `28f9ac8`, fully pushed, and `origin/main` is level with it.** CI is **green**
  on that tip — run `33611759381` (push, 2m27s), and `33611195670` on the merge commit
  `5d7c3c6` before it — all four gates across both Gradle projects plus the oasdiff step.
  (This line named `5d7c3c6` until the housekeeping commit itself landed on top of it. A
  recorded tip goes stale the moment anything else commits, so **treat the SHA as "where this
  was written", not as a claim about now** — `git log -1` and `gh run list --branch main` are
  the live answer, and both were green when this was written.)
- **Local baseline re-verified from clean: 586 tests, 0 failures, 0 errors.** See the header for
  why this is not the 561 the previous entry claimed.
- **The members-management worktree and its branch are gone.** `.claude/worktrees/members-management`
  was still on disk (every earlier slice deleted its workspace at merge; this one did not), holding
  branch `worktree-members-management` at `0afe2f8`. Both were verified clean — no uncommitted
  changes, no stashes, nothing unmerged into `main` — and removed. Its `.superpowers/sdd` ledger
  was **already empty** (a lone `.gitignore`), so no reasoning was lost that this file, the spec and
  challenges #65–#67 do not already carry.
- **One item is deliberately left open:** the remote branch `origin/build-hygiene`
  (tip `ee4641c`) still exists. It is fully merged into `main` and safe to delete with
  `git push origin --delete build-hygiene`; the automated session that ran this pass had remote
  branch deletion blocked by a permission classifier, so it is left for a human. This is the same
  loose end §0 has carried since the build-hygiene slice — it is now the *only* one.

**A trap this pass walked into, worth one line so the next agent does not repeat it:** a stale
`origin/main` remote-tracking ref made `git log origin/main..main` report 21 unpushed commits
when in fact everything was pushed. **`git fetch` before drawing any conclusion from a
`origin/<branch>..<branch>` range** — the tracking ref is a local cache, not the remote's state,
and an unfetched one produces a confident, entirely wrong answer.

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
the branch went away. **`main` is the baseline for new work and is fully pushed** — when this
paragraph was written that meant `b239f5f`; members management has since landed on top, and the
current tip is `5d7c3c6` with `origin/main` level with it (see the housekeeping bullet above).
The commit range started at `4a1848b`: two docs
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
`origin/build-hygiene` (tip `ee4641c`) **still exists as of 2026-09-02** — confirmed fully merged
into `main`, so `git push origin --delete build-hygiene` is safe and is the one open housekeeping
item; and the four intermediate CI-testing commits inside the merge are deliberate — they are the
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
   green local run and a green CI run assert the same thing. On `main` at `5d7c3c6` this is
   **586 tests, 0 failures, 0 errors** (558 root + 28 `platform-primitives`), verified from clean
   on 2026-09-02 — up from 544 before members management, 519 before the OpenAPI slice and 464
   before the invitations slice.
   Gradle prints no total for a multi-project build, so count it yourself:

   ```bash
   cd backend && ./gradlew clean check
   find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + \
     | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
   find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'failures="[0-9]*"' {} + \
     | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "failures:", s}'
   ```

   If that number differs, stop and reconcile before writing code — everything below assumes it.
   **Counting only the root project's XML files produces a phantom 28-test gap** — `find .
   -path './build/test-results/test/*.xml'` alone reports 558, not 586, and this tripped an
   implementer on an earlier branch. (The gap was 23 until members management added 5 tests to
   `platform-primitives`; it is the size of that module's suite, so expect it to keep moving.) The unqualified `find . -path '*/build/test-results/test/*.xml'`
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

- **Latest code work: Wave 1.5, supply chain** — merged at `7f6a700`, 604 tests. Four scanners, one
  guard test, and one decision that shapes all of it: **none of the scanners is wired into
  `./gradlew check`.** They are Go/Rust/npm binaries, and wrapping them in Exec tasks would make
  every local build slower and demand three installs. The cost of that choice — a green local run
  no longer implying a green CI — is bounded by
  `backend/src/test/java/com/easycrm/platform/supplychain/SupplyChainWorkflowTest.java`, which
  parses the real `.github/workflows/ci.yml` and fails `check` if a scan is deleted **or weakened**.
  Weakened is the operative word: its 13 assertions cover `if:` (a scan skipped by condition),
  `continue-on-error` asserted *absent* rather than merely not `true` (because `${{ true }}` parses
  as a String), `|| true` / `--exit-code 0` swallowing a scan's exit status, a shallow
  `fetch-depth` silently reducing gitleaks to one commit, and any floating image tag. Add a scan
  step and you must add its assertions too — that is the intended friction.
  - **`.gitleaks.toml`** carries a custom rule, `easycrm-default-credential`, because gitleaks'
    default ruleset cannot see inside Spring's `${VAR:default}` syntax at all — its capture group
    cannot begin at `$`. The three dev defaults in `application.yml` are allowlisted against that
    rule specifically, so a real credential put in the same position still fails. Challenge #71.
  - **`backend/squawk.toml`** sets `assume_in_transaction = true` — a fact about how Flyway runs
    migrations, not a relaxation — which removes 124 of 267 findings on its own, and excludes three
    rules with written reasons. The CI step lints only migrations **changed** in the push or PR,
    because Flyway checksums make the existing 34 immutable. Challenge #73.
  - **Dependency-Check reports, it does not block** (spec D1), in both places: `continue-on-error`
    on the job and `failBuildOnCVSS = 11f` above the maximum possible score. Its flip trigger is
    branch protection, roadmap item 8.
  - Design: [`specs/2026-09-12-supply-chain-design.md`](specs/2026-09-12-supply-chain-design.md).
    Plan: [`plans/2026-09-12-supply-chain.md`](plans/2026-09-12-supply-chain.md).

- **The buyer snapshot** — branch `buyer-snapshot`, tip `c24ae5e`, off `main` at
  `82733f1`, **merged fast-forward 2026-09-08 and pushed; the branch is gone.** It was green at 591
  tests, which was the baseline until Wave 1.5 took it to 604 (see §0). Six task commits
  (`a3d89f1`..`c24ae5e`), TDD, one per task, each reviewed clean. Closes F11 / hazard H1 /
  sub-project 1 — the repo's only live correctness bug — and, in the same edit, D10's architectural
  prerequisite for extracting `document-svc`.

  **Schema.** `V34__quotation_version_buyer_snapshot.sql` adds `buyer_business_name VARCHAR(255)`,
  `buyer_gstin VARCHAR(15)` and `buyer_billing_address VARCHAR(512)` to `quotation_version` —
  widths mirroring `customer` exactly, because a snapshot narrower than its source truncates on
  freeze. All three nullable (B6: a `DRAFT` has no buyer yet; a `NOT NULL` placeholder would make
  "unset" indistinguishable from a real value). **Flat columns, not the `buyer_snapshot JSONB` D10
  specified** — the precedent in this exact table family is flat (`QuotationItem` freezes
  `name_snapshot`/`hsn_snapshot`/`uom_snapshot`), and `ddl-auto: validate` checks columns but not
  blob contents, so inside JSONB every future field would land unvalidated by anything.
  `AuditLog.detail` remains the codebase's only JSONB column, correctly so: its shape varies per
  event by design, and the buyer snapshot has exactly one shape. RLS needed no change — these are
  columns on an already-enabled, already-forced, already-policied table, and
  `RlsCoverageIntegrationTest` keys on `tenant_id`.

  **The backfill is the interesting half, and it is challenge 68.** Flyway connects as
  `easycrm_owner`, which owns the tables — and `V26` `FORCE`d RLS precisely so the owner is bound
  too. Every policy reads
  `tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid`; `missing_ok = true`
  makes that `NULL` in a session with no GUC, so a plain cross-tenant `UPDATE` in a migration
  **matches zero rows, commits, and reports success** — no error, no warning, no log line. The
  natural post-check (`count(*)` of unfrozen `SENT` rows, written after the loop) runs under the
  same absent GUC, sees zero rows and passes vacuously; the guard fails identically to the thing it
  guards. `V34` therefore loops `FOR t IN SELECT id FROM tenant` (that table has no `tenant_id` and
  V26 deliberately leaves it unforced), `set_config`s per tenant, updates inside the policy, and
  puts the `RAISE EXCEPTION` **inside** the loop. `NO FORCE`-and-re-`FORCE` was rejected (a
  mid-migration failure leaves a silent isolation hole) and `BYPASSRLS` on `easycrm_owner` was
  rejected (a permanent role attribute, superuser to grant, weakening layer 3 forever for one
  migration). **Every future DML migration in this repo follows the `V34` shape.**

  **Domain and write path.** `com.easycrm.sales.BuyerSnapshot` is the codebase's first
  `@Embeddable` — three `String` fields, no identity, mapped into `quotation_version` via an
  `@Embedded` field on `QuotationVersion` alongside a `freezeBuyer(...)` method that sits beside the
  existing `markSent(...)`. `QuotationService.send()` gained one read (through the already-injected
  `VisibleFinder`) and one guard. **The buyer freezes at `send()`, not at creation**, unlike every
  other frozen field on the version: the document means "the buyer as of when this was sent", and a
  draft that sits for two weeks while someone corrects a typo'd GSTIN must send the corrected one.
  That is safe only because buyer identity has no dependents — nothing already frozen was derived
  from it. `placeOfSupply` **does** have dependents (the per-line `cgst`/`sgst`/`igst` on
  `QuotationItem` were computed against it), so it still freezes at creation, and `send()` now
  throws a 422 `ValidationException` naming `placeOfSupply` when `customer.stateCode` no longer
  matches it — otherwise a customer who moves state gets a GST document showing one state's address
  beside another state's tax breakup, which is internally contradictory and looks fine. **The escape
  hatch is a new quotation, not `revise()`**: `revise()` copies the stale `placeOfSupply` and the
  frozen items forward, so it cannot recompute the split and the guard correctly fires again;
  `create()` re-reads the customer and recomputes from scratch. `accept()` already uses the same
  phrasing for the cancelled-order case. Challenge 69 records the general rule.

  **Render path.** `QuotationPdfService.render()` reads `v.getBuyer()` and no longer calls
  `finder.findCustomer(...)`; **`import com.easycrm.crm.Customer` is deleted**, and
  `grep -rn "com.easycrm.crm" backend/src/main/java/com/easycrm/sales/pdf/` returns nothing. That
  deletion is D10's payoff: under the service split, `document-svc` cannot reach `master-data`'s
  schema, so every live `crm` read on the render path would have become a synchronous cross-service
  call on the most latency-sensitive and most cached route in the system. Freezing the buyer deletes
  the call rather than making it fast. A `SENT` version with a null snapshot throws an
  `IllegalStateException` naming the invariant — unreachable after the backfill and the write path,
  and a loud 500 is the right answer for a violated invariant.

  **No API surface (B7).** `QuotationVersionResponse` is unchanged, `docs/api/openapi.yaml` does not
  move, `OpenApiSnapshotTest` stays green as written, and oasdiff records no change. Nothing consumes
  the snapshot — the frontend is zero lines — and adding it later is additive and cheap.

  **`ShareLinkService` is unchanged, deliberately.** The service-scope doc's Appendix A folded S2
  (freeze the customer's primary `Contact`, which `waMeUrl()` reads live) into this slice; it was
  declined and Appendix A amended in place. `ALTER TABLE ... ADD COLUMN` with no default is O(1) on
  modern Postgres, so the "second migration over the same table" the fold was meant to avoid costs
  one extra Flyway file — while freezing the number would make a mistyped phone uncorrectable
  without burning a version number via `revise()`, on a path used daily, for a payoff that lands
  only at SP8 (itself conditional on a §4.5 trigger that may never fire). **The S2 finding stands;
  only its scheduling moved**, so its row in `2026-08-26-platform-modules-handoff.md` is still open.

  **One test gap, stated rather than closed.** Testcontainers starts from an empty database, so the
  backfill loop iterates zero tenants and updates zero rows under test. **No test in this repo can
  prove the backfill works** — its only real check is the in-migration `RAISE`, which runs wherever
  there is data. Every environment from Phase 3 onward is built from `V1` on an empty database, so
  the loop will only ever do real work against a developer's local Postgres. That is why the gap is
  acceptable, not a reason to pretend it is closed.

- **Previous code work: members management** — **merged to `main` as `f2465b8`**, off `main` at
  `e9d694e` (branch `worktree-members-management`). Eight task commits (`0d80e9b`..`f0ce72c`) plus
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

  **561 tests, 0 failures, 0 errors** *as measured on the branch*, up from the 519-test baseline
  it was cut from — `./gradlew clean check` green from a clean state, Spotless clean, SpotBugs 0
  findings. **The post-merge total on `main` is 586**, because the branch later merged `main` into
  itself to pick up the OpenAPI slice's 25 tests; use 586 as the baseline and see the header for
  the arithmetic. New challenges **#65–#67** (#65
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
- **Run tests:** `cd backend && ./gradlew clean check` (the baseline command as of the build-hygiene slice — see §0; `./gradlew test` still runs tests only, but no longer proves what "the build is green" means on this repo). Integration tests spin up one shared Postgres container (singleton pattern) — 586 tests on `main`, run in well under a minute once the image is cached (it was ~4s before the PDF slice; rendering real PDFs is the difference).
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

> **Superseded by `docs/ROADMAP.md` Part 6, and reprioritised 2026-09-12.** This section predates
> the roadmap and is kept for the *reasoning* it records, not for its ranking. The live queue is:
> **Wave 1.5 (supply chain) → Wave 1.6 (Modulith + the H4 cycle fix) → the frontend**, with **D-g
> (the domain name) owed before the frontend mints a public link** and the marketing site moved to
> the bottom at item 16. Item 4 below (cursor pagination) is now roadmap item 12's dependant — the
> load baseline decides it on evidence rather than by elimination. Read §0's reprioritisation note,
> then the roadmap, then come back here only for the qualifications on `SALES_MANAGER` (H6),
> follow-up scheduling, and PF19.

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

**Re-checked 2026-09-02 after the housekeeping pass (§0): the ranking is unchanged.** That pass
pushed nothing new, verified the baseline and CI, and removed dead branches — no code moved, so
nothing below was re-weighted. The four-option set stands, and the standing recommendation is
**Wave 1.5 (supply chain) next, then the frontend**: Wave 1.5 is small, blocked on nothing, and
has the highest security value on a repo that is now public and ships JWT auth, bcrypt and GST
data; the frontend is the higher-value step but is large enough to deserve its own session and a
decomposition before a spec, which is why it sits second rather than first. **This is a
recommendation, not a decision — §0 step 4 still applies: pick with the user.**

**Updated 2026-09-03 — the ranking changed, and there is now a roadmap above it.**
Two things moved. **First: `docs/ROADMAP.md` now exists** and is the programme-level sequencing
document — eight tracks (application, build/CI, local dev, AWS, observability, auth, load+chaos,
and public presence — a free-hosted static marketing site on a custom domain with TLS),
six phases, and a ranked priority list with the blockers between items. Read it before proposing a
next chunk; this section is the session-level view of the same thing, and the roadmap is the one
that carries the AWS and billing threads too. **Second, and more important: the top of the ranking
is no longer Wave 1.5.**

~~**Sub-project 1 — the buyer snapshot (F11) — has never been done, and it is a live correctness
bug.**~~ **DONE 2026-09-07 — branch `buyer-snapshot`, tip `c24ae5e`, awaiting integration.**
`QuotationVersion` froze items, totals and `placeOfSupply` while `QuotationPdfService` read
`businessName`, `gstin` and `billingAddress` **live** from `customer` at render time (verified open
2026-09-03), so editing a customer's address re-rendered a `SENT` quotation differently — including
through a public share link the buyer already holds. Challenge #28 guaranteed byte-determinism
across renders, not across customer edits; F11 was exactly that gap, and the buyer now freezes onto
the version at `send()`. It had been open since 2026-08-19 while nine slices landed around it,
because that thread's numbering lives in the architecture docs and this one never picked it up.
**The lesson survives the fix: do not read sub-project numbering as priority.**

**The recommended order is now: ~~SP1 buyer snapshot~~ → Wave 1.5 supply chain → Wave 1.6 module
boundaries → the frontend**, with the domain and static site runnable in parallel since it consumes
none of the backend queue. Wave 1.5 is the head of the queue. Both remaining backend items are
small and neither is blocked; the frontend still wants its own session and a decomposition pass,
which is why it stays last. Still a recommendation, not a decision — §0 step 4 still applies.

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
- **Wave 1.6, module boundaries (Spring Modulith) — small, unblocked, and it stops a drift that is
  already happening.** Evaluated 2026-09-03 and **adopted, structure-verification half only**:
  `ApplicationModules.verify()` as a build gate plus `Documenter` output guarded like the OpenAPI
  snapshot; the event publication registry and externalisation are **declined** (no `tenant_id`
  column on `event_publication`, no SNS/SQS artifact, and `2026-08-19-outbox-lld.md` is the more
  specified design). See `../architecture/2026-09-03-spring-modulith-evaluation.md`, decisions
  M1–M7. **The finding that decided it: `platform.visibility` imports `crm.Customer` and
  `sales.{Enquiry,Quotation,Order,FollowUp}`, and `platform.job.TenantJobRunner` imports `tenant`
  — three dependency cycles, and under the split every service would inherit `sales` entities from
  the module that is supposed to be shared by all five.** `VisibleFinder` landed 2026-08-29 and
  `TenantJobRunner` 2026-08-31; the service-scope doc was last touched 2026-08-24, so S1–S10 could
  not have seen either. None of the four ArchUnit tests guards package dependencies — **nothing in
  this repo has ever checked a module boundary.** The fix is the `AssignedWorkload` port inversion
  (challenge #66) applied to `VisibleFinder`, and it is owed regardless of Modulith, because MF1
  blocks sub-project 8 on its own.
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
- **Wave 1.6 — module boundaries, evaluated and adopted 2026-09-03, not yet started.** Spring
  Modulith 2.1.1 (the Boot 4 line; 1.x is Boot 3, and Maven Central's *search API* is stale for
  this coordinate — read `repo1.maven.org`'s `maven-metadata.xml` instead). Take
  `ApplicationModules.verify()` and `Documenter`; decline the events half. One catalog interaction
  to decide deliberately: `spring-modulith-core` pulls **ArchUnit 1.4.2 at compile scope** and the
  repo pins 1.4.1 on purpose — Gradle resolves highest-wins, so the bump happens silently unless it
  is made in the catalog with a comment. Full reasoning and the unverified list in
  `../architecture/2026-09-03-spring-modulith-evaluation.md`.
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
