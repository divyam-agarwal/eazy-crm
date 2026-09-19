// @vitest-environment node
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { ESLint, type Linter } from 'eslint';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';

// A cold ESLint start with typescript-eslint and the TypeScript import resolver can exceed the 10s
// default test timeout on a CI runner.
vi.setConfig({ testTimeout: 60_000 });

// Every gate in spec §4.3/§4.7/§4.9 is proven by linting a deliberately violating snippet at a real
// path. A second feature is created on disk so "features never import each other" has a target;
// eslint.config.js generates one zone per directory under src/features at load time.
//
// R6: the fixture directory is `zz-lint-fixture` EVERYWHERE in this file. A directory named
// `zz-fixture` is never created on disk, so no zone would be generated for it and any assertion
// written against that path would pass vacuously (it would never even reach the rule).
const root = fileURLToPath(new URL('../..', import.meta.url));
const fixtureFeature = `${root}/src/features/zz-lint-fixture`;
let eslint: ESLint;

beforeAll(() => {
  mkdirSync(fixtureFeature, { recursive: true });
  writeFileSync(`${fixtureFeature}/thing.ts`, 'export const thing = 1;\n');
  eslint = new ESLint({ cwd: root });
});

afterAll(() => rmSync(fixtureFeature, { recursive: true, force: true }));

// Testing-8: `not.toContain(ruleId)` also passes when the snippet never parsed, or when the
// TypeScript resolver could not resolve an `@/…` specifier (no-restricted-paths silently ignores an
// unresolved import). Every allow-case below asserts `fatal` is empty so a parse/resolution failure
// can never be misread as "the rule allowed it".
async function lintOnce(path: string, code: string): Promise<{ ids: (string | null)[]; fatal: Linter.LintMessage[] }> {
  const [result] = await eslint.lintText(code, { filePath: `${root}/${path}` });
  const messages = result?.messages ?? [];
  return {
    ids: messages.map((m) => m.ruleId),
    fatal: messages.filter((m) => m.fatal === true || m.ruleId === null),
  };
}

describe('layer boundaries', () => {
  it.each([
    ['a feature importing app', 'src/features/auth/x.ts', "import { appRoutes } from '@/app/router';\nexport const r = appRoutes;\n"],
    [
      'one feature importing another',
      'src/features/zz-lint-fixture/y.ts',
      "import { getAccessToken } from '@/features/auth/session/accessToken';\nexport const g = getAccessToken;\n",
    ],
    ['auth importing the fixture feature', 'src/features/auth/z.ts', "import { thing } from '../zz-lint-fixture/thing';\nexport const t = thing;\n"],
    ['lib importing a feature', 'src/lib/x.ts', "import { getAccessToken } from '@/features/auth/session/accessToken';\nexport const g = getAccessToken;\n"],
    ['api importing lib', 'src/api/x.ts', "import { readLastWorkspace } from '@/lib/storage';\nexport const r = readLastWorkspace;\n"],
  ])('rejects %s', async (_label, path, code) => {
    const { ids } = await lintOnce(path, code);
    expect(ids).toContain('import-x/no-restricted-paths');
  });

  it('allows a feature importing lib and api (non-vacuity)', async () => {
    const { ids, fatal } = await lintOnce(
      'src/features/auth/ok.ts',
      "import { api } from '@/api/client';\nimport { safeNext } from '@/lib/safeNext';\nexport const both = [api, safeNext];\n",
    );
    expect(fatal).toEqual([]);
    expect(ids).not.toContain('import-x/no-restricted-paths');
  });
});

describe('no request bypasses src/api', () => {
  it('rejects a raw fetch in a feature', async () => {
    const { ids } = await lintOnce('src/features/auth/x.ts', "export const go = () => fetch('/api/v1/customers');\n");
    expect(ids).toContain('no-restricted-globals');
  });

  it('rejects globalThis.fetch in a feature', async () => {
    const { ids } = await lintOnce('src/features/auth/x.ts', "export const go = () => globalThis.fetch('/api');\n");
    expect(ids).toContain('no-restricted-properties');
  });

  it('rejects importing openapi-fetch outside src/api', async () => {
    const { ids } = await lintOnce('src/app/x.ts', "import createClient from 'openapi-fetch';\nexport const c = createClient;\n");
    expect(ids).toContain('no-restricted-imports');
  });

  it('allows fetch inside src/api (non-vacuity)', async () => {
    const { ids, fatal } = await lintOnce('src/api/x.ts', "export const go = () => fetch('/x');\n");
    expect(fatal).toEqual([]);
    expect(ids).not.toContain('no-restricted-globals');
  });

  // P16: the boundary F1 will lean on. A feature reads the session through @/session and never
  // reaches into features/auth for it. Uses the real fixture feature directory (R6) so the zone
  // this assertion depends on actually exists.
  it('lets any feature import @/session but not features/auth/session', async () => {
    const readSide = await lintOnce(
      'src/features/zz-lint-fixture/X.ts',
      "import { useMe } from '@/session/useMe';\nexport const go = () => useMe();\n",
    );
    expect(readSide.fatal).toEqual([]);
    expect(readSide.ids).not.toContain('import-x/no-restricted-paths');

    const writeSide = await lintOnce(
      'src/features/zz-lint-fixture/Y.ts',
      "import { endSession } from '@/features/auth/session/session';\nexport const go = () => endSession('logout');\n",
    );
    expect(writeSide.ids).toContain('import-x/no-restricted-paths');
  });

  // `no-restricted-paths` silently ignores an import it cannot resolve (Testing-8), so the target
  // here must be a real file — `@/features/auth/pages/LoginPage` does not exist until Task 10 and
  // would make this assertion pass vacuously (the rule never runs at all, not "allows it").
  it('stops src/session importing a feature (non-vacuity for its own zone)', async () => {
    const { ids } = await lintOnce(
      'src/session/x.ts',
      "import { getAccessToken } from '@/features/auth/session/accessToken';\nexport const t = getAccessToken;\n",
    );
    expect(ids).toContain('import-x/no-restricted-paths');
  });
});

