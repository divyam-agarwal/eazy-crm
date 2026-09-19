import { render } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { vi } from 'vitest';
import { createSessionRuntime } from '@/app/bootstrap';
import { Providers } from '@/app/providers';
import { createQueryClient } from '@/app/queryClient';
import { appRoutes } from '@/app/router';
import { setAccessToken } from '@/features/auth/session/accessToken';
import { createNoopChannel } from '@/features/auth/session/authChannel';
import { createInMemoryLocks } from '@/features/auth/session/lockProvider';
import { startSession } from '@/features/auth/session/start';
import { useSessionStore } from '@/session/sessionStore';
import type { Me, SessionStatus } from '@/session/types';

export interface RenderAppOptions {
  session?: { status: SessionStatus; me?: Me | null; accessToken?: string };
  boot?: boolean;
}

/**
 * The real router, session and HTTP layer; only the network (MSW) and the runtime's outward edges
 * (broadcast channel, locks, reload, log) are fakes.
 *
 * <p>R17/Testing-4: builds the runtime through `createSessionRuntime` — the SAME function
 * `bootstrap.ts#startApp` uses in production — overriding only what a test must control. That keeps
 * `clearQueryCache: () => queryClient.clear()` real, so a test can prove logout actually clears the
 * cache instead of trusting a binding nothing ever executes.
 */
export function renderApp(path: string, options: RenderAppOptions = {}) {
  const queryClient = createQueryClient();
  const runtime = createSessionRuntime(queryClient, {
    channel: createNoopChannel(),
    locks: createInMemoryLocks(),
    reload: vi.fn(),
    log: vi.fn(),
  });
  const controls = startSession(runtime, { autoBoot: false });
  if (options.session) {
    useSessionStore.setState({ status: options.session.status, me: options.session.me ?? null });
    if (options.session.accessToken) setAccessToken(options.session.accessToken);
  }
  const router = createMemoryRouter(appRoutes, { initialEntries: [path] });
  if (options.boot) void controls.boot.start();
  const user = userEvent.setup();
  const view = render(<Providers router={router} queryClient={queryClient} />);
  return { ...view, router, runtime, controls, queryClient, user };
}
