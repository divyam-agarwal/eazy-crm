import type { QueryClient } from '@tanstack/react-query';
import { createAuthChannel } from '@/features/auth/session/authChannel';
import { createInMemoryLocks, supportsWebLocks, webLocks } from '@/features/auth/session/lockProvider';
import type { SessionRuntime } from '@/features/auth/session/runtime';
import { startSession } from '@/features/auth/session/start';
import { initI18n } from '@/lib/i18n';
import { createQueryClient } from './queryClient';
import { createAppRouter } from './router';

/**
 * Testing-4: the production wiring lives here so `renderApp` can use it too, with overrides.
 * When `renderApp` built its own runtime, `clearQueryCache: () => queryClient.clear()` was the one
 * binding no test ever executed — and that binding is what stops the next user on a shared counter
 * phone from seeing the previous user's data (challenge #84).
 */
export function createSessionRuntime(
  queryClient: QueryClient,
  overrides: Partial<SessionRuntime> = {},
): SessionRuntime {
  return {
    clearQueryCache: () => queryClient.clear(),
    channel: createAuthChannel(),
    // Browsers without Web Locks serialize per tab only; every browser this product targets has them.
    locks: supportsWebLocks() ? webLocks : createInMemoryLocks(),
    reload: () => window.location.reload(),
    log: (message) => console.error(`[easycrm] ${message}`),
    ...overrides,
  };
}

export function startApp() {
  void initI18n();
  const queryClient = createQueryClient();
  startSession(createSessionRuntime(queryClient));
  return { queryClient, router: createAppRouter() };
}