// AR-1 (Task 5 architecture review): src/session/sessionStore.ts exports useSessionStore, including
// .setState — a raw write bypass around establishSession/endSession (spec §4.4). Only
// src/session/useMe.ts itself and src/features/auth/session/** may import it.
describe('AR-1: sessionStore is reachable only from useMe.ts and features/auth/session', () => {
  it('rejects a non-auth feature importing sessionStore directly', async () => {
    const { ids } = await lintOnce(
      'src/features/zz-lint-fixture/x.ts',
      "import { useSessionStore } from '@/session/sessionStore';\nexport const s = useSessionStore;\n",
    );
    expect(ids).toContain('import-x/no-restricted-paths');
  });

  it('rejects a file inside features/auth but outside features/auth/session', async () => {
    const { ids } = await lintOnce(
      'src/features/auth/pages/LoginPage.ts',
      "import { useSessionStore } from '@/session/sessionStore';\nexport const s = useSessionStore;\n",
    );
    expect(ids).toContain('import-x/no-restricted-paths');
  });

  it('rejects app and lib importing sessionStore', async () => {
    const app = await lintOnce('src/app/x.ts', "import { useSessionStore } from '@/session/sessionStore';\nexport const s = useSessionStore;\n");
    expect(app.ids).toContain('import-x/no-restricted-paths');

    const lib = await lintOnce('src/lib/x.ts', "import { useSessionStore } from '@/session/sessionStore';\nexport const s = useSessionStore;\n");
    expect(lib.ids).toContain('import-x/no-restricted-paths');
  });

  it('allows features/auth/session and session/useMe.ts to import sessionStore (non-vacuity)', async () => {
    const writeSide = await lintOnce(
      'src/features/auth/session/x.ts',
      "import { useSessionStore } from '@/session/sessionStore';\nexport const s = useSessionStore;\n",
    );
    expect(writeSide.fatal).toEqual([]);
    expect(writeSide.ids).not.toContain('import-x/no-restricted-paths');

    const readSide = await lintOnce('src/session/useMe.ts', "import { useSessionStore } from './sessionStore';\nexport const s = useSessionStore;\n");
    expect(readSide.fatal).toEqual([]);
    expect(readSide.ids).not.toContain('import-x/no-restricted-paths');
  });

  // Fix round 1 (Important #2): the target glob was `./src/session/!(useMe).{ts,tsx}` — no `**`, so
  // it matched only DIRECT children of src/session and silently stopped applying one level deeper.
  // src/session is flat today (four files, no subdirectories), which is exactly why this passed
  // unnoticed: nothing on disk could ever exercise the gap. Unlike the "features never import each
  // other" zones, this zone is hand-written, not generated from a readdirSync of real subdirectories
  // — so, same as every other reject-case in this file that targets a path with no zone-generation
  // dependency (e.g. 'src/lib/x.ts', 'src/app/x.ts' above), a purely virtual nested path is enough to
  // prove the rule fires; no on-disk fixture directory is needed the way zz-lint-fixture is needed
  // for the features zone (that one only exists because the zone ITSELF is generated by reading
  // src/features at config-load time).
  it('rejects sessionStore imported from a nested file under src/session (non-vacuity for depth)', async () => {
    const oneLevel = await lintOnce(
      'src/session/sub/x.ts',
      "import { useSessionStore } from '@/session/sessionStore';\nexport const s = useSessionStore;\n",
    );
    expect(oneLevel.ids).toContain('import-x/no-restricted-paths');

    const twoLevels = await lintOnce(
      'src/session/sub/deeper/y.ts',
      "import { useSessionStore } from '@/session/sessionStore';\nexport const s = useSessionStore;\n",
    );
    expect(twoLevels.ids).toContain('import-x/no-restricted-paths');
  });
});

