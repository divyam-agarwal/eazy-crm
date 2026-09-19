# Frontend dependency ledger

Spec 2026-09-14-f0 §4.2: every runtime dependency states its gzipped size and the chunk it lands in.
Sizes are measured from the production build by `pnpm deps:sizes` (Task 13), not at install time —
a pre-tree-shaking size is not what a user downloads. **Every later F-slice that adds a runtime
dependency adds a row here in the same change.**

| Package | Version | Chunk | Gzipped (measured) | Why |
|---|---|---|---|---|
| react | | entry | | UI runtime |
| react-dom | | entry | | UI runtime |

Dev-only dependencies are not listed: they never reach a user.
