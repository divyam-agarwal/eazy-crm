# Frontend dependency ledger

Spec 2026-09-14-f0 §4.2: every runtime dependency states its gzipped size and the chunk it lands in.
Sizes are measured from the production build by `pnpm deps:sizes` (Task 13), not at install time —
a pre-tree-shaking size is not what a user downloads. **Every later F-slice that adds a runtime
dependency adds a row here in the same change.**

| Package | Version | Chunk | Gzipped (measured) | Why |
|---|---|---|---|---|
| react | 19.3.0 | entry | | UI runtime |
| react-dom | 19.3.0 | entry | | UI runtime |
| openapi-fetch | 0.17.0 | entry | | Typed fetch client generated from the OpenAPI contract (Task 3) |
| i18next | 25.10.10 | entry | ~20.3 KB¹ | i18n core; `common` and `auth` namespaces preload at boot, others load lazily per route (Task 4) |
| react-i18next | 16.6.6 | entry | ~20.3 KB¹ | React bindings for i18next (Task 4) |
| i18next-resources-to-backend | 1.2.3 | entry | ~20.3 KB¹ | Dynamic `import()` loader so each locale/namespace is its own chunk (Task 4) |
| zustand | 5.0.15 | entry | | Session store (`src/session/sessionStore.ts`) — `me`/`status`, read by every feature via `useMe`/`useSessionStatus` (Task 5). Not yet wired into an entry point (bootstrap lands in Task 6); gzipped-measured column is Task 13's job like the other entry deps above |
| react-router | 8.4.0 | entry | 32.42 KB² | Data router (`createBrowserRouter`/`RouterProvider`) — the app's router, `errorElement` and route-level `lazy:` code-splitting (Task 8) |
| @tanstack/react-query | 5.103.1 | entry | ~7.25 KB³ | Query client + cache; not yet used by a data-fetching hook (F0's own routes land in Tasks 10-12), but its defaults (spec §4.5: retry policy, `networkMode: 'always'`, 30 s stale time) are wired and tested from Task 8 on |

¹ The three i18n packages were measured together, not per-package, by the frontend-review-performance
lens during Task 4 fix round 1: bundled with esbuild (`--bundle --minify` against a module importing
all three), combined output is 61.2 KB minified / 20.3 KB gzipped. That figure is carried on all
three rows rather than split — splitting it would imply a per-package precision this measurement
doesn't have. This settled the BOOT_NAMESPACES question in `src/lib/i18n/index.ts` (preloading `auth`
costs ~833 B gzipped against the 200 KB route budget, once the two locale JSON files and boot-time
i18next runtime overhead are netted out — not the full 20.3 KB, which is the whole i18n toolchain,
already paid once `common` alone is preloaded). It is a stand-in for Task 13's real per-app-bundle
measurement (`pnpm deps:sizes`); Task 13 should re-measure from the real production build once the
locale JSON and route code are actually wired in (Task 8+).

² Measured by the frontend-review-performance lens during Task 8 fix round 1: the data-router API
this app imports (`createBrowserRouter`/`RouterProvider`) bundles to **32.42 KB gzip** standalone,
versus **14.75 KB** for the declarative equivalent (`BrowserRouter`/`Routes`/`Route`) — a **17.7 KB**
gap, paid for loaders/actions/fetchers/revalidation that F0 uses nowhere (`RequireSession` gates on a
Zustand read, not a loader). **Kept anyway**: `errorElement` and route-level `lazy:` (R47(a)/(b), see
`router.tsx`) are real wins the declarative API would make us hand-roll ourselves, worse and untested.
This is a genuine, standing lever — not an irreducible cost — if a future slice needs to reclaim
17.7 KB and can live without those two features; recorded here so that option isn't lost from view.

³ Measured the same way: `esbuild --bundle --minify` against a module importing
`{ QueryClient, QueryClientProvider }` (the only two exports this app currently uses), with `react`
and `react-dom` marked `--external` since both are already counted on their own rows above — 24.1 KB
raw / 7.25 KB gzip. This is the marginal cost of the library's core + provider only; it will grow once
a data-fetching hook (`useQuery`/`useMutation`) is actually imported by a route in Tasks 10-12, at
which point Task 13's real `pnpm deps:sizes` run against the production build supersedes this figure.

Version column filled from the resolved `package.json` majors; the gzipped-measured column for
`react`/`react-dom`/`openapi-fetch` is still Task 13's job (`pnpm deps:sizes`), not filled at
install time. Dev-only dependencies are not listed: they never reach a user.

## Note for Task 13's budget review

Vite's default single-entry CSS handling merges every stylesheet reachable from `index.html`
(`src/splash.css`'s `<link>` plus whatever `src/index.css` pulls in via `main.tsx`) into one hashed
file per entry — `assets/index-<hash>.css`, not a separately named `splash-<hash>.css`. This means
the render-blocking CSS payload (currently 4.48 kB raw / 1.59 kB gzipped, well inside the 200 KB
route budget at 67.8 KB total) grows with every Tailwind utility class the app accumulates, and its
hash changes on any unrelated CSS change anywhere in the app — not just changes to the splash
styles. Not a defect at F0's size; flagged here so Task 13's budget review has the context instead
of rediscovering it.
