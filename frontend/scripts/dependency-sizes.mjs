#!/usr/bin/env node
// R13/P13: the dependency ledger (DEPENDENCIES.md) is measured from the production build, not at
// `pnpm add` time — a pre-tree-shaking size is not what a user downloads. This script sums the
// visualizer's per-module gzip sizes (reports/bundle.json, written by the `raw-data` template
// wired in vite.config.ts when ANALYZE=true) by npm package, and lists which output chunk(s) each
// package's code landed in.
//
// Per-module gzip slightly overstates a chunk's real gzip size (each module is compressed alone,
// not as part of the whole chunk), so treat these numbers as an upper bound, not exact.
import { existsSync, readFileSync } from 'node:fs';
import process from 'node:process';

const file = process.argv[2] ?? 'reports/bundle.json';
if (!existsSync(file)) {
  console.error(`deps:sizes: no ${file} — run "ANALYZE=true pnpm build" first.`);
  process.exit(1);
}

/** @typedef {{ id: string; moduleParts: Record<string, string> }} NodeMeta */
/** @typedef {{ gzipLength?: number }} NodePart */

const data = JSON.parse(readFileSync(file, 'utf-8'));
/** @type {Record<string, NodeMeta>} */
const nodeMetas = data.nodeMetas ?? {};
/** @type {Record<string, NodePart>} */
const nodeParts = data.nodeParts ?? {};

/** node_modules/<pkg> or node_modules/<@scope>/<pkg> from a module id. */
function packageOf(moduleId) {
  const marker = 'node_modules/';
  const at = moduleId.lastIndexOf(marker);
  if (at === -1) return undefined;
  const rest = moduleId.slice(at + marker.length).split('/');
  return rest[0]?.startsWith('@') ? `${rest[0]}/${rest[1]}` : rest[0];
}

/** @type {Map<string, { gzip: number; chunks: Set<string> }>} */
const totals = new Map();
for (const meta of Object.values(nodeMetas)) {
  const pkg = packageOf(meta.id);
  if (!pkg) continue;
  for (const [bundleId, partUid] of Object.entries(meta.moduleParts ?? {})) {
    const part = nodeParts[partUid];
    const entry = totals.get(pkg) ?? { gzip: 0, chunks: new Set() };
    entry.gzip += part?.gzipLength ?? 0;
    entry.chunks.add(bundleId);
    totals.set(pkg, entry);
  }
}

if (totals.size === 0) {
  console.error(`deps:sizes: resolved zero packages from ${file} — check the raw-data output.`);
  process.exit(1);
}

console.log('| Package | Chunk(s) | Gzipped (upper bound) |');
console.log('|---|---|---|');
for (const [pkg, { gzip, chunks }] of [...totals].sort((a, b) => b[1].gzip - a[1].gzip)) {
  console.log(`| ${pkg} | ${[...chunks].sort().join(', ')} | ${(gzip / 1024).toFixed(2)} KB |`);
}
