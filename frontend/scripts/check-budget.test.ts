// @vitest-environment node
import { randomBytes } from 'node:crypto';
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { BUDGET_BYTES, measure, ROUTE_ENTRIES, routeFiles, summarize } from './check-budget.mjs';

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
  it('measures only the HTML entry when the route map is empty (ships this way in Task 2)', async () => {
    const dir = await makeDist(
      { 'index.html': { file: 'assets/index-abc.js', imports: [], css: [] } },
      { 'assets/index-abc.js': 'console.log(1)' },
    );
    const results = await measure(ROUTE_ENTRIES, dir);
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
    const results = await measure(ROUTE_ENTRIES, dir);
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
