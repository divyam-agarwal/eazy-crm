// @vitest-environment node
import { spawnSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it } from 'vitest';
import {
  BASELINE_PATH,
  BUDGET_BYTES,
  loadBaseline,
  measure,
  ROUTE_ENTRIES,
  routeFiles,
  summarize,
  withDelta,
  writeBaseline,
} from './check-budget.mjs';

const script = fileURLToPath(new URL('./check-budget.mjs', import.meta.url));

const dirsToClean: string[] = [];
afterEach(async () => {
  await Promise.all(dirsToClean.splice(0).map((dir) => rm(dir, { recursive: true, force: true })));
});

/** Write a synthetic dist/ (manifest + asset files) and return its path. */
async function makeDist(
  manifest: Record<string, unknown>,
  files: Record<string, string | Buffer>,
) {
  const dir = await mkdtemp(path.join(tmpdir(), 'budget-'));
  dirsToClean.push(dir);
  await mkdir(path.join(dir, '.vite'), { recursive: true });
  await writeFile(path.join(dir, '.vite', 'manifest.json'), JSON.stringify(manifest));
  for (const [file, content] of Object.entries(files)) {
    await mkdir(path.join(dir, path.dirname(file)), { recursive: true });
    await writeFile(path.join(dir, file), content);
  }
  return dir;
}

/**
 * Write a synthetic project root — `<root>/dist/...` (manifest + files) — for CLI spawns that
 * pass `cwd: root`, since the CLI always reads `dist/` and `budget-baseline.json` relative to
 * `process.cwd()` (it takes no positional distDir argument).
 */
async function makeProjectRoot(
  manifest: Record<string, unknown>,
  files: Record<string, string | Buffer>,
) {
  const root = await mkdtemp(path.join(tmpdir(), 'budget-cli-'));
  dirsToClean.push(root);
  const dir = path.join(root, 'dist');
  await mkdir(path.join(dir, '.vite'), { recursive: true });
  await writeFile(path.join(dir, '.vite', 'manifest.json'), JSON.stringify(manifest));
  for (const [file, content] of Object.entries(files)) {
    await mkdir(path.join(dir, path.dirname(file)), { recursive: true });
    await writeFile(path.join(dir, file), content);
  }
  return root;
}

describe('routeFiles (pure)', () => {
  it('resolves an entry plus its static imports and css, following imports transitively', () => {
    const manifest = {
      'index.html': {
        file: 'assets/index-abc.js',
        imports: ['src/shared.ts'],
        css: ['assets/index-abc.css'],
      },
      'src/shared.ts': { file: 'assets/shared-def.js', imports: [] },
    };
    expect(routeFiles(manifest, 'index.html').sort()).toEqual(
      ['assets/index-abc.css', 'assets/index-abc.js', 'assets/shared-def.js'].sort(),
    );
  });

  it('excludes dynamicImports — those are separate lazily-loaded chunks', () => {
    const manifest = {
      'index.html': { file: 'assets/index-abc.js', imports: [], dynamicImports: ['src/lazy.ts'] },
      'src/lazy.ts': { file: 'assets/lazy-xyz.js', imports: [] },
    };
    expect(routeFiles(manifest, 'index.html')).toEqual(['assets/index-abc.js']);
  });

  it('throws when the entry key is missing from the manifest', () => {
    expect(() => routeFiles({}, '/login')).toThrow(/No manifest entry for "\/login"/);
  });
});

describe('summarize (pure)', () => {
  it('flags entries whose gzipped bytes exceed BUDGET_BYTES', () => {
    const { totalFiles, overBudget } = summarize([
      { label: 'index.html', files: ['a.js'], bytes: 1_000 },
      { label: '/heavy', files: ['b.js'], bytes: BUDGET_BYTES + 1 },
    ]);
    expect(totalFiles).toBe(2);
    expect(overBudget.map((r) => r.label)).toEqual(['/heavy']);
  });

  it('reports zero total files when every result resolved nothing', () => {
    const { totalFiles } = summarize([{ label: 'index.html', files: [], bytes: 0 }]);
    expect(totalFiles).toBe(0);
  });
});

