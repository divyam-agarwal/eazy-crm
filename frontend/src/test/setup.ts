import './i18n';
import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { resetAuthBridge } from '@/api/authBridge';
import { clearAccessToken } from '@/features/auth/session/accessToken';
import { stopSession } from '@/features/auth/session/start';
import { resetSessionStoreForTests } from '@/session/sessionStore';
import { server } from './msw';

// jsdom does not implement scrollIntoView at all (the property is undefined, not a no-op), so
// `vi.spyOn(Element.prototype, 'scrollIntoView')` in an individual test (R27) has nothing to wrap.
// Give it a base no-op once per test file's fresh jsdom environment; restoreMocks still undoes any
// per-test spyOn back to this stub, never leaking a real assertion mock into the next file.
if (typeof Element !== 'undefined' && typeof Element.prototype.scrollIntoView !== 'function') {
  Element.prototype.scrollIntoView = () => {};
}

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  // Task 6: a leaked boot/logout — its `online` listeners, its scheduled retry timer — from one
  // test file's startSession() would otherwise run against the next file's fixtures.
  stopSession();
  // R1: several test files opt into `// @vitest-environment node` (no DOM). cleanup() and
  // localStorage are jsdom/happy-dom-only and throw outside a document — guard both so this one
  // global setup file works for every environment.
  if (typeof document !== 'undefined') cleanup();
  server.resetHandlers();
  resetSessionStoreForTests();
  clearAccessToken();
  if (typeof localStorage !== 'undefined') localStorage.clear();
  // Without this, a setAuthBridge() call in one test file leaks into the next test file sharing
  // the same Vitest worker (the module-scoped singleton in authBridge.ts outlives any one file) —
  // green until run order changes. Task 5 calls setAuthBridge(realBridge) at startup.
  resetAuthBridge();
});
afterAll(() => server.close());
