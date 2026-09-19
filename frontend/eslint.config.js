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
  "Import from 'zod/mini', not 'zod'. The bare 'zod' namespace retains the whole runtime " +
  '(measured: a two-field schema is 92.2 KB gzip via `zod`, 5.05 KB via `zod/mini`) — one wrong ' +
  'import can put the entry chunk over budget.';
const NOOP_LOCKS_MSG =
  'noopLocks is a deliberately non-exclusive LockProvider kept only for a recorded red run (Task 15). ' +
  'Production must go through createSessionRuntime, which picks webLocks/createInMemoryLocks.';

const ZOD_PATH = { name: 'zod', message: ZOD_MSG };
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
      'no-restricted-imports': ['error', { paths: [ZOD_PATH] }],
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
      'no-restricted-imports': ['error', { paths: [ZOD_PATH, OPENAPI_FETCH_PATH] }],
    },
  },
  // R24: bootstrap.ts is the one file allowed to choose which LockProvider production runs with.
  // noopLocks exists only for Task 15's recorded red run and must never reach it. Narrower than (and
  // must repeat) the block above, for the reason noted there.
  {
    files: ['src/app/bootstrap.ts'],
    rules: {
      'no-restricted-imports': ['error', { paths: [ZOD_PATH, OPENAPI_FETCH_PATH, NOOP_LOCKS_PATH] }],
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
            {
              target: [
                './src/app',
                './src/lib',
                './src/components',
                './src/session/!(useMe).{ts,tsx}',
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
