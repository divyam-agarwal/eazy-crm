import { fileURLToPath, URL } from 'node:url';
import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { visualizer } from 'rollup-plugin-visualizer';
import { defineConfig } from 'vitest/config';

// Spec 2026-09-14-f0 §4.9. The deployed header belongs to the hosting layer (item 5 / SP2); vite
// preview sends it so E2E surfaces any violation during F0.
export const CSP = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self'",
  "object-src 'none'",
  "base-uri 'self'",
  "frame-ancestors 'none'",
  "connect-src 'self'",
].join('; ');

// E2E runs two backends; each vite preview instance proxies to one of them (Task 14).
const apiTarget = process.env.E2E_API_TARGET ?? 'http://localhost:8080';
const proxy = { '/api': { target: apiTarget, changeOrigin: false } };

// Step 6b (P18/Performance-2): `ANALYZE=true pnpm build` writes a treemap of the production
// bundle so a task that pushes a route over budget can see why, not just that it did. Task 13
// adds the `raw-data` sibling: `scripts/dependency-sizes.mjs` reads its per-module gzip sizes to
// build the DEPENDENCIES.md ledger from the real production build, not from `pnpm add`-time sizes.
const analyze = process.env.ANALYZE === 'true';

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    analyze &&
      visualizer({ filename: 'reports/bundle-stats.html', gzipSize: true, brotliSize: true }),
    analyze &&
      visualizer({ filename: 'reports/bundle.json', template: 'raw-data', gzipSize: true }),
  ],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: { proxy },
  preview: { proxy, headers: { 'Content-Security-Policy': CSP, 'Referrer-Policy': 'no-referrer' } },
  build: { manifest: true },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}', 'scripts/**/*.test.ts'],
    restoreMocks: true,
    testTimeout: 10_000,
    coverage: {
      provider: 'v8',
      reporter: ['text-summary', 'html'],
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/api/schema.d.ts', 'src/test/**', 'src/**/*.test.{ts,tsx}', 'src/**/*.typecheck.ts', 'src/main.tsx', 'src/components/ui/**'],
      // Task 17 Step 1 (spec §6.1): floor taken from the measured baseline (Statements 97.41 /
      // Branches 92.51 / Functions 96.81 / Lines 98.44 on 303 tests / 33 files), each floored to a
      // whole percent — mirrors how the backend's JaCoCo floors were set from a measured baseline.
      // A whole-percent floor catches a real regression without failing on run-to-run noise from
      // the fractional part. Proven to bite: temporarily setting lines to 100 produced
      // "ERROR: Coverage for lines (98.44%) does not meet global threshold (100%)".
      thresholds: { statements: 97, branches: 92, functions: 96, lines: 98 },
    },
  },
});
