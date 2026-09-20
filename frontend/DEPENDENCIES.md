# Frontend dependency ledger

Spec 2026-09-14-f0 §4.2: every runtime dependency states its gzipped size and the chunk(s) it lands
in. Sizes are measured from the **production build** (`ANALYZE=true pnpm build && pnpm deps:sizes`,
Task 13), not at `pnpm add` time — a pre-tree-shaking size is not what a user downloads (R13/P13).
**Every later F-slice that adds a runtime dependency adds a row here in the same change** —
`scripts/dependencies.test.ts` fails the build if a `package.json` `dependencies` entry has no row.

`deps:sizes` sums the visualizer's **per-module** gzip sizes by npm package. That slightly
overstates a chunk's real gzip size (each module is compressed alone, not as part of the whole
chunk it ships in), so read every number below as an **upper bound**, not exact — and don't expect
column sums to equal a chunk's own reported size in the `pnpm build` output.

"Chunk" names the built asset(s) the package's code appears in, translated to which download(s) it
belongs to: **entry** (part of the HTML entry's own static import graph — paid by every route),
or the route(s) whose lazy chunk imports it. Three of the app's shared chunks are not part of the
entry but are still pulled in by more than one route: `translator` (`/login`, `/signup`,
`/invite/:token`), `authKeys` (`/signup`, `/invite/:token` — react-query's typed query-key
factory), and `caseInput` (`/login`, `/signup`).

| Package | Version | Chunk | Gzipped (measured) | Pulled in by | Why |
|---|---|---|---|---|---|
| react | 19.3.0 | entry | 4.94 KB | — | UI runtime |
| react-dom | 19.3.0 | entry | 99.12 KB | — | UI runtime |
| react-router | 8.4.0 | entry | 51.22 KB¹ | — | Data router (`createBrowserRouter`/`RouterProvider`) — `errorElement` and route-level `lazy:` code-splitting (Task 8) |
| @tanstack/react-query | 5.103.1 | entry, /login, /signup, /invite/:token | 3.14 KB² | — | Query client + cache; `useQuery` now has two consumers (`/signup`, `/invite/:token` — see ² and the note below the table) |
| zustand | 5.0.15 | entry | 0.89 KB | — | Session store (`src/session/sessionStore.ts`) — `me`/`status`, read by every feature via `useMe`/`useSessionStatus` |
| react-hook-form | 7.88.0 | /login, /signup, /invite/:token | 16.13 KB | — | Form state; shared automatically by Rolldown into one chunk, no per-route duplication |
| @hookform/resolvers | 5.9.1 | /login, /signup, /invite/:token | 1.81 KB | — | Bridges `react-hook-form` to a `zod` schema |
| zod | 4.6.5 | /login, /signup, /invite/:token | 9.40 KB | — | Form validation; `zod/mini` only, per spec |
| i18next | 25.10.10 | entry | 17.78 KB³ | — | i18n core; `common` and `auth` namespaces preload at boot, others load lazily per route |
| react-i18next | 16.6.6 | entry, /login, /signup, /invite/:token | 8.73 KB³ | — | React bindings for i18next (`useTranslation`, `Trans`) |
| i18next-resources-to-backend | 1.2.3 | entry | 0.63 KB³ | — | Dynamic `import()` loader so each locale/namespace is its own chunk |
| openapi-fetch | 0.17.0 | entry | 3.36 KB | — | Typed fetch client generated from the OpenAPI contract (Task 3) |
| class-variance-authority | 0.7.1 | /login, /signup, /invite/:token | 0.66 KB | — | shadcn primitives — variant class composition (`cva()`) |
| cn | 0.3.0 | /login, /signup, /invite/:token | 13.06 KB | — | Compiled drop-in replacement for `clsx` + `tailwind-merge` — the shadcn `cn()` utility (`src/lib/utils.ts`). This project uses `cn`, not a separate `tailwind-merge` dependency. **I7 (final fix wave):** previously also "entry" via `RouteSkeleton` (statically imported by `router.tsx`) rendering the shadcn `Skeleton`, which imports `cn` purely to merge a fixed base class with static size utilities — 12.97 KB gz of the entry chunk spent on a loading placeholder. `RouteSkeleton` now composes its own `BASIC_SKELETON_CLASS` (`src/app/basicSkeletonClass.ts`, same pattern as `BASIC_BUTTON_CLASS`) with a template string instead, so `cn` no longer ships in the entry — only in the three lazy routes that already import it for real reasons |
| radix-ui | 1.6.7 | /login, /signup, /invite/:token | 3.29 KB⁴ | — | shadcn primitives — meta-package; the actual code that ships is whichever `@radix-ui/react-*` primitives are imported (see ⁴) |
| lucide-react | 1.47.0 | /login, /signup, /invite/:token | 4.60 KB | — | Password toggle icons |
| @tanstack/query-core | 5.103.1 | entry, /login, /signup, /invite/:token | 18.40 KB | @tanstack/react-query | Query engine `@tanstack/react-query` re-exports; this is most of that package's real weight |
| scheduler | 0.28.0 | entry | 2.43 KB | react-dom | React's cooperative scheduler |
| use-sync-external-store | 1.7.0 | entry | 0.90 KB | react-i18next, zustand | External-store subscription shim both packages depend on |
| html-parse-stringify | 3.1.0 | /login, /signup, /invite/:token | 1.62 KB | react-i18next | HTML (re-)parsing for the `<Trans>` component |
| void-elements | 3.1.0 | /login, /signup, /invite/:token | 0.24 KB | html-parse-stringify (→ react-i18next) | Void-tag table used by the parser above |
| clsx | 2.1.1 | /login, /signup, /invite/:token | 0.35 KB | class-variance-authority | `cva()`'s internal class-name joiner |

¹ **Re-measured for Task 13.** Standalone `esbuild --bundle --minify` (external `react`/`react-dom`)
against a module importing only what this app uses: `createBrowserRouter`/`RouterProvider` bundles
to **31.4 KB gzip**, versus **14.2 KB** for the declarative equivalent (`BrowserRouter`/`Routes`/
`Route`) — a **17.2 KB** gap (previously recorded as 32.42 KB / 14.75 KB / 17.7 KB). **The cause of
that ~0.5 KB per-side difference is not established.** What was ruled out: version drift
(`react-router@8.4.0` and `esbuild@0.28.2` are pinned identically in `pnpm-lock.yaml` at both the
Task 8 commit, `f62a503`, and here — neither moved) and gzip level (`-9` vs `-6` differs by tens of
bytes, not ~500). What was tried and did not reproduce the original 32.42/14.75: the same command
in `--format=esm` (default here), plus `--format=iife`, `--format=cjs`, and adding an explicit
`--define:process.env.NODE_ENV=\"production\"` — all four landed in the 31.4–31.7 KB /
14.2–14.5 KB range, none at the original figures. The original footnote never recorded its exact
command (only a prose description), so a true verbatim re-run isn't possible; rather than guess
further, this is left as: two isolated measurements of the same library version, ~0.5 KB apart,
constructed somehow differently, cause unknown. **Kept anyway**: `errorElement` and route-level
`lazy:` (`router.tsx`) are real wins the declarative API would make us hand-roll, worse and
untested. This is a genuine, standing lever — not an irreducible cost — if a future slice needs to
reclaim ~17 KB and can live without those two features. The 51.22 KB figure in the table above is
the real production number (per-module gzip, so an upper bound, and it includes some overlap
double-counted against other entry-chunk packages — see the caveat above the table); this footnote's
31.4/14.2/17.2 KB figures are the controlled, isolated comparison, not the same measurement.

² **`useQuery`'s machinery split, as speculated.** `/invite/:token`'s auth mutation/query code
lands in `authKeys-*.js`, which `SignupPage` also imports — Rolldown put both routes' shared
`@tanstack/query-core` + query-key-factory code in one chunk once `/invite` became a second
consumer, instead of duplicating it per route (confirmed from `dist/.vite/manifest.json`: both
`SignupPage.tsx` and `InvitePage.tsx` import `_authKeys-*.js`; `LoginPage.tsx` does not, since it
has no query). This makes the real numbers *better* than the pre-Task-13 estimate assumed, not
worse. The `@tanstack/react-query` row's own code (3.14 KB) is now much smaller than the combined
weight of everything it pulls in (`@tanstack/query-core`, 18.40 KB) — see that row for the split.
Superseded: the old footnote's ~7.25 KB standalone-provider-only esbuild estimate, made before any
route called `useQuery`.

³ Previously measured together as one 20.3 KB gzip figure (Task 4, esbuild bundle of all three
packages). Now itemized per-package from the real production build: 17.78 + 8.73 + 0.63 = 27.14 KB
combined — higher than the old combined estimate, consistent with per-module gzip being an upper
bound (each module compressed alone loses the cross-module redundancy the old single-bundle esbuild
measurement captured).

⁴ `radix-ui` itself ships almost no code of its own — it's a re-export surface. Tree-shaking
resolves an import through it down to the actual `@radix-ui/react-*` primitive, so the code that
ships is `@radix-ui/react-slot` (1.64 KB), `@radix-ui/react-primitive` (0.67 KB),
`@radix-ui/react-compose-refs` (0.51 KB), and `@radix-ui/react-label` (0.47 KB) — 3.29 KB summed.
Those four aren't broken out as their own ledger rows because `pnpm why` traces every one of them
back to `radix-ui` (this app's only consumer of `@radix-ui/*`), not to some other direct dependency.

Route totals at `d7defab` (this task changed tooling and docs only, not app code or dependencies):
**/login 172.7 KB, /signup 177.0 KB, /invite/:token 176.0 KB** (gzipped, budget 200 KB; entry
144.6 KB — the ~0.1 KB of run-to-run variance on `/login` is build non-determinism, not a real
change; `pnpm budget` was re-run twice back-to-back to confirm it settles here). `pnpm budget` now
also reports each route's delta against the committed
`budget-baseline.json` — see that file and `scripts/check-budget.mjs` (R87). Absolute-only
reporting had let `/login` drift 168.8 → 172.2 → 172.6 KB across three tasks that never touched
`/login` (the shared entry chunk grew under it each time). **The mechanism is forward-only**:
`budget-baseline.json` was seeded in this task from these already-drifted numbers (`/login` at
172.7 KB, after the historical growth above, not before it), so it cannot retroactively attribute
that 168.8 → 172.6 KB drift — a human did that, by reading the three prior tasks' own reports. What
it does going forward is make the *next* route to erode visible at the commit that causes it,
instead of months later when some unrelated change finally trips 200 KB. `budget-baseline.json` is
updated only by an explicit `pnpm budget --update` — nothing implicit rewrites it.

**Final fix wave (2026-09-20) update:** I7 moved `cn` out of the entry chunk (see that row above).
Re-measured route totals: **index.html (entry) 133.8 KB** (down from 144.6 KB — the ~10.8 KB gzip win
I7 predicted), **/login 171.7 KB, /signup 176.2 KB, /invite/:token 175.2 KB**, plus two routes I1 added
to the budget script that were previously unmeasured: **/ 146.8 KB, \* 134.0 KB** (all gzipped, budget
200 KB per route; `budget-baseline.json` updated to match via `pnpm budget --update`).

Dev-only dependencies are not listed: they never reach a user.

## Note for Task 13's budget review

Vite's default single-entry CSS handling merges every stylesheet reachable from `index.html`
(`src/splash.css`'s `<link>` plus whatever `src/index.css` pulls in via `main.tsx`) into one hashed
file per entry — `assets/index-<hash>.css`, not a separately named `splash-<hash>.css`. This means
the render-blocking CSS payload — **re-measured for Task 13: 24.31 kB raw / 5.39 kB gzipped**, up
from 4.48 kB / 1.59 kB when this note was written (Task 8; three more pages' worth of Tailwind
utility classes since), still well inside the 200 KB route budget (baked into every route total
above) — grows with every Tailwind utility class the app accumulates, and its hash changes on any
unrelated CSS change anywhere in the app — not just changes to the splash styles. Not a defect at
this size; flagged here so a future budget review has the context instead of rediscovering it.
