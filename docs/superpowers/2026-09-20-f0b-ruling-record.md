# F0b ruling record

This document preserves the decisions the controller made while dispatching and reviewing the F0b
frontend-foundation slice — an agent-run implementation of
`docs/superpowers/plans/2026-09-16-f0b-frontend-foundation.md`. The plan is the argument; the spec
(`docs/superpowers/specs/2026-09-14-f0-frontend-foundation-design.md`) is the binding authority. Where
the two conflicted, or where review during execution found the plan's text wrong, stale, or untested,
a ruling records what was decided, why, and what it costs if the ruling itself turns out to be wrong.

The original record was `.superpowers/sdd/2026-09-16-f0b-frontend-foundation/rulings.md` — a
gitignored working file in a directory that does not survive the slice. 107 rulings (R1–R107, plus one
superseded R59 and one amended R95) accumulated there across pre-flight review and eleven fix rounds.
Several are load-bearing for F1 and exist nowhere else — not in the code, not in commit messages. This
file groups them by theme rather than by number, because a 107-row numbered list is a worse record than
one organized around what was actually at stake. Each ruling keeps its original number as an anchor.

Two rulings reference reports that lived in the same now-deleted directory
(`task-17-report.md`, `final-fix-report.md`); their conclusions are captured here, the reports
themselves are not.

## Quick index: reversals and corrections

These rulings overturned an earlier ruling, or fixed a regression a prior ruling caused. They are the
most instructive entries in this record — each is a decision made confidently and then proved wrong by
evidence, not by second-guessing.

- **R46 reverses R4** — the "lazy `auth` namespace" design was measured, not just tested, and found to
  cost a second network round trip on every F0 route. See *i18n and accessibility → namespace loading*.
- **R55 amends R14** — "P15 is proven at the unit level" was correct, but R14 scoped it to only 4 of 6
  cookie-writing call sites on an argument two reviewers independently demolished. See *the testing
  throughline → the P15 lock, six times over*.
- **R60 reverses R59** — R59 accepted an "owner-unknown" gap as a narrow instance of an already-accepted
  bias; a probe test showed it was not narrow at all — it could terminate an unrelated, legitimate
  colleague's session on a shared phone. See *security → the durable-logout-marker saga*.
- **R95-AMENDED reverses R95** — R95 said "verify the ordering assumption before shipping this gate."
  The assumption was tested in real Chromium and falsified; the gate was never shipped, which R95 itself
  had already said was the correct outcome in that case. See *security → the durable-logout-marker saga*.
- **R100 corrects R100's own predecessor** (a ruling made in Task 10's fix round, unnumbered in the
  ledger, recorded via Challenge #98) — "the P15 lock makes the resubmit guard redundant" was accepted
  on a flawed demonstration; a second, more complete test proved the lock serializes duplicate submits
  rather than deduplicating them. See *the testing throughline → confusing serialization with dedup*.
- **R105 corrects a regression R101 introduced** — folding in I8 (remote logout should redirect with a
  reason) broke the invite-route exemption R23 had established five tasks earlier. See *security → the
  durable-logout-marker saga* and *plan defects → the invite-route exemption, twice*.
- **R38 supersedes R34** on challenge numbering, after the assignment scheme collided a second time. See
  *process → challenge numbering*.
- **R73 closes an escape hatch R67 left open** (`zod/v4`, `zod/v3`), and **R75/R76 iterate twice more**
  on the same lint-zone glob after each fix reintroduced a narrower version of the bug it fixed. See
  *bundle size → the zod ban, four rounds*.

## Plan defects corrected before dispatch

The pre-flight review of the plan (against `preflight-scan.md`'s 9 BLOCKING / 22 IMPORTANT / 19 MINOR
findings) surfaced a large number of places where the plan's prose and its own code blocks disagreed,
where a later task assumed something an earlier task never wrote, or where a code block simply would
not compile. These were fixed once, before execution, rather than left for an implementer to discover.

**Compile errors and missing imports.** R8 (Task 10): missing `readEndReasonState` import. R9 (Tasks
11–12): missing `sessionControls` import in `useSignup.ts` and `useAcceptInvitation.ts` — only Task 10's
dispatch had the corrective note. R19 (Task 8): missing `QueryClient`, `SessionRuntime`, `api`,
`getAccessToken` imports in `bootstrap.ts`/`app.test.tsx`. R20 (Task 7): missing `vi` import in
`fields.test.tsx` — fatal because `globals` is not enabled, so the file cannot run at all. R22
(Task 12): an unused `forceCase` import that fails Task 12's own `--max-warnings=0` lint step.

**Stale or self-contradicting code blocks.** R33 (Task 6): the plan's prose patches a few lines later
correct three of its own code blocks — `SessionControls` needs `withCookieLock` in its interface, the
`boot.test.ts` harness needs `logoutPending`/`finishPendingLogout`, and `logout.ts`/`start.ts` need
`parseRetryAfter`/`AuthMessage` imports — but an implementer transcribing the code block verbatim would
ship the uncorrected version. R16 (Task 8): Step 3's corrected boot test and Step 5's uncorrected copy
of the same test both exist in the plan; the corrected one wins, the duplicate is renumbered 4b, so
copying Step 5 verbatim does not silently reintroduce the defect Step 3 exists to fix.

**Contradictory tests, one of which cannot pass.** R5 (Task 6): the plan's own review decision says
`logout()` broadcasts `signing-out` before the POST, so the test asserting a six-entry call-order array
cannot pass — the seven-entry array is correct and the implementation is not the thing that's wrong.
R4 (Task 4, later reversed by R46 — see *i18n* below): two tests in the same task assert opposite things
about whether the `auth` i18n namespace is preloaded; one had to be picked as the survivor before R46
overturned the design choice itself.

**Scheduling and ownership gaps.** R7 (Task 7): `caseInput.ts` and a real test body for it are created
in Task 7, not Task 11 as the plan has it — Task 11 only *imports* it, and Tasks 10 and 12 need it too,
so it belongs in the lib/primitives task that already owns `src/lib/*`. R32 (Task 7): the instruction to
record the SIZE/PATTERN rule in `docs/api/error-codes.md` had no task listed as owning that file; Task 7
is assigned it. R6 (Task 9): the lint fixture directory name is inconsistent across the plan
(`zz-fixture` vs `zz-lint-fixture`); `zz-lint-fixture` is used everywhere, since `zz-fixture` has no
generated zone and the write-side assertion built on it tests nothing.

