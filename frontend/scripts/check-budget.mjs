#!/usr/bin/env node
// P18 / Performance-2: keep each route's initial payload bounded so the app stays usable on
// low-end Android over flaky 4G. Standing this up in Task 2 (rather than waiting for Task 13)
// means the task that pushes a route over budget is the task that gets caught, not the last one
// before ship.
//
// Controller ruling R2: with ROUTE_ENTRIES empty (no pages exist yet), this script must still
// measure the HTML entry and its static imports, and must still fail if it resolves zero files —
// so `measure()` takes the route map as a parameter (defaulting to ROUTE_ENTRIES) and the HTML
// entry is always included, independent of the route map.
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { gzipSync } from 'node:zlib';

/** Per-route JS+CSS budget, gzipped bytes. */
export const BUDGET_BYTES = 200 * 1024;

const DIST_DIR = 'dist';
const HTML_ENTRY = 'index.html';

// R87: a committed baseline of each label's last-recorded gzip size, so `pnpm budget` can report
// per-route drift ("+Y KB since baseline"), not just the absolute number. Absolute-only reporting
// let /login drift 168.8 -> 172.2 -> 172.6 KB across three tasks that never touched /login — the
// shared entry chunk grew under it each time — invisible until some later, unrelated commit trips
// the 200 KB ceiling and gets blamed for months of accumulated erosion. The delta never fails the
// build; BUDGET_BYTES (below) stays the only hard gate. `pnpm budget --update` is the only thing
// that moves the baseline — nothing implicit rewrites it.
export const BASELINE_PATH = 'budget-baseline.json';

// Each page task adds its own entry. NOTE (I1 correction): routeFiles()'s missing-key throw does
// NOT force this list to stay in sync with router.tsx — it only fires in the OPPOSITE direction,
// catching a ROUTE_ENTRIES value that names a module the build never produced (a typo, a moved
// file, a route deleted from router.tsx but left here). It fires on a STALE entry, not a MISSING
// one: nothing throws, and `pnpm budget` stays green, if a new route is added to router.tsx and
// never added here — I1 itself (`/` and `*` going unmeasured for three tasks) is exactly that
// failure mode, found by reading router.tsx against this list, not by any test going red. Typed
// with these five literal keys (not a plain `Record<string, string>` index signature) so a consumer
// that indexes `ROUTE_ENTRIES['/login']` gets `string | string[]` under `noUncheckedIndexedAccess`,
// not `... | undefined` — this object's shape is fixed, not open-ended, so the precise type is also
// the honest one. `/` has no single page module of its own (router.tsx nests `RequireSession`
// (statically imported, already inside every other measurement) -> lazy `AppShell` -> lazy, index-
// routed `HomePage`), so its value is the array of BOTH lazy chunks that make up its initial payload.
/** @type {{ '/login': string; '/signup': string; '/invite/:token': string; '/': [string, string]; '*': string }} */
export const ROUTE_ENTRIES = {
  '/login': 'src/features/auth/pages/LoginPage.tsx',
  '/signup': 'src/features/auth/pages/SignupPage.tsx',
  '/invite/:token': 'src/features/auth/pages/InvitePage.tsx',
  '/': ['src/app/shell/AppShell.tsx', 'src/app/shell/HomePage.tsx'],
  '*': 'src/app/NotFoundPage.tsx',
};

/**
 * @typedef {{ file?: string; css?: string[]; imports?: string[]; dynamicImports?: string[] }} ManifestChunk
 * @typedef {Record<string, ManifestChunk>} Manifest
 * @typedef {{ label: string; files: string[]; bytes: number }} MeasureResult
 */

/**
 * Read and parse the Vite manifest written by `vite build` (`build.manifest: true`).
 * @param {string} distDir
 * @returns {Promise<Manifest>}
 */
async function loadManifest(distDir) {
  const manifestPath = path.join(distDir, '.vite', 'manifest.json');
  let raw;
  try {
    raw = await readFile(manifestPath, 'utf-8');
  } catch (cause) {
    throw new Error(`Cannot read ${manifestPath} — run "pnpm build" before "pnpm budget".`, {
      cause,
    });
  }
  return JSON.parse(raw);
}

/**
 * Resolve every file (JS + CSS) an entry pulls into its initial payload: itself, its static
 * `imports`, and its `css`, followed transitively. `dynamicImports` are excluded on purpose —
 * those are separate, lazily-loaded chunks, not part of this entry's initial download.
 *
 * `entryKey` accepts more than one manifest key (I1: `/` has no single page module of its own —
 * router.tsx nests a lazy `AppShell` around a lazy, index-routed `HomePage`, so its initial payload
 * is the UNION of both chunks' files) — the array form dedupes across shared imports the same way
 * one entry's own transitive imports already do.
 * @param {Manifest} manifest
 * @param {string | string[]} entryKey
 * @returns {string[]}
 */
export function routeFiles(manifest, entryKey) {
  const entryKeys = Array.isArray(entryKey) ? entryKey : [entryKey];
  for (const key of entryKeys) {
    if (!manifest[key]) {
      throw new Error(
        `No manifest entry for "${key}". Add the route to the build (and ROUTE_ENTRIES) before budgeting it.`,
      );
    }
  }
  const files = new Set();
  const seen = new Set();
  const visit = (key) => {
    if (seen.has(key)) return;
    seen.add(key);
    const chunk = manifest[key];
    if (!chunk) {
      throw new Error(`Manifest is missing a chunk referenced as "${key}".`);
    }
    if (chunk.file) files.add(chunk.file);
    for (const cssFile of chunk.css ?? []) files.add(cssFile);
    for (const importedKey of chunk.imports ?? []) visit(importedKey);
  };
  for (const key of entryKeys) visit(key);
  return [...files];
}

