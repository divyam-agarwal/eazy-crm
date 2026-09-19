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

Version column filled from the resolved `package.json` majors; the gzipped-measured column is
still Task 13's job (`pnpm deps:sizes`), not filled at install time. Dev-only dependencies are not
listed: they never reach a user.

## Note for Task 13's budget review

Vite's default single-entry CSS handling merges every stylesheet reachable from `index.html`
(`src/splash.css`'s `<link>` plus whatever `src/index.css` pulls in via `main.tsx`) into one hashed
file per entry — `assets/index-<hash>.css`, not a separately named `splash-<hash>.css`. This means
the render-blocking CSS payload (currently 4.48 kB raw / 1.59 kB gzipped, well inside the 200 KB
route budget at 67.8 KB total) grows with every Tailwind utility class the app accumulates, and its
hash changes on any unrelated CSS change anywhere in the app — not just changes to the splash
styles. Not a defect at F0's size; flagged here so Task 13's budget review has the context instead
of rediscovering it.