**Environment and infrastructure.** R1 (Task 2): `src/test/setup.ts` must guard `cleanup()` and
`localStorage.clear()` behind `typeof ... !== 'undefined'` checks, because eight test files run under
`// @vitest-environment node` and the global setup calls jsdom-only APIs that throw in `afterEach` for
all of them — one setup file with two guards, not split setup files per environment.

**The invite-route exemption, twice.** R23 (Tasks 8, 12): `RootLayout`'s `signing-out` gate must not
cover `/invite/:token` — that page renders its own signing-out state in place, because spec §5.1
requires the sign-out-and-accept flow to show the anonymous state "on the same page," and the global
gate would unmount it, losing `maybeAccepted`/`acceptLost` state and flashing a blocking screen. This
exemption was later broken by the final fix wave (R101, folding in I8) and had to be re-fixed by R105 —
see the *reversals index* above and *security* below.

**Architecture-adjacent corrections.** R31 (Task 8): `AppShell` must read identity through `useMe()`,
not `useSessionStore` directly — otherwise P16's stated interface ships with a unit test and zero
consumers, dead code a reviewer would delete on sight, taking a piece of F1's foundation with it. R25
(Task 7): extract one shared `<FieldFooter>` for the description/error `<p>` pair duplicated verbatim
across `TextField`, `PasswordField`, `SelectField` — eleven lines tripled, and the accessibility
contrast fix (R62/R102 below) would otherwise need applying three times. R26 (Task 5): `endSession`
passes its `reason` argument to `runtime.log` — a four-member `EndReason` union existed with nothing
reading it. R27 (Task 7): use `vi.spyOn(Element.prototype, 'scrollIntoView')` rather than a direct
prototype assignment, because a direct assignment is not undone by `restoreMocks` and leaks into every
later test file in the same worker.

## Dependency and tooling versions

**R36 (Task 2, fix round 1) — pin TypeScript, test the rest empirically rather than decree it.**
`typescript` is pinned to `^5` as a hard requirement: `typescript-eslint@8.66–8.70` all declare
`typescript: >=4.8.4 <6.1.0`, and TypeScript 7 is a native rewrite that Task 9's lint/typecheck
integration would not work against — this is evidence-backed, not just rule-backed. For `vite` /
`@vitejs/plugin-react` / `vitest`, the plan's named majors (`^7`/`^5`/`^3`) were *attempted* rather than
assumed: if they installed cleanly and every gate passed, keep them; if not, revert to the
already-consistent installed trio (8/6/5) and say so plainly rather than force an untested combination.

**R37 (Tasks 3–17) — the plan's version list was wrong, and the installed set is now authoritative.**
R36's empirical test settled it: the plan's own trio (7/5/3) installs without a peer conflict but fails
the environment probe outright — `TypeError: RequestInit: Expected signal ("AbortSignal {}") to be an
instance of AbortSignal` inside `@mswjs/interceptors`, precisely the failure the probe exists to catch,
coming from the *older* combination. The installed set — vite 8.3.0, @vitejs/plugin-react 6.1.1, vitest
5.0.1, typescript 5.9.3 — is not drift, it is the working configuration, and every later task must treat
it as ground truth rather than "correct" it back toward the plan's stale numbers. TypeScript stays
pinned at `^5` regardless — that constraint is independent and evidence-backed. Task 17 recorded the
correction in the plan/spec amendments. **Cost if wrong:** if the probe failure was actually an install
artifact, the branch is carrying newer majors than the plan's code assumes, and a later task could hit
an API shift silently.

## The testing throughline: green checks that measured nothing