describe('measure', () => {
  // Task 10 gave the real ROUTE_ENTRIES its first entry ('/login'), so this scenario -- an empty
  // route map -- is now exercised with its own synthetic `{}`, the same way the "over-budget route"
  // test below passes a synthetic map instead of relying on ROUTE_ENTRIES (see that test's comment).
  it('measures only the HTML entry when the route map is empty (ships this way in Task 2)', async () => {
    const dir = await makeDist(
      { 'index.html': { file: 'assets/index-abc.js', imports: [], css: [] } },
      { 'assets/index-abc.js': 'console.log(1)' },
    );
    const results = await measure({}, dir);
    expect(results).toHaveLength(1);
    expect(results[0]?.label).toBe('index.html');
    expect(results[0]?.files).toEqual(['assets/index-abc.js']);
    expect(results[0]?.bytes).toBeGreaterThan(0);
  });

  it('throws (missing-manifest) when dist/.vite/manifest.json was never written', async () => {
    const dir = await mkdtemp(path.join(tmpdir(), 'budget-empty-'));
    dirsToClean.push(dir);
    await expect(measure(ROUTE_ENTRIES, dir)).rejects.toThrow(/pnpm build/);
  });

  it('resolves zero files when the HTML entry chunk carries no file, css, or imports', async () => {
    const dir = await makeDist({ 'index.html': { imports: [], css: [] } }, {});
    const results = await measure({}, dir);
    const { totalFiles } = summarize(results);
    expect(totalFiles).toBe(0);
  });

  it('flags an over-budget route using a synthetic map passed to measure() (not ROUTE_ENTRIES)', async () => {
    // Random bytes are incompressible, so the gzipped size stays close to the raw size — unlike
    // repeated characters, which gzip would shrink far below BUDGET_BYTES.
    const big = randomBytes(BUDGET_BYTES + 1024);
    const dir = await makeDist(
      {
        'index.html': { file: 'assets/index-abc.js', imports: [], css: [] },
        '/heavy': { file: 'assets/heavy-def.js', imports: [], css: [] },
      },
      { 'assets/index-abc.js': 'console.log(1)', 'assets/heavy-def.js': big },
    );
    const results = await measure({ '/heavy': '/heavy' }, dir);
    const { overBudget } = summarize(results);
    expect(overBudget.map((r) => r.label)).toEqual(['/heavy']);
  });
});

// R87: absolute-only reporting hides erosion until some later, unrelated commit trips the 200 KB
// ceiling and eats the blame for months of accumulated drift (measured: /login moved 168.8 -> 172.2
// -> 172.6 KB across three tasks that never touched it — the shared entry chunk grew under it each
// time). `withDelta` never fails the build; BUDGET_BYTES stays the only hard gate.
describe('withDelta (pure)', () => {
  it('computes a positive delta when a label grew since the baseline', () => {
    const results = [{ label: '/login', files: ['a.js'], bytes: 2_000 }];
    expect(withDelta(results, { '/login': 1_500 })).toEqual([{ ...results[0], deltaBytes: 500 }]);
  });

  it('computes a negative delta when a label shrank since the baseline', () => {
    const results = [{ label: '/login', files: ['a.js'], bytes: 1_000 }];
    expect(withDelta(results, { '/login': 1_500 })).toEqual([{ ...results[0], deltaBytes: -500 }]);
  });

  it('reports a null delta for a label the baseline has never seen (a brand-new route)', () => {
    const results = [{ label: '/new-route', files: ['a.js'], bytes: 1_000 }];
    expect(withDelta(results, {})).toEqual([{ ...results[0], deltaBytes: null }]);
  });
});

describe('loadBaseline / writeBaseline', () => {
  it('returns {} when the baseline file does not exist yet — first run is not an error', async () => {
    const dir = await mkdtemp(path.join(tmpdir(), 'budget-nobaseline-'));
    dirsToClean.push(dir);
    await expect(loadBaseline(path.join(dir, BASELINE_PATH))).resolves.toEqual({});
  });

  it('round-trips what measure() reported, keyed by label', async () => {
    const dir = await mkdtemp(path.join(tmpdir(), 'budget-writebaseline-'));
    dirsToClean.push(dir);
    const baselinePath = path.join(dir, BASELINE_PATH);
    const results = [
      { label: 'index.html', files: ['a.js'], bytes: 111 },
      { label: '/login', files: ['b.js'], bytes: 222 },
    ];
    await writeBaseline(results, baselinePath);
    await expect(loadBaseline(baselinePath)).resolves.toEqual({ 'index.html': 111, '/login': 222 });
  });
});

