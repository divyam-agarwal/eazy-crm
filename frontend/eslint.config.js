import { readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import js from '@eslint/js';
import { createTypeScriptImportResolver } from 'eslint-import-resolver-typescript';
import i18next from 'eslint-plugin-i18next';
import importX from 'eslint-plugin-import-x';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import react from 'eslint-plugin-react';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';

// One zone per feature directory, so a new feature is isolated from the others the moment it exists
// (plan decision P12). Read at config load.
const features = readdirSync(fileURLToPath(new URL('./src/features', import.meta.url)), { withFileTypes: true })
  .filter((entry) => entry.isDirectory())
  .map((entry) => entry.name);

const TESTS = ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/**/*.typecheck.ts'];
const BYPASS = 'Requests go through src/api/client.ts, which adds auth, the CSRF header, the timeout and refresh (spec §4.3).';
const ZOD_MSG =
  "Import from 'zod/mini', not 'zod'. Fix round 1: `no-restricted-imports` matches the literal " +
  "specifier string, so banning only 'zod' left 'zod/v3', 'zod/v4' and 'zod/v4/core' as unblocked " +
  'escape hatches to the exact same heavy runtime — zod 4.6.5 ships them as separate export map ' +
  "entries, and zod's own v4 migration docs point an implementer straight at 'zod/v4'. Fix round 2: " +
  "'zod/compile' joined the list too — it looks like a schema-to-function codegen utility with no " +
  'schema-authoring API, but it imports directly from the same v4/core internals as the full runtime ' +
  '(`./v4/core/compile.js`, `./v4/core/index.js`) and measures accordingly. Measured (esbuild --bundle ' +
  '--minify, gzip): zod 93.1 KB and zod/v4 93.1 KB (two-field schema; identical — same code), ' +
  "zod/v3 14.3 KB (two-field schema), zod/v4/core 80.2 KB (bare namespace import), zod/compile " +
  '10.2 KB (bare side-effect import), zod/mini (== zod/v4-mini == `zod/v4/mini`) 4.56 KB (two-field ' +
  'schema). One wrong import can put the entry chunk over budget.';
const NOOP_LOCKS_MSG =
  'noopLocks is a deliberately non-exclusive LockProvider kept only for a recorded red run (Task 15). ' +
  'Production must go through createSessionRuntime, which picks webLocks/createInMemoryLocks.';

// Every zod entry point that resolves to the full (non-mini) runtime, per zod 4.6.5's own `exports`
// map (checked directly — see ZOD_MSG) and measured, not inferred from the name or the shape of its
// public API — 'zod/compile' looked safe by API-shape reasoning alone (fix round 1) and turned out
// not to be (fix round 2: measured 10.2 KB gzip, same v4/core internals as 'zod/v4/core'). Deliberately
// NOT included: `zod/mini`, `zod/v4-mini` and `zod/v4/mini` (all measured identical to zod/mini — the
// whole point) and `zod/locales`/`zod/v4/locales(/*)` (pure re-exported string catalogs, no schema
// builder in their API at all — confirmed by reading the module, not assumed; the project's own i18n
// plans may need these later and they don't retain the validation engine on their own).
//
// Note for a future reader of package.json: 'zod' (not 'zod/mini') is the correct dependencies entry
// even though only the 'zod/mini' subpath may be imported — 'zod/mini' ships inside the same 'zod'
// npm package (see its exports map), it is not a separate package to swap in. Don't "tidy up" the
// dependency to something named zod-mini; it doesn't exist.
const ZOD_HEAVY_PATHS = ['zod', 'zod/v3', 'zod/v4', 'zod/v4/core', 'zod/compile'].map((name) => ({ name, message: ZOD_MSG }));
const OPENAPI_FETCH_PATH = { name: 'openapi-fetch', message: BYPASS };
const NOOP_LOCKS_PATH = {
  name: '@/features/auth/session/lockProvider',
  importNames: ['noopLocks'],
  message: NOOP_LOCKS_MSG,
};

export default tseslint.config(
  { ignores: ['dist', 'reports', 'coverage', 'test-results', 'playwright-report', 'src/api/schema.d.ts'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: { globals: { ...globals.browser, ...globals.node } },
    plugins: { react, 'react-hooks': reactHooks, 'import-x': importX },
    settings: {
      react: { version: 'detect' },
      'import-x/resolver-next': [createTypeScriptImportResolver({ project: './tsconfig.json' })],
    },
    rules: {
      ...react.configs.flat.recommended.rules,
      ...react.configs.flat['jsx-runtime'].rules,
      'react/prop-types': 'off',
      'react/no-danger': 'error',
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'error',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
    },
  },
  { ...jsxA11y.flatConfigs.recommended, files: ['**/*.{ts,tsx}'] },
  // Build and CI scripts (scripts/*.mjs, e2e/scripts/*.mjs) run in Node.
  { files: ['**/*.{js,mjs}'], languageOptions: { globals: globals.node } },
  // R67: bare 'zod' is banned everywhere non-test code lives, including src/api — zod is used for
  // schema validation, not HTTP, so it isn't part of the api-layer's fetch allowance below.
  {
    files: ['src/**/*.{ts,tsx}'],
    ignores: TESTS,
    rules: {
      'no-restricted-imports': ['error', { paths: ZOD_HEAVY_PATHS }],
    },
  },
  {
    files: ['src/**/*.{ts,tsx}'],
    ignores: [...TESTS, 'src/api/**'],
    rules: {
      'no-restricted-globals': ['error', { name: 'fetch', message: BYPASS }, { name: 'XMLHttpRequest', message: BYPASS }],
      'no-restricted-properties': [
        'error',
        { object: 'window', property: 'fetch', message: BYPASS },
        { object: 'globalThis', property: 'fetch', message: BYPASS },
      ],
      // Superset of the zod-only block above: this fileset (everywhere except src/api) also bans
      // importing openapi-fetch directly. ESLint flat config resolves a repeated rule id per file by
      // taking the LAST matching config wholesale, not merging arrays, so every block that narrows
      // `no-restricted-imports` further must repeat the restrictions the narrower fileset still owes.
      'no-restricted-imports': ['error', { paths: [...ZOD_HEAVY_PATHS, OPENAPI_FETCH_PATH] }],
    },
  },
  // R24: bootstrap.ts is the one file allowed to choose which LockProvider production runs with.
  // noopLocks exists only for Task 15's recorded red run and must never reach it. Narrower than (and
  // must repeat) the block above, for the reason noted there.
  {
    files: ['src/app/bootstrap.ts'],
    rules: {
      'no-restricted-imports': ['error', { paths: [...ZOD_HEAVY_PATHS, OPENAPI_FETCH_PATH, NOOP_LOCKS_PATH] }],
    },
  },
  {
    files: ['src/**/*.{ts,tsx}'],
    ignores: TESTS,
    rules: {
      'import-x/no-restricted-paths': [
        'error',
        {
          zones: [
            {
              target: './src/api',
              from: ['./src/lib', './src/components', './src/session', './src/features', './src/app'],
              message: 'api is the lowest layer: it imports nothing else from src.',
            },
            {
              target: ['./src/lib', './src/components'],
              from: ['./src/session', './src/features', './src/app'],
              message: 'lib and components never import session, features or app.',
            },
            // P16: src/session is the shared READ side. It may use api and lib, nothing above.
            {
              target: './src/session',
              from: ['./src/components', './src/features', './src/app'],
              message: 'src/session is below features: it reads the session, it does not drive it.',
            },
            { target: './src/features', from: './src/app', message: 'features never import app.' },
            ...features.map((name) => ({
              target: `./src/features/${name}`,
              from: './src/features',
              except: [`./${name}`],
              message: 'features never import each other (spec §4.3).',
            })),
            // AR-1 (Task 5 architecture review): src/session/sessionStore.ts exports useSessionStore,
            // including `.setState` — a raw write bypass around establishSession/endSession (spec
            // §4.4). Only src/session/useMe.ts itself and src/features/auth/session/** may import the
            // store module directly; every other importer goes through @/session/useMe or the auth
            // feature's session boundary instead. The store can't simply move: useMe and the write
            // side need the same zustand instance, and session → features is a forbidden direction.
            //
            // Fix round 1: './src/session/!(useMe).{ts,tsx}' only matched DIRECT children of
            // src/session — it has no '**', so it silently stopped applying the moment a file moved
            // one level deeper (e.g. a future src/session/hooks/ or src/session/internal/).
            //
            // Fix round 2: the round-1 replacement, './src/session/**/!(useMe).{ts,tsx}', over-
            // corrected — '!(useMe)' there negates on BASENAME AT ANY DEPTH, not on the top-level
            // path, so an impostor 'src/session/something/useMe.ts' was exempted right along with the
            // real top-level 'src/session/useMe.ts', silently defeating AR-1 again for any nested file
            // that happens to share the name. The exemption has to be anchored to the literal depth-0
            // path, not to a basename pattern that repeats at every depth: one entry exempts ONLY
            // direct children other than useMe.ts (depth 0), a second entry unconditionally bans
            // everything nested one level or deeper regardless of name (no basename exemption there
            // at all). Verified against minimatch directly before wiring in: 'src/session/useMe.ts'
            // (real, depth 0) stays exempt; 'src/session/something/useMe.ts' (impostor, depth 1),
            // 'src/session/sub/x.ts' and 'src/session/sub/deeper/y.ts' are all blocked.
            {
              target: [
                './src/app',
                './src/lib',
                './src/components',
                './src/session/!(useMe).{ts,tsx}',
                './src/session/*/**',
                './src/features/auth/*.{ts,tsx}',
                './src/features/auth/!(session)/**',
                ...features.filter((name) => name !== 'auth').map((name) => `./src/features/${name}`),
              ],
              from: './src/session/sessionStore.ts',
              message:
                'sessionStore is reachable only from @/session/useMe.ts and src/features/auth/session/** ' +
                '(AR-1). Read the session through @/session/useMe; drive it through the auth feature.',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['src/features/**/*.tsx', 'src/app/**/*.tsx'],
    ignores: TESTS,
    plugins: { i18next },
    rules: { 'i18next/no-literal-string': ['error', { mode: 'jsx-text-only' }] },
  },
);
