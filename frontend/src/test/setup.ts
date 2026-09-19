import './i18n';
import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { resetAuthBridge } from '@/api/authBridge';
import { clearAccessToken } from '@/features/auth/session/accessToken';
import { resetSessionStore } from '@/session/sessionStore';
import { server } from './msw';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  // R1: several test files opt into `// @vitest-environment node` (no DOM). cleanup() and
  // localStorage are jsdom/happy-dom-only and throw outside a document — guard both so this one
  // global setup file works for every environment.
  if (typeof document !== 'undefined') cleanup();
  server.resetHandlers();
  resetSessionStore();
  clearAccessToken();
  if (typeof localStorage !== 'undefined') localStorage.clear();
  // Without this, a setAuthBridge() call in one test file leaks into the next test file sharing
  // the same Vitest worker (the module-scoped singleton in authBridge.ts outlives any one file) —
  // green until run order changes. Task 5 calls setAuthBridge(realBridge) at startup.
  resetAuthBridge();
});
afterAll(() => server.close());