// Task 2's fixtures used a synthetic `{ '/login': 'src/Login.tsx' }` route map because the real
// pages (Tasks 10-12) did not exist yet. Now that they do, this exercises the CLI end-to-end with
// no routes argument — i.e. the actual ROUTE_ENTRIES paths `main()` uses in production — which is
// the one thing the synthetic-map tests above cannot catch: a real route path that's missing or
// misspelled in ROUTE_ENTRIES, or a real over-budget route not being named in the CLI's output.
describe('CLI — over budget on a real route (Task 13)', () => {
  it('exits 1 and names /signup as OVER BUDGET while leaving /login and /invite alone', async () => {
    const big = randomBytes(BUDGET_BYTES + 1024);
    const manifest: Record<string, unknown> = {
      'index.html': { file: 'assets/index.js', imports: [], css: [] },
    };
    manifest[ROUTE_ENTRIES['/login']] = { file: 'assets/login.js', imports: [] };
    manifest[ROUTE_ENTRIES['/signup']] = { file: 'assets/signup.js', imports: [] };
    manifest[ROUTE_ENTRIES['/invite/:token']] = { file: 'assets/invite.js', imports: [] };
    const root = await makeProjectRoot(manifest, {
      'assets/index.js': 'console.log(1)',
      'assets/login.js': 'console.log(1)',
      'assets/signup.js': big,
      'assets/invite.js': 'console.log(1)',
    });
    const run = spawnSync(process.execPath, [script], { cwd: root, encoding: 'utf8' });
    expect(run.status).toBe(1);
    expect(run.stdout).toMatch(/✗ \/signup: .* — budget 200 KB/);
    expect(run.stdout).toMatch(/✓ \/login: /);
    expect(run.stdout).toMatch(/✓ \/invite\/:token: /);
    expect(run.stderr).toMatch(/1 entry over budget/);
  });
});

// The CLI always measures the HTML entry plus every real ROUTE_ENTRIES route (main() calls
// measure() with no arguments), so every baseline-delta fixture below must give each of them a
// manifest entry too — not just 'index.html' — or routeFiles() throws on the missing routes.
const REAL_ROUTES_MANIFEST: Record<string, unknown> = {
  'index.html': { file: 'assets/index.js', imports: [], css: [] },
};
REAL_ROUTES_MANIFEST[ROUTE_ENTRIES['/login']] = { file: 'assets/login.js', imports: [] };
REAL_ROUTES_MANIFEST[ROUTE_ENTRIES['/signup']] = { file: 'assets/signup.js', imports: [] };
REAL_ROUTES_MANIFEST[ROUTE_ENTRIES['/invite/:token']] = { file: 'assets/invite.js', imports: [] };
const REAL_ROUTES_FILES = {
  'assets/index.js': 'x'.repeat(2_000),
  'assets/login.js': 'y',
  'assets/signup.js': 'y',
  'assets/invite.js': 'y',
};

describe('CLI — baseline delta (R87)', () => {
  it('prints a delta against a committed baseline, and does not fail the build on drift alone', async () => {
    const root = await makeProjectRoot(REAL_ROUTES_MANIFEST, REAL_ROUTES_FILES);
    // Seed a baseline well below the current measurement so a clear positive delta shows up.
    await writeFile(path.join(root, BASELINE_PATH), JSON.stringify({ 'index.html': 5 }));
    const run = spawnSync(process.execPath, [script], { cwd: root, encoding: 'utf8' });
    expect(run.status).toBe(0);
    expect(run.stdout).toMatch(/index\.html: .* \(\+\d+(\.\d+)? KB since baseline\)/);
  });

  it('reports "no baseline" for a label absent from budget-baseline.json, without failing', async () => {
    const root = await makeProjectRoot(REAL_ROUTES_MANIFEST, REAL_ROUTES_FILES);
    const run = spawnSync(process.execPath, [script], { cwd: root, encoding: 'utf8' });
    expect(run.status).toBe(0);
    expect(run.stdout).toMatch(/index\.html: .* \(no baseline\)/);
  });

  it('--update writes the current measurement as the new baseline', async () => {
    const root = await makeProjectRoot(REAL_ROUTES_MANIFEST, REAL_ROUTES_FILES);
    const run = spawnSync(process.execPath, [script, '--update'], { cwd: root, encoding: 'utf8' });
    expect(run.status).toBe(0);
    const baseline = JSON.parse(await readFile(path.join(root, BASELINE_PATH), 'utf-8')) as Record<
      string,
      number
    >;
    expect(baseline['index.html']).toBeGreaterThan(0);
    expect(baseline['/login']).toBeGreaterThan(0);
  });
});
