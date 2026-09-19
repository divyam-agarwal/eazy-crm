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
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { gzipSync } from 'node:zlib';

/** Per-route JS+CSS budget, gzipped bytes. */
export const BUDGET_BYTES = 200 * 1024;

const DIST_DIR = 'dist';
const HTML_ENTRY = 'index.html';

// Each page task adds its own entry — the missing-key throw in routeFiles() is what forces that.
// Task 10 -> '/login', Task 11 -> '/signup', Task 12 -> '/invite/:token'.
/** @type {Record<string, string>} */
export const ROUTE_ENTRIES = {
  '/login': 'src/features/auth/pages/LoginPage.tsx',
  '/signup': 'src/features/auth/pages/SignupPage.tsx',
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
 * @param {Manifest} manifest
 * @param {string} entryKey
 * @returns {string[]}
 */
export function routeFiles(manifest, entryKey) {
  if (!manifest[entryKey]) {
    throw new Error(
      `No manifest entry for "${entryKey}". Add the route to the build (and ROUTE_ENTRIES) before budgeting it.`,
    );
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
  visit(entryKey);
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
 * @param {Record<string, string>} routes
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

/** @param {MeasureResult[]} results */
function printReport(results) {
  for (const { label, bytes, files } of results) {
    const kb = (bytes / 1024).toFixed(1);
    const budgetKb = (BUDGET_BYTES / 1024).toFixed(0);
    const over = bytes > BUDGET_BYTES;
    console.log(
      `${over ? '✗' : '✓'} ${label}: ${kb} KB gzipped (${files.length} file${files.length === 1 ? '' : 's'}) — budget ${budgetKb} KB`,
    );
  }
}

async function main() {
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

  printReport(results);

  if (overBudget.length > 0) {
    console.error(`budget: ${overBudget.length} entr${overBudget.length === 1 ? 'y' : 'ies'} over budget.`);
    process.exitCode = 1;
  }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  await main();
}
