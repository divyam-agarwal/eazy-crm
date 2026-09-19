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
  },
});