/**
 * Gzipped byte size of a single built file under distDir.
 * @param {string} distDir
 * @param {string} file
 * @returns {Promise<number>}
 */
async function gzipSize(distDir, file) {
  const buf = await readFile(path.join(distDir, file));
  return gzipSync(buf).length;
}

/**
 * Measure the HTML entry (always, independent of `routes`) plus every route in `routes`, gzipped.
 * `routes` defaults to the module's ROUTE_ENTRIES; tests pass a synthetic map instead so a real
 * over-budget route doesn't need to exist yet.
 * @param {Record<string, string | string[]>} routes
 * @param {string} distDir
 * @returns {Promise<MeasureResult[]>}
 */
export async function measure(routes = ROUTE_ENTRIES, distDir = DIST_DIR) {
  const manifest = await loadManifest(distDir);
  const targets = { [HTML_ENTRY]: HTML_ENTRY, ...routes };
  const results = [];
  for (const [label, entryKey] of Object.entries(targets)) {
    const files = routeFiles(manifest, entryKey);
    let bytes = 0;
    for (const file of files) bytes += await gzipSize(distDir, file);
    results.push({ label, files, bytes });
  }
  return results;
}

/**
 * Pure summary of a measure() result: total files resolved and which entries are over budget.
 * @param {MeasureResult[]} results
 * @returns {{ totalFiles: number; overBudget: MeasureResult[] }}
 */
export function summarize(results) {
  const totalFiles = results.reduce((n, r) => n + r.files.length, 0);
  const overBudget = results.filter((r) => r.bytes > BUDGET_BYTES);
  return { totalFiles, overBudget };
}

/**
 * Read the committed baseline (label -> last-recorded gzip bytes). Missing file (first run, or a
 * brand-new checkout before anyone has run `--update`) is not an error — it just means every label
 * reports "no baseline" instead of a delta.
 * @param {string} baselinePath
 * @returns {Promise<Record<string, number>>}
 */
export async function loadBaseline(baselinePath = BASELINE_PATH) {
  try {
    return JSON.parse(await readFile(baselinePath, 'utf-8'));
  } catch {
    return {};
  }
}

/**
 * Write the current measurement as the new baseline, keyed by label. The only caller is `--update`
 * — nothing else rewrites this file, so a route's drift stays visible until someone deliberately
 * decides "yes, this is the new normal."
 * @param {MeasureResult[]} results
 * @param {string} baselinePath
 * @returns {Promise<Record<string, number>>}
 */
export async function writeBaseline(results, baselinePath = BASELINE_PATH) {
  /** @type {Record<string, number>} */
  const data = {};
  for (const { label, bytes } of results) data[label] = bytes;
  await writeFile(baselinePath, `${JSON.stringify(data, null, 2)}\n`);
  return data;
}

/**
 * Pure: attach each result's delta against the baseline. `deltaBytes` is `null` when the baseline
 * has no entry for that label yet (a brand-new route, or no baseline file at all).
 * @param {MeasureResult[]} results
 * @param {Record<string, number>} baseline
 * @returns {(MeasureResult & { deltaBytes: number | null })[]}
 */
export function withDelta(results, baseline) {
  return results.map((r) => ({
    ...r,
    deltaBytes: Object.hasOwn(baseline, r.label) ? r.bytes - baseline[r.label] : null,
  }));
}

/** @param {(MeasureResult & { deltaBytes: number | null })[]} results */
function printReport(results) {
  for (const { label, bytes, files, deltaBytes } of results) {
    const kb = (bytes / 1024).toFixed(1);
    const budgetKb = (BUDGET_BYTES / 1024).toFixed(0);
    const over = bytes > BUDGET_BYTES;
    const delta =
      deltaBytes === null
        ? '(no baseline)'
        : `(${deltaBytes >= 0 ? '+' : ''}${(deltaBytes / 1024).toFixed(1)} KB since baseline)`;
    console.log(
      `${over ? '✗' : '✓'} ${label}: ${kb} KB gzipped (${files.length} file${files.length === 1 ? '' : 's'}) ${delta} — budget ${budgetKb} KB`,
    );
  }
}

async function main() {
  const update = process.argv.includes('--update');

  let results;
  try {
    results = await measure();
  } catch (err) {
    console.error(`budget: ${err.message}`);
    process.exitCode = 1;
    return;
  }

  const { totalFiles, overBudget } = summarize(results);

  if (totalFiles === 0) {
    console.error(
      'budget: resolved zero files — nothing was measured. Check the manifest and ROUTE_ENTRIES.',
    );
    process.exitCode = 1;
    return;
  }

  const baseline = await loadBaseline();
  printReport(withDelta(results, baseline));

  if (update) {
    await writeBaseline(results);
    console.log(`budget: wrote ${BASELINE_PATH} from this measurement.`);
  }

  if (overBudget.length > 0) {
    console.error(`budget: ${overBudget.length} entr${overBudget.length === 1 ? 'y' : 'ies'} over budget.`);
    process.exitCode = 1;
  }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  await main();
}
