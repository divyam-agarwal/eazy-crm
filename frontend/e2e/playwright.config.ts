import { defineConfig, devices } from '@playwright/test';

const MAIN_API = 18080;
const REFRESH_API = 18081;
// Testing-9: NOT Vite preview's default 4173. A leftover `pnpm preview` from a manual check
// (Task 8 Step 7 runs one) listens there, proxies to :8080, and `reuseExistingServer` would
// silently adopt it — the suite would then test a different backend than it started.
const MAIN_WEB = 41731;
const REFRESH_WEB = 41741;
const reuse = false; // always start our own; the cost is a few seconds, the failure mode is silent

// Commands run from frontend/ (cwd '..' is relative to this file).
const backend = (apiPort: number, webPort: number, extra: string[] = []) => ({
  // PUBLIC_BASE_URL = the preview origin, so the backend-minted acceptUrl is a link this suite can open (spec §6.4).
  command: ['bash e2e/scripts/run-backend.sh', String(apiPort), `--easycrm.public-base-url=http://localhost:${webPort}`, ...extra].join(' '),
  url: `http://localhost:${apiPort}/actuator/health`,
  cwd: '..',
  timeout: 180_000,
  reuseExistingServer: reuse,
});

const preview = (webPort: number, apiPort: number) => ({
  command: `pnpm exec vite preview --port ${webPort} --strictPort`,
  url: `http://localhost:${webPort}/login`,
  cwd: '..',
  env: { E2E_API_TARGET: `http://localhost:${apiPort}` },
  timeout: 60_000,
  reuseExistingServer: reuse,
});

export default defineConfig({
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  forbidOnly: Boolean(process.env.CI),
  reporter: [
    ['list'],
    ['json', { outputFile: '../test-results/e2e-results.json' }],
    ['html', { open: 'never', outputFolder: '../playwright-report' }],
  ],
  outputDir: '../test-results/artifacts',
  use: { ...devices['Desktop Chrome'], trace: 'retain-on-failure' },
  projects: [
    { name: 'e2e-main', testDir: './main', use: { baseURL: `http://localhost:${MAIN_WEB}` } },
    { name: 'e2e-refresh', testDir: './refresh', use: { baseURL: `http://localhost:${REFRESH_WEB}` } },
  ],
  webServer: [
    backend(MAIN_API, MAIN_WEB),
    backend(REFRESH_API, REFRESH_WEB, ['--easycrm.jwt.access-ttl-seconds=5']),
    preview(MAIN_WEB, MAIN_API),
    preview(REFRESH_WEB, REFRESH_API),
  ],
});
