import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { server } from './msw';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  // R1: several test files opt into `// @vitest-environment node` (no DOM). cleanup() and
  // localStorage are jsdom/happy-dom-only and throw outside a document — guard both so this one
  // global setup file works for every environment.
  if (typeof document !== 'undefined') cleanup();
  if (typeof localStorage !== 'undefined') localStorage.clear();
  server.resetHandlers();
});
afterAll(() => server.close());
