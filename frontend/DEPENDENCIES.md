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