// R24: nothing today stops noopLocks (a deliberately non-exclusive LockProvider kept only for
// Task 15's recorded red run) from being wired into production through bootstrap.ts.
describe('R24: bootstrap.ts never imports noopLocks', () => {
  it('rejects noopLocks imported into bootstrap.ts', async () => {
    const { ids } = await lintOnce(
      'src/app/bootstrap.ts',
      "import { noopLocks } from '@/features/auth/session/lockProvider';\nexport const l = noopLocks;\n",
    );
    expect(ids).toContain('no-restricted-imports');
  });

  it('allows the real lock providers in bootstrap.ts (non-vacuity)', async () => {
    const { ids, fatal } = await lintOnce(
      'src/app/bootstrap.ts',
      "import { webLocks, createInMemoryLocks } from '@/features/auth/session/lockProvider';\nexport const l = [webLocks, createInMemoryLocks];\n",
    );
    expect(fatal).toEqual([]);
    expect(ids).not.toContain('no-restricted-imports');
  });
});

// R67: `import { z } from 'zod'` retains the whole zod runtime (92.2 KB gzip for a two-field schema
// vs 5.05 KB via `zod/mini`) — 69.2 KB of headroom against the 130.8/200 KB budget means one wrong
// import puts the entry chunk alone over budget.
//
// Fix round 1 (Important #1): banning only the bare 'zod' specifier left 'zod/v3', 'zod/v4' and
// 'zod/v4/core' unblocked — zod 4.6.5's own export map exposes each as a separate full-runtime entry
// point, and 'zod/v4' measures byte-for-byte identical to bare 'zod' (it's the same code). zod's own
// v4 migration docs point an implementer at 'zod/v4' by name, reproducing the exact hazard this rule
// exists to close. Every banned spelling gets its own reject case below, and 'zod/v4/mini' gets an
// explicit allow case so a future regression to a broad glob (e.g. banning all of 'zod/v4/**') would
// be caught here instead of silently blocking the light variant too.
describe("R67: only zod's mini entry points may be imported, never a full-runtime one", () => {
  it.each([
    ['zod', 'zod'],
    ['zod/v3', 'zod/v3'],
    ['zod/v4', 'zod/v4'],
    ['zod/v4/core', 'zod/v4/core'],
  ])('rejects %s in a feature', async (_label, specifier) => {
    const { ids } = await lintOnce('src/features/auth/x.ts', `import { z } from '${specifier}';\nexport const s = z;\n`);
    expect(ids).toContain('no-restricted-imports');
  });

  it('rejects a bare zod import inside src/api too', async () => {
    const { ids } = await lintOnce('src/api/x.ts', "import { z } from 'zod';\nexport const s = z;\n");
    expect(ids).toContain('no-restricted-imports');
  });

  it('allows zod/mini (non-vacuity)', async () => {
    const { ids, fatal } = await lintOnce('src/features/auth/x.ts', "import { z } from 'zod/mini';\nexport const s = z;\n");
    expect(fatal).toEqual([]);
    expect(ids).not.toContain('no-restricted-imports');
  });

  it('allows zod/v4/mini (non-vacuity — guards against an over-broad zod/v4/** ban)', async () => {
    const { ids, fatal } = await lintOnce(
      'src/features/auth/x.ts',
      "import { object, string } from 'zod/v4/mini';\nexport const s = object({ a: string() });\n",
    );
    expect(fatal).toEqual([]);
    expect(ids).not.toContain('no-restricted-imports');
  });
});

describe('JSX gates', () => {
  it('rejects an <img> without alt', async () => {
    const { ids } = await lintOnce('src/features/auth/X.tsx', 'export const X = () => <img src="/a.png" />;\n');
    expect(ids).toContain('jsx-a11y/alt-text');
  });

  // eslint-plugin-i18next exempts any ALL-CAPS-named declaration from the literal-string check (its
  // VariableDeclarator handler treats it as a constant, e.g. `const A_B = 'test'`) — and a single
  // uppercase letter satisfies that same regex (`/^[A-Z_-]+$/`). `const X = ...` would silently
  // exempt its whole body, JSX included, making this assertion pass vacuously regardless of whether
  // the rule works. Use a component name that isn't all-caps.
  it('rejects literal JSX text in features and app', async () => {
    const feature = await lintOnce('src/features/auth/Greeting.tsx', 'export const Greeting = () => <p>Hello</p>;\n');
    expect(feature.ids).toContain('i18next/no-literal-string');

    const app = await lintOnce('src/app/Greeting.tsx', 'export const Greeting = () => <p>Hello</p>;\n');
    expect(app.ids).toContain('i18next/no-literal-string');
  });

  it('allows expression text, and literal text in tests (non-vacuity)', async () => {
    const expr = await lintOnce(
      'src/features/auth/X.tsx',
      'export const X = ({ label }: { label: string }) => <p>{label}</p>;\n',
    );
    expect(expr.fatal).toEqual([]);
    expect(expr.ids).not.toContain('i18next/no-literal-string');

    const test = await lintOnce('src/features/auth/X.test.tsx', 'export const X = () => <p>Hello</p>;\n');
    expect(test.fatal).toEqual([]);
    expect(test.ids).not.toContain('i18next/no-literal-string');
  });

  it('rejects dangerouslySetInnerHTML', async () => {
    const { ids } = await lintOnce(
      'src/features/auth/X.tsx',
      'export const X = ({ html }: { html: string }) => <div dangerouslySetInnerHTML={{ __html: html }} />;\n',
    );
    expect(ids).toContain('react/no-danger');
  });
});
