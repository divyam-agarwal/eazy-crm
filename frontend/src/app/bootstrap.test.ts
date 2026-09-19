import { describe, expect, it, vi } from 'vitest';

// Fix round 1, item 1: `renderApp` (src/test/renderApp.tsx) never calls `startApp()` — it
// independently re-creates the query client, runtime and session so tests can inject fakes. That
// means every other test in this app proves a faithful COPY of the composition root works, while
// `startApp()`'s own body — the thing main.tsx actually calls — was exercised by nothing. Deleting
// either `startSession(...)` or `void initI18n()` from `startApp()` left 196/196 green.
//
// Mock both collaborators so this test can assert `startApp()` actually calls them, without paying
// for a real boot (network calls) or a real i18n load.
vi.mock('@/features/auth/session/start', () => ({ startSession: vi.fn(() => ({})) }));
vi.mock('@/lib/i18n', () => ({ initI18n: vi.fn(() => Promise.resolve()) }));

import { startSession } from '@/features/auth/session/start';
import { initI18n } from '@/lib/i18n';
import { startApp } from './bootstrap';

describe('startApp (composition root)', () => {
  it('boots the session and initializes i18n', () => {
    startApp();

    expect(startSession).toHaveBeenCalledTimes(1);
    expect(initI18n).toHaveBeenCalledTimes(1);
  });
});