The single most recurring pattern across this slice's fix rounds was a test suite that stayed green
while covering nothing — a mock that made the real wiring irrelevant, a test that would pass identically
if the behaviour under test were deleted, or an assertion checked at the wrong moment. Reviewers proved
each instance by actually performing the mutation the test was supposed to catch and watching the suite
stay green. By the final fix wave this had become a named shape ("the fourteenth green-check-measuring-
nothing," per R99) that the controller explicitly went looking for.

**Composition and wiring, untested end to end.**
- R39 (Task 3, fix round 1): `client.ts`'s composition — `createAuthFetch` wrapping `createBareFetch` —
  had no test that would fail if the wrapping were removed; a demonstrated swap left typecheck, 39/39
  tests, and the build all green, silently making every request unauthenticated with no 401 refresh.
  A real round-trip through `openapi-fetch` and a schema-typed `openapi-msw` handler was added, verified
  by performing the swap and watching it go red.
- R54 (Task 6, fix round 1): `start.ts`, the composition point for six behaviours (message routing,
  cookie-locked logout/refresh calls), had no test file at all. Three mutations — swapping `'login'` for
  `'logout'` in the subscription, stripping `withCookieLock` from `callLogout`, and the same for
  `refresh` — all left 114/114 green and a clean typecheck. This is explicitly named the third instance
  of this shape on the branch, after Task 3's swap and (implicitly) Task 8's below.
- R69 (Task 8, fix round 1): `startApp()`'s own body — the production entry point — was tested by
  nothing; deleting either its `startSession` or `initI18n` call left 196/196 green. Named the sixth
  instance of the shape.

**The P15 lock, six times over.** P15 requires every cookie-writing call site to hold the same Web
Lock, so two tabs cannot race a cookie rotation.
- R14 (Tasks 6, 10–12, 15) first scoped this to a shared unit-level proof via `holdCookieLock`,
  reasoning that one `withCookieLock` closure covered every site "by construction."
- R55 (Task 6, fix round 1) **amended R14**: that argument was wrong — each call site is an independent
  line, boot's refresh and logout's POST were not covered, and boot's refresh is one of the two named
  actors in P15's own motivating race. Worse, the gap was not merely deferred, it was *permanently
  unassigned* — it appeared in no later task, and `holdCookieLock` occurred exactly once in the whole
  plan.
- R99 (final fix wave) closed the last gap: the coordinator itself — the one path that actually rotates
  the cookie on a 401 — was still untested. Swapping `REFRESH_LOCK` for another lock name left 303/303
  unit, 11/11 E2E, lint, and typecheck all green. Named the fourteenth instance of the pattern.

**Tests that cannot fail by construction.**
- R50 (Task 5, fix round 1): a test meant to prove the cross-tab refresh grace window was bounded passed
  identically when the two independent locks it exercised were replaced by one shared lock — it was
  insensitive to the only axis it existed to test. The controller had mandated this test in R44
  believing it closed the gap; "the implementer's reasoning for its design was genuinely insightful and
  the test still measured nothing" — insight is not evidence. Rebuilt with a third, later caller that
  must be *rejected*, proving the cap is real.
- R78 (Task 10, fix round 1): swapping `attempt={submitCount}` for the literal `attempt={1}` left all 13
  tests green — TypeScript catches removing the prop but not neutering it. A behavioural test (submit
  twice, same error, assert refocus) was added before Tasks 11–12 could copy the hollow pattern.
- R21 (Task 9): the Testing-8 patch making `lintOnce` return `fatal` existed only in the plan's prose,
  never applied to the test file itself — without it, an allow-case would pass on a parse error.
- R71 (Task 10): two integration proofs — a real suspend-then-resolve forcing `RouteSkeleton` to render,
  and a simulated rejected `import()` reaching `RouteErrorBoundary` — did not exist anywhere; both
  contracts lived only in doc comments. R77 later confirmed the first was satisfied by the resulting
  tree-shape proof (reverting the router's Suspense wrap produces a real failure) without needing a
  timing-based race, which the branch had already had to de-flake once elsewhere (R58) and did not want
  to risk again.

**Real infrastructure over stubs.** R51 (Task 5, fix round 1): every existing test for cross-tab
`BroadcastChannel` wiring used a synchronous stub; a typo in the channel name, a missing listener, or a
broken `unsubscribe()` would compile, typecheck, and pass the whole suite while silently breaking
cross-tab logout in production. A test using the *real* `BroadcastChannel` (Node 24's global works under
vitest/jsdom with no polyfill) was added. R58 (Task 6, fix round 1) then found that test itself flaky
(~1 in 8 in a full-suite run) from a bare `setTimeout(…, 0)`, and replaced it with a deterministic latch
— "a flaky test in CI trains people to re-run rather than read, which is worse than the gap it closed."

**Confusing serialization with dedup.** R100 (final fix wave): a resubmit guard on the login/signup/
invite forms had been deleted in Task 10's fix round on the argument that "the P15 Web Lock already
covers it" — a demonstration the controller accepted at the time. The demonstration only asserted
`requests === 1` *while the lock was still held*; re-asserting after release showed a second request
did land (`requestsAfterRelease = 2`). A lock serializes duplicate submits, it does not deduplicate
them — the guard's own justifying prose said "waits until the first one releases it" and then drew the
opposite conclusion. The guard was restored (`if (mutation.isPending) return;`) in all three forms.
Impact today was contained, but the false invariant had been copied verbatim into `LoginPage`,
`SignupPage`, `InvitePage`, and the challenge log — and F1's quotation-accept → order-create flow is
exactly the non-idempotent write a maintainer would have trusted it for.

**Gates that cannot be tripped.** R2 (Task 2): `check-budget.mjs` must always measure the HTML entry and
its static imports independent of the route map, and exit 1 on zero resolved files — the plan's claim
that the over-budget CLI test works against an empty `ROUTE_ENTRIES` is false, since an empty map makes
`measure()` return `[]` and exit 0; that test moves to Task 13, when a real route exists. R98 (Task 16,
fix round 1): `FrontendWorkflowTest`'s CI guard suite was blind to a brand-new workflow step carrying
only `continue-on-error: true` with no `if:` — invisible to every existing assertion, since they each
inspect only named gates or steps that already carry an `if:`. One assertion scanning *all* steps closed
it. R101 (final fix wave) folded in a matching case, I2: the budget script's own zero-files guard could
be deleted and 18/18 tests stayed green, because only the `summarize()` helper was tested, never
`main()`.

**Timing-based assertions.** R106 (final correction): four new negative assertions used fixed real-timer
margins (50ms on three pages, 150ms on the coordinator) to prove "if the bug were present, a request
would have landed by now" — a window that's too short is a false pass, the exact failure this whole
review effort exists to police, landing in the tests meant to close two Criticals about duplicated
writes. Replaced with bounded polls (structural, not empirical) rather than P11's repeated-stress-run
treatment, since a poll was available here and P11 only used stress runs because one wasn't.

## Security: the durable-logout-marker saga

P14's durable `logoutPending` marker exists so a sign-out survives a killed tab or a lost network
connection on a shared device — the target user is explicitly a shop counter with one phone and two
employees. Getting its cross-tab, cross-boot behaviour right took five rounds of rulings, two of which
reverse each other, because each fix closed one hole and opened a narrower one.

- **R56 (Task 6, fix round 1) — SEC-3, rated Critical.** A forged `login` broadcast must not clear the
  durable marker on its own word. When a `login` message arrives while a logout is pending, the client
  must verify against the server first with a locked refresh and decide on the *returned principal*: a
  different principal means a real new session exists and the marker clears; the same principal means no
  login actually happened and the marker (and retry) stays; a 401 clears it. Without this, one
  `postMessage` restores exactly the bug P14 was built to prevent — UI says signed out, cookie lives for
  30 days, next boot silently resurrects the previous user, and it persists *after* the injecting script
  is gone, a real escalation over the already-accepted in-memory-token risk (F0-13).
- **R59 (superseded by R60).** Original text, kept for the record: accept that a freshly loaded tab
  resuming a pending logout has no local `me`, so it cannot classify an incoming `login` broadcast and
  conservatively stays pending — the same fails-toward-signed-out bias as R56, just narrower, and not
  worth extending the marker to carry identity (which the global constraint bans as "a flag, never
  tenant data," and which would leave a previous user's identity on disk on the exact shared device this
  feature targets).
- **R60 reverses R59.** A probe test proved the "narrower" framing wrong: with no captured principal, a
  real, server-verified login for a *different* user looks identical to an unverifiable one — the marker
  stays armed, the next tick POSTs logout carrying that new user's freshly-issued cookie, which the
  backend revokes unconditionally, ending an unrelated, legitimate, just-authenticated colleague's
  session with no attacker involved — in the exact two-employee, one-phone workflow this product targets.
  The fix: persist `{userId, tenantId}` alongside the marker, as a deliberate, narrow, *documented*
  exception to "the marker is a flag, never tenant data," cleared when the marker is. Weighed explicitly
  against the constraint and judged to lose: the constraint was written about tenant/business data, this
  is the user's own id, transient, and the alternative is silently ending a colleague's session. Trade-
  off accepted: a legitimate same-user re-login while their own logout is still retrying will have the
  retry end the new session too — fails toward signed-out, the correct bias here, and reversible if F1
  wants the opposite.
- **R95 → R95-AMENDED.** The original R95 (superseded, text kept for the record) proposed gating the
  `logout` branch's `me === null` case on `isLogoutPending()`, closing SEC14-1 (a forged bare `logout`
  can drop P14's blocking screen without server verification) — but explicitly conditioned it on
  verifying first that a `localStorage` write is reliably visible to another process before a
  `BroadcastChannel` message it preceded. **R95-AMENDED reports the outcome: that assumption was tested
  and falsified.** A 3000-iteration two-tab probe in real Chromium reproduced ordering violations on
  both runs (2/3000 and 52/3000, first at seq=5 with tab B still reading the prior value) — `localStorage`
  cross-process propagation and `BroadcastChannel` delivery are distinct IPC paths with no ordering
  guarantee between them. The gate was correctly never shipped; SEC14-1 is accepted-and-documented
  residual risk instead, recorded in `session.ts` and slated for spec §4.9. Had the gate shipped anyway,
  it would have rejected legitimate confirming broadcasts at a measurable rate and reintroduced
  Challenge #103's permanently-stuck screen — strictly worse than the spoofable UI signal it would have
  closed.
- **R105 (final correction) fixes a regression the final fix wave itself introduced, contradicting the
  controller's own earlier R23.** Folding in I8 (remote logout should redirect with a reason, via
  `RootLayout`'s `onSessionExpired` listener) made that listener navigate unconditionally, walking
  straight past the `/invite/:token` exemption R23 had established. Concretely: a user on an invite link,
  signed in as a colleague, has any other tab on the shared phone sign out legitimately — the invite page
  is now yanked to `/login` and the link is lost, exactly the case F0-10 says must not redirect. Fixed
  by gating the navigate on the same `exempt`/`survivesSignOut` value already computed two lines below
  it. "Two of my rulings, five tasks apart, in direct contradiction — invisible to the numbers, caught
  only by a lens that knew *why* the exemption exists."

Other security rulings in this family, not reversals:

- R40 (Task 3, fix round 1): the inert `AuthBridge` stub emits a one-time `console.error` on first use.
  A wiring failure (forgetting or misordering `setAuthBridge`) currently presents identically to a
  legitimate logout — infinite redirect to `/login`, no error, nothing distinguishing the two. Runtime
  behaviour stays fail-safe; only the diagnostic changes.
- R41 (Task 3 fix round 1, carried to Task 7): `ApiFailure` gains a distinct `{ kind: 'aborted' }`,
  detected via `error.name === 'AbortError'`, separate from network and timeout failures, and
  `applyApiError` treats it as a no-op rather than an error banner. F0 barely cancels anything, but the
  union is consumed by Task 7 and will be copied by every F1 hook — cheaper to fix before the pattern
  duplicates.
- R44 (Task 5, carried forward): Task 5's dispatch must include two tests the security lens asked for —
  N parallel 401s in one tab producing exactly one `refresh()` call, and the cross-tab rotation grace
  window not double-logging-out two tabs racing near-simultaneously. `AuthBridge.refresh`'s single-flight
  shape existed with nothing testing it.
- R49 (Task 5, fix round 1) — the highest-value finding on the branch: wrap the coordinator's `case 'ok'`
  branch so a throw from `onRefreshed` maps to a terminal `'ended'` + `onUnauthorized`, never a
  rejection, with a matching defensive `try/catch` around `authFetch.ts`'s `bridge.refresh(...)` call.
  `toMe.ts` deliberately throws on an unrecognized role and names "the coordinator" as its catcher, which
  it is not; the concrete trigger is the roadmap's own platform-admin role — ship it server-side, and any
  tab on an older bundle gets a 200 with an unknown role, `toMe` throws, `refresh()` rejects, no terminal
  state is ever reached client-side, while the refresh cookie keeps rotating server-side on every retry.
  A real scheduled change would have turned this into a live bug.
- R52 (Task 5, fix round 1): one private `transition(status, me?)` helper in `session.ts` that every
  status write goes through, including the two raw `setState` calls in `subscribeToAuthChannel`; rename
  `resetSessionStore` → `resetSessionStoreForTests` (the old name invites reuse and would strand a
  "switch user" flow on the splash screen); assert `runtime.log` actually receives the end reason (R26
  was satisfied but unasserted).
- R53 (Task 5, record; Task 17): accept the residual risk that unauthenticated `easycrm-auth` broadcasts
  let any same-origin script force sibling tabs to reload or hold them in "signing out" indefinitely —
  same-origin XSS is already total compromise, so nonce machinery buys little. Two conditions: confirm a
  forged `signing-out` cannot write the *durable* marker (a disruption surviving reload would change the
  calculus), and record the decision in spec §4.9's residual-risk list, which already covers F0-13's
  token risk but not this one.
- R57 (Task 6, fix round 1) — SEC-5: record, don't block on, the fact that a `localStorage` failure
  silently degrades P14 to pre-fix in-memory-only behaviour. Making `markLogoutPending()` failure block
  the sign-out POST would break sign-out entirely in private-mode Safari or under quota pressure — worse
  than the degradation itself. Documented in `logoutPending.ts`, the challenge log, and spec §4.9.
- R88 (Task 12, fix round 1): traced through the installed `@tanstack/query-core`, `endSession('logout')`
  → `clearQueryCache()` removes the preview query, so the very next render rebuilds a fresh pending
  query and `isPending` wins the branch race over `signing-out` — the user sees a generic skeleton
  instead of the "do not close this tab" warning. Fixed by rendering `signing-out` before the
  `isPending`/`isError` gates, and strengthened the regression test to hold the preview GET open, not
  just the logout POST (with only the POST held, MSW resolved the refetch instantly and the test could
  not see the bug).
- R24 (Task 9): guard that `src/app/bootstrap.ts` never imports `noopLocks` — a deliberately
  non-exclusive lock provider that ships in production source, one import from bootstrap, protected only
  by a comment, while the plan adds a real guard for `fetch`.
- R61 (process) and R97 (process, strengthening R61): never run two mutation-testing reviewers in the
  same worktree concurrently — a genuine controller error surfaced a transient in-flight mutation from a
  parallel review session, caught only because both reviewers independently re-verified against `git
  show HEAD`. R97 strengthens this after Task 14's E2E reviewer was blocked mid-red-run by the sandbox's
  shared-resource classifier *because* read-only lenses were reading concurrently: any reviewer that must
  mutate and rebuild (E2E, Playwright, anything invoking Gradle) needs the worktree entirely to itself,
  not just exclusivity from other mutators.

## Accessibility and i18n

**Namespace loading — the reversal.** R4 (Task 4) initially accepted that after `initI18n`,
`hasResourceBundle('en','auth')` is `false` — i.e., the `auth` i18n namespace loads lazily, to keep the
boot payload small, with only `common` preloaded. **R46 (Task 4, fix round 1) reverses the design half of
R4**: `auth` is added to `BOOT_NAMESPACES`. The performance lens tested the stated rationale instead of
accepting it, built the same dynamic-import pattern against the installed Vite 8.3.0, and read the
manifest: each namespace becomes a *sibling* chunk, not a dependency of the route chunk, and
`useTranslation('auth')` runs inside the component — so the real sequence is fetch route chunk → parse →
render → suspend → fetch `auth.json` → re-render, two sequential network legs, not the one round trip
the plan claimed. Measured cost of preloading: 833 bytes gzipped against a 200 KB budget, and all three
F0 routes need the namespace anyway, so "don't preload what a route doesn't need" does not even apply
here. The test half of R4 survives, inverted: a test now pins the exact boot list so it cannot regrow
silently one namespace per feature. (R48, same task: the measured total i18n library cost — 20.3 KB
gzipped for i18next + react-i18next + i18next-resources-to-backend — is recorded in `DEPENDENCIES.md`.)

**Live regions and focus management.** This codebase's `FormAlert`/status-region pattern went through
several rounds because screen-reader announcement and keyboard focus are easy to get subtly wrong even
when the visible behaviour looks correct.

- R63 (Task 7, fix round 1): `FormAlert` must re-announce and refocus on a repeated *identical* error,
  keyed on an attempt counter rather than the message string — `setState(sameText)` is a no-op
  re-render, so a live region's text never mutates and most screen readers say nothing on a second
  identical failure. For this audience (flaky 4G, resubmitting) that is the common case, not an edge
  case, and the component's own code comment claimed the opposite behaviour. R65 (Tasks 8, 10–12) then
  makes `attempt: number` a *required* prop on `FormAlert`, carried via RHF's `submitCount` on every
  form page — a consumer passing a constant would typecheck fine while silently restoring the bug R63
  fixed.
- R80 (Task 10, fix round 1) — A11Y10-2: gate `FormAlert`'s focus on `attempt > 0`, not on mount. On the
  `reason=expired` landing, `PageHeading` and `FormAlert` both focus in mount effects, React runs
  children before parents, and the heading's page-identity announcement gets stolen instantly by the
  alert. `role="alert"` already announces a mount-seeded message, so gating *focus* (not announcement)
  on a real submit resolves the collision.
- R89 (Task 12, fix round 1) — a regression of R80's intent: `attempt` was wired to `errorUpdateCount`,
  which increments on the background fetch's own failure and is already ≥ 1 the first time the error
  branch renders — defeating R80's gate on cold arrival. `attempt` must be driven by a genuine
  user-action counter, not a retry counter that merely reads as one.
- R79 (Task 10, fix round 1) — A11Y10-1: give the pending window a voice. `isSubmitting` currently
  disables the very control holding focus, dropping focus to `<body>` with no announcement for the full
  2–5s round trip on a low-end Android over patchy 4G. Add a live status region and keep the submit
  control focusable while pending (`aria-disabled` + `pointer-events-none`, not `disabled`).
- R85 (Task 11, fix round 1): collapse `/signup`'s two `role="status"` regions into one permanently
  mounted region with state-driven content — inserting a live region into the DOM together with its
  content is exactly the failure mode the "mount container first, add content after" ARIA convention
  exists to prevent, and this page reverses a pattern the codebase adopted (with an explanatory comment)
  one task earlier. Worst case: the one moment a shop owner most needs to be told something —
  `SLUG_TAKEN` after a flaky resubmit — is silent.
- R90 (Task 12, fix round 1): hoist one permanently mounted `role="status"` above the branch-keyed
  `<main>`s in `RootLayout`, and apply the identical fix to the pre-existing `SignOutPendingScreen.tsx`
  — the signing-out paragraph was mounted with its text already present, the exact anti-pattern the
  accept-form region in the same file had already been rebuilt to avoid.
- R91 (Task 12, fix round 1): the invalid/expired invite branch needs an actionable next step, at
  minimum a sign-in link — it is one sentence with no link, outside `AppShell`'s nav chrome, reaching a
  user who by definition has no account and no other route into the product (the most likely path here
  is a forwarded WhatsApp link). The sibling signup page already has this shape.

**Contrast and colour.** R62 (Task 7, fix round 1): fixed a bug in the contrast-checking script's own
colour compositing — it blended in linear light while CSS composites in gamma-encoded sRGB — and
corrected Challenge #92's recorded numbers to ≈6.84:1 and ≈3.99:1 (verified to three decimals by two
independent OKLab reimplementations). No token changed; AA holds regardless. R102 (final fix wave) —
I3: fixed two theme tokens that genuinely fail contrast — `--ring` composites to 1.54:1 at `ring-ring/50`
and `--input` gives a 1.26:1 field boundary, both against WCAG 2.2's 3:1 minimum for non-text UI. Axe
checks text contrast only and will stay green on these forever, so no automated gate will ever catch
them, and with `bg-transparent` that border is the only thing identifying a text field.

**Deferred, recorded, or accepted as-is.** R64 (Task 7, fix round 1 + Task 17): fix now (A11Y-4, a
missing `focus-visible` ring on `FormAlert`'s programmatic focus target); **record, don't fix** (A11Y-3,
the `h-9` fixed height on `Input`/`SelectField` is unverified against Devanagari, and needs a real
browser — assigned to Task 17's manual walkthrough, which has one); **accept as-is** (A11Y-5,
`PasswordField`'s 44×36 toggle clears WCAG 2.2's 24×24 minimum, and bumping the shared `Input` height
right before five consuming tasks would churn the foundation to chase an aspiration rather than a
requirement). Task 17's walkthrough later confirmed (narrowly — at `text-base` only, below the `md:`
breakpoint) that no Devanagari clipping occurs, including worst-case stacked conjuncts. R30 (Task 17):
the four-level error-message resolution tier (an improvement over spec §4.6) needed a declared decision
(new P20 in Part 10), not a comment tag — an undeclared departure from spec is what Part 10 exists to
prevent, even when the departure is an improvement. R28 (Task 14): `expectAccessible` (axe) must run on
every page state the E2E specs visit, including all three `cross-tab-logout` tests, per spec §6.4 — that
file ran it zero times. R29 (Task 2): `--font-sans` needs a Devanagari fallback in its system stack
(spec §4.2 requires it explicitly, and F1 ships Hindi) — a one-line CSS fix landed before it could be
forgotten.

**Structural correctness.** R92 (Task 12, fix round 1): replace R23's `pathname.startsWith('/invite/')`
string check with a route `handle: { survivesSignOut: true }` flag — the string check worked, but a
second route needing the same contract would have no compiler or lint signal reminding it to extend the
check, and this codebase had already shipped and fixed exactly this shape of bug once (the `<Trans>`
empty-anchor defect below).

**A cross-page bug found by a page task that wasn't its own.** R84 (Task 11, approve; Task 17, record):
Task 11 reached into Task 10's already-reviewed files to fix a live bug — a placeholder `<link>` tag in
translation strings collided with the void HTML `<link>` element in react-i18next's parser, rendering
`/login`'s "create an account" anchor empty and unclickable. It survived a general review and two
specialist lenses because no test queried the link by role or name. Ruled correct to fix in place rather
than defer: "scope purity does not outrank a shipped page with a dead link," and the regression test
belongs next to the bug, in Task 10's file — recorded so the final review would not read the cross-file
diff as scope creep.

## Bundle size and code-splitting

**The zod ban, four rounds.** A wrong zod import is the single largest bundle-size risk on this branch,
because `zod`'s namespace-style API retains its entire runtime the moment any method is touched.

- R67 (Task 9 lint rule; Tasks 10–12 dispatches) — measured against the installed zod 4.6.5: a two-field
  schema on `zod/mini` is 5.05 KB gzip; the *same* schema written the classic namespace way is 92.2 KB.
  With 57.5 KB of headroom at the time, one wrong import in a page task would put the entry chunk alone
  at 234.7 KB against a 200 KB budget — and CI here runs post-merge, so it would ship red to `main`. The
  trap is well-trodden: `@hookform/resolvers`'s own README uses the classic API. `no-restricted-imports`
  bans a bare `'zod'` import; only `'zod/mini'` is allowed. **This is a decision that binds F1: any code
  touching Zod schemas must import from `zod/mini`, never the library's own documented top-level API.**
- R73 (Task 9, fix round 1) closes an escape hatch R67 left open: `no-restricted-imports`'s `paths`
  option matches only the literal specifier, so `zod/v4` and `zod/v3` were uncaught — measured at 92,214
  bytes gzip, functionally identical to bare `zod`, and `eslint --max-warnings=0` exited 0 on a file
  importing it. Worse, `zod/v4` is a *documented path in zod's own v4 migration guide* — repeating the
  original trap's defining feature. Fixed by banning via `patterns`, not `paths`.
- R76 (Task 9, fix round 2): also ban `zod/compile` (10.2 KB gzip as a bare side-effect import, and its
  source imports the same `./v4/core/*` internals whose weight justified banning `zod/v4/core` — "measure
  it, don't infer safety from the API shape" cuts both ways), and add a positive allow-case test for the
  `zod/v4-mini` alias, which was correctly unbanned but had no test proving it.
- Two lint-zone glob bugs surfaced and were fixed in successive rounds on the *session boundary* rule
  (not zod, but the identical shape of "each fix reintroduces a narrower version of the bug"): R74
  (fix round 1) found the zone's glob had no `**`, so it matched only direct children of `src/session/`
  and a file in any subdirectory bypassed the ban entirely — passing lint clean on a real test. R75
  (fix round 2) found the fix for that bug created a narrower instance of it: the `useMe` exemption
  negated on *basename at any depth*, so `src/session/internal/useMe.ts` — a plausible real filename —
  was silently exempted too. Split into a depth-0 exemption plus an unconditional depth-≥1 ban, this
  time with a test for the *allow* side, since the missing piece each round had been a test for allow,
  not deny.

**The `<select>` decision — binds F1.** R83 (Task 11, no action needed — the question was already
settled): `SelectField` is a native `<select>`, not Radix, because Radix `Select` pulls in
`react-remove-scroll`, which injects a runtime `<style>` tag the app's CSP refuses. This was already
decided in an earlier plan decision (P9); recorded here so Task 11 would not re-litigate the control
choice or rediscover the CSP constraint the hard way. R86 (Task 11, fix round 1) makes the constraint
structural: a `no-restricted-imports` gate blocks the Radix `Select`/`Dialog`/`Popover`/`AlertDialog`
family from the `radix-ui` umbrella package, allow-listing only the primitives actually in use — because
`radix-ui` is one dependency, so those components (and `react-remove-scroll`) are already resolved in
`node_modules`, one import away from silently reintroducing the exact CSP violation P9 spent effort
avoiding. **Binds future work:** any future use of Radix `Select`/`Dialog`/`Popover`/`AlertDialog` will
hit a lint error naming the CSP reason, by design.

**Route budget mechanics — binds F1.** R2 (see *plan defects* above) established that `check-budget.mjs`
always measures the HTML entry unconditionally, and R3 (Tasks 10–12) made each page task responsible for
adding its own route to `ROUTE_ENTRIES` and running `pnpm budget`. R12 (Tasks 3–12) made `pnpm build &&
pnpm budget` mandatory in every task's verification step, since 8 of 10 tasks in the plan's text omitted
it. R82 (Tasks 11–12) required the printed route figure to be pasted into the commit body, since CI's
own route-budget gate does not exist until Task 16 — this manual step was the only backstop before then.
**R87 (Task 13) is the mechanism this all builds toward and binds F1's budget tooling:** `pnpm budget`
must carry a per-route *delta* against a committed baseline, not just an absolute number, because a
route's headroom moves even when the route itself is untouched — measured directly: `/login` moved
168.8 → 172.2 KB with no change to `/login` at all, because the shared `translator` chunk grew when
`/signup` landed (normal chunk-boundary recomputation, confirmed at the module level, zero duplication).
Without the delta, the first commit to trip 200 KB gets blamed for drift another route actually caused.

**Other bundle findings.**
- R68 (Task 8, fix round 1): the ledger (`DEPENDENCIES.md`) was missing rows for `react-router` and
  `@tanstack/react-query` entirely, in violation of spec §4.2's per-dependency row requirement; added,
  along with the measured 17.7 KB gap between the data router and the plain declarative router. The data
  router is kept deliberately — `errorElement` and route-level `lazy:` are real ergonomic wins — but the
  "irreducible" framing was struck from the record so a future implementer with a headroom problem knows
  it's a real lever, not a dead end.
- R72 (Task 8, fix round 1): `RouteErrorBoundary`/`UnreachableScreen` statically import shadcn `Button`,
  pulling ~6.7 KB of `cn` + `class-variance-authority` + `radix-ui/react-slot` into every page load for
  two rare-path screens. Replace with a plain `<button>` *only if* that measurably removes the chain
  from the entry chunk; if something else entry-reachable already imports `Button`, the change buys
  nothing and should not be made — measure first, don't assume.
- R101 (final fix wave), item I7: `RouteSkeleton` → `Skeleton` → `cn` put 12.97 KB gzipped into every
  route's initial payload just to merge two class strings, when the fix pattern already existed
  elsewhere in the codebase (`basicButtonClass.ts`). Same fold-in also closed I1 (the budget script only
  measured 3 of 6 lazy routes — `/` itself, exactly where F1 will grow, was unmeasured).
- **R107 (handoff ticket, binds F1) — I7's win has no regression test.** Re-importing `Skeleton`/`cn`
  into anything statically reachable from the router would cost back ~13 KB gzip and would *not* trip
  `pnpm budget`, since every route carries 23–55 KB of headroom. A real erosion risk with no gate; the
  fix (a lint rule barring `cn`/`components/ui/*` from router-reachable files, or an explicit
  entry-chunk assertion) was deliberately left for whoever owns F1's structure to design, rather than
  bolted on at merge time.
- R47 (Task 8): two performance findings carried into the dispatch — (a) the login/signup/invite routes
  need their own local Suspense boundary with a sized skeleton, since the plan's single top-level
  `<Suspense fallback={null}>` around the whole `RouterProvider` would blank `RootLayout` entirely on
  any suspend; (b) a retry-then-error-UI wrapper for rejected dynamic `import()`s, since neither
  `resourcesToBackend` nor React Router's `lazy` retries on their own, and a rejection throws past
  Suspense uncaught. R70 (Task 8, fix round 1) found that wrapper's retry budget too short — 3 flat
  attempts at 500ms gives up after ~1 second against failures (a tunnel, a lift, a tower handoff) that
  routinely last several seconds, and spec §4.4 sets this app's own bar at "within 5s, later retries may
  back off further." Lengthened to match, or the mismatch must be justified in code rather than left
  silent.

**Bookkeeping honesty.** R93 (Task 13, fix round 1): a footnote in `DEPENDENCIES.md` blamed a ~0.5 KB
measurement discrepancy on "esbuild-tooling noise" — contradicted by evidence, since the relevant tool
versions were pinned identically at both measurement points and the discrepancy reproduced
deterministically. Rather than invent a second wrong explanation (this was the third time on the branch
a plausible-sounding wrong diagnosis had been recorded — after the contrast-script bug and Challenge
#96), the footnote now states plainly that the cause is unestablished. R94 (Task 13, fix round 1):
clarified that `budget-baseline.json`'s seeding mechanism is forward-only — it was seeded from
already-drifted numbers in the same commit, so it cannot retroactively explain the drift a human had to
trace by reading prior task reports; the challenge-log entry's phrasing could otherwise be misread as
claiming the tool did that attribution itself.

## Process and review discipline

- **R38 supersedes R34** on challenge numbering, after the assignment scheme collided twice. R34 first
  shifted the plan's three pre-named challenge numbers (85→87 etc.) to make room for one a task
  legitimately discovered first. R38 replaced the whole approach: **challenge numbers are assigned at
  commit time by taking the next free number in `docs/superpowers/engineering-challenges.md` — none is
  ever reserved in advance.** The plan had pre-assigned numbers to three challenges written *last*, while
  tasks running *first* kept discovering genuinely challenge-worthy problems and taking those numbers
  first; chronological assignment is how the log already works, and a reservation the execution order
  contradicts is what was actually wrong. Every dispatch from that point states the next free number and
  forbids assuming a reserved one.
- R61 and R97 (see *security* above): mutation-testing reviewers need real isolation — never two in the
  same worktree concurrently, and one that must rebuild (E2E/Playwright/Gradle) needs it entirely to
  itself, not just exclusivity from other mutators.
- R66 (process): a documentation-only fix round with no code impact may be verified by the controller
  directly with a grep, rather than a dispatched scoped re-review — dispatching an agent to confirm a
  single corrected CSS comment costs more than the change itself. The exemption is narrow: it requires
  checking the diff's file list first, since a round that quietly touches code would defeat a grep-only
  check.
- R42 (Task 3, fix round 1): three Minor findings were folded into an already-open fix round because
  each was a one-line prevention of later rework — exempting `/api/v1/auth/signup/status` from the
  refresh flow (or recording the backend's guarantee it never 401s), exporting `resetAuthBridge()` for
  the shared `afterEach` (module state was otherwise leaking across test files in one Vitest worker —
  "green until run order changes, which is the worst kind"), and a comment at `unwrap`'s throw site.
  Process otherwise says Minors go to the ledger, not the loop, specifically so a round is never opened
  *for* a Minor — but the round was already open regardless.
- R96 (Task 15, charter rewritten; Task 17, record): Task 14 had written Task 15's E2E spec but never run
  the mandated red-run proof (the reviewer's own attempt was blocked by the sandbox), so nobody had
  empirical proof the test could catch broken Web Locks — on the one task the handoff itself calls
  highest-risk for exactly this reason. Left implicit, a future agent handed "Task 15" would find its
  named files already absent, `EXPECTED` already satisfied, and reasonably skip it, permanently losing
  the proof. Task 15's charter was rewritten as verification-and-hardening: run the two mandated red
  runs (swap in `noopLocks`, confirm concurrency failure; remove `withCookieLock` from `useLogin`,
  confirm a login-vs-refresh race becomes observable), redesign the spec if either fails to go red per
  the plan's own words ("if it still passes, the test is vacuous: stop and redesign it"), add the second
  tab's CSP watch the auto fixture didn't cover, and reconcile the expected test count with reality.

**R104 (parked, for whoever merges) — binds future edits to the handoff doc.** The handoff's hardcoded
`git rev-list --count` figure is unfixable as a static number: this is the second time it has drifted
(34→35 previously, now 35 vs. a true count of 36), and it is self-referential by construction — any
commit that writes the count is off by one, because it cannot count its own commit. Parking rather than
patching: the durable fix is to print the *command* (`git rev-list --count main..f0b-frontend`) in the
handoff instead of its output, a one-line change left for whoever next touches the file. Explicitly
judged not worth a second fix wave on its own.

## Decisions recorded for F1 or the deployment layer (SP2), not acted on now

Several rulings deliberately declined to act, on the grounds that acting now would be premature, the
owning layer doesn't exist yet, or the decision belongs to whoever does that work next — but the decision
itself, and its reasoning, needed to survive. These are the ones most likely to be silently re-litigated
if this record is lost.

- **R43 (Task 17, record only) — `src/api/types.ts` split point.** All 8 re-exports stay in one file for
  F0; Tasks 5–12 are already written against these exact export names, and churning imports across nine
  tasks to pre-empt a future merge conflict is a bad trade today. **The moment to split is F1's first
  non-auth schema** — at that point, features own their own aliases and `api/types.ts` keeps only what
  `src/session/` needs. If F1 ignores this note, it pays the reorg cost then instead of now.
- **R45 (Task 4, declined; Task 17, record) — deferred i18n copy decision.** Do not add `{{count}}`
  interpolation ("you entered 14") to length-error keys now. The blocker: client-side Zod messages have
  the entered length for free, but server-derived error codes carry only `field → code`, not the value,
  so symmetric interpolation would require growing `applyApiError`'s signature — a foundation function
  four tasks already consume — just to improve copy. **F1's Hindi copy pass is the designated moment**,
  alongside I18N-1's duplicate-copy pairs; until then the messages are correct but not maximally helpful.
- **R53 (Task 5, record; Task 17) and R57 (Task 6, fix round 1)** — see *security* above: both are
  residual-risk acceptances that must land in spec §4.9's list via Task 17, not fixes.
- **R64's A11Y-3 (Task 7, fix round 1; Task 17)** — see *accessibility* above: the Devanagari
  fixed-height question was assigned to Task 17's manual walkthrough specifically because it needed a
  real browser, which that task has and Task 7 did not.
- **R103 (Task 17, record; SP2) — CSP and `/public/q/{token}` belong to the deployment layer.** Two
  findings (I5, I6) cannot be fixed in this repo: the CSP currently ships only from `vite preview`, and
  `/public/q/{token}` is mapped outside `/api`, so the SPA catch-all route would shadow it — both need a
  deployment layer that does not exist yet, named in the roadmap as SP2. This matters more than it looks:
  those `/public/q/{token}` URLs are **already going out in sent WhatsApp messages**, and Part 8's
  routing note currently only specifies `/invite/*` → SPA and `/api/*` → backend, leaving this path
  unspecified. Discovering the gap at deploy time would be expensive; the note costs nothing. Recorded in
  spec Part 8 and the handoff, with SP2 named explicitly as the owner.
- **R104 (parked)** — see *process* above: the self-referential commit-count fix, left for whoever next
  touches the handoff file.
- **R107 (handoff ticket)** — see *bundle size* above: I7's bundle-size win has no regression test, and
  the correct fix (a lint rule or entry-chunk assertion) is deliberately left to whoever owns F1's
  structure, rather than bolted on at merge time by this slice.

## Recorded but not acted on (Minors)

A short list of Minor findings were explicitly triaged to "handled by the implementer if encountered, or
at the final review" rather than given individual rulings: A5 (test-count arithmetic — the standing
instruction across the whole slice was that a predicted test count never overrides the actual count),
A10, A14, A16, A20, A25, A30, A31, A36, A44, A49, A56, A67, A81, C6, C8, D3, D4, D5, D7, D11, D16. None of
these altered behaviour or a gate; they are preserved here only so a future reader knows they were seen
and consciously deferred, not missed.
