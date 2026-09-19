import { QueryClientProvider, onlineManager } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import i18next from 'i18next';
import type { BackendModule } from 'i18next';
import { HttpResponse, http as mswHttp } from 'msw';
import { Suspense } from 'react';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, describe, expect, it } from 'vitest';
import { appRoutes } from '@/app/router';
import { createQueryClient } from '@/app/queryClient';
import { RouteErrorBoundary } from '@/app/RouteErrorBoundary';
import { RouteSkeleton } from '@/app/RouteSkeleton';
import authResource from '@/locales/en/auth.json';
import commonResource from '@/locales/en/common.json';
import { withImportRetry } from '@/lib/lazyImport';
import { useSessionStore } from '@/session/sessionStore';
import { errorBody, ownerMe, ownerSession } from '@/test/fixtures';
import { holdCookieLock } from '@/test/locks';
import { server } from '@/test/msw';
import { http } from '@/test/openapiHttp';
import { renderApp } from '@/test/renderApp';

const anonymous = { status: 'anonymous' as const };

async function fillAndSubmit(user: ReturnType<typeof renderApp>['user'], values = { slug: 'ravi-traders', email: 'ravi@shop.in', password: 'correct-horse-9' }) {
  const workspace = await screen.findByLabelText('Workspace');
  await user.clear(workspace);
  await user.type(workspace, values.slug);
  await user.type(screen.getByLabelText('Email'), values.email);
  await user.type(screen.getByLabelText('Password'), values.password);
  await user.click(screen.getByRole('button', { name: 'Sign in' }));
}

describe('LoginPage', () => {
  it('shows one generic message on 401 and does not redirect', async () => {
    server.use(http.post('/api/v1/auth/login', ({ response }) => response(401).json(errorBody('UNAUTHORIZED', 'invalid credentials'))));
    const { user, router } = renderApp('/login', { session: anonymous });

    await fillAndSubmit(user);

    expect(await screen.findByText('Workspace, email or password is incorrect.')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
    expect(document.title).toBe('Sign in · EasyCRM');
  });

  it('pre-fills the workspace remembered on this device', async () => {
    localStorage.setItem('easycrm.lastWorkspace', 'ravi-traders');
    renderApp('/login', { session: anonymous });
    expect(await screen.findByLabelText('Workspace')).toHaveValue('ravi-traders');
  });

  it('lowercases the workspace as it is typed', async () => {
    const { user } = renderApp('/login', { session: anonymous });
    await user.type(await screen.findByLabelText('Workspace'), 'Ravi-Traders');
    expect(screen.getByLabelText('Workspace')).toHaveValue('ravi-traders');
  });

  it('signs in, remembers the workspace and goes to a safe next', async () => {
    server.use(http.post('/api/v1/auth/login', ({ response }) => response(200).json(ownerSession)));
    const { user, router } = renderApp(`/login?next=${encodeURIComponent('/nope?x=1')}`, { session: anonymous });

    await fillAndSubmit(user);

    await waitFor(() => expect(router.state.location.pathname).toBe('/nope'));
    expect(router.state.location.search).toBe('?x=1');
    expect(useSessionStore.getState().me?.email).toBe('ravi@shop.in');
    expect(localStorage.getItem('easycrm.lastWorkspace')).toBe('ravi-traders');
  });

  it('ignores an unsafe next and goes home', async () => {
    server.use(http.post('/api/v1/auth/login', ({ response }) => response(200).json(ownerSession)));
    const { user } = renderApp(`/login?next=${encodeURIComponent('//evil.com')}`, { session: anonymous });

    await fillAndSubmit(user);

    expect(await screen.findByText('Signed in to ravi-traders as ravi@shop.in (Owner)')).toBeInTheDocument();
  });

  it('redirects a signed-in user away from /login', async () => {
    const { router } = renderApp('/login', { session: { status: 'authenticated', me: ownerMe, accessToken: 't' } });
    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
  });

  it('explains a 429 with Retry-After', async () => {
    server.use(
      mswHttp.post('*/api/v1/auth/login', () =>
        HttpResponse.json(errorBody('RATE_LIMITED', 'too many requests'), { status: 429, headers: { 'Retry-After': '12' } }),
      ),
    );
    const { user } = renderApp('/login', { session: anonymous });
    await fillAndSubmit(user);
    expect(await screen.findByText('Too many attempts. Try again in 12 seconds.')).toBeInTheDocument();
  });

  it('explains a network failure', async () => {
    server.use(mswHttp.post('*/api/v1/auth/login', () => HttpResponse.error()));
    const { user } = renderApp('/login', { session: anonymous });
    await fillAndSubmit(user);
    expect(await screen.findByText("Can't reach EasyCRM. Check your connection and try again.")).toBeInTheDocument();
  });

  it('validates on the client, marks fields invalid and focuses the first', async () => {
    const { user } = renderApp('/login', { session: anonymous });
    await user.click(await screen.findByRole('button', { name: 'Sign in' }));

    expect(await screen.findAllByText('This field is required.')).toHaveLength(3);
    expect(screen.getByLabelText('Workspace')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Workspace')).toHaveFocus();
  });
});

// R14: proves P15 (every call that writes the refresh cookie holds the `easycrm-refresh` Web Lock)
// for useLogin specifically, the same way start.test.ts already proves it for boot and logout.
describe('useLogin and P15', () => {
  it('waits for an in-flight refresh before signing in', async () => {
    const hold = holdCookieLock();
    await hold.acquired;
    let loginRequests = 0;
    server.use(
      http.post('/api/v1/auth/login', ({ response }) => {
        loginRequests += 1;
        return response(200).json(ownerSession);
      }),
    );
    const { user } = renderApp('/login', { session: anonymous, locks: hold.locks });

    await fillAndSubmit(user);
    // A synchronous check would pass either way -- fetch is always async even without a lock. Give
    // an UNLOCKED call plenty of real time to complete against the near-instant, in-process mocked
    // network; only a call genuinely queued behind our still-held lock can fail to have run by then.
    await new Promise((resolve) => setTimeout(resolve, 150));
    expect(loginRequests).toBe(0);

    hold.release();
    await waitFor(() => expect(loginRequests).toBe(1));
  });
});

// R15/P17: with the query client's `mutations: { networkMode: 'always' }` (queryClient.ts), a
// mutation never PAUSES while the browser reports offline -- it always attempts the network and
// either succeeds or fails for real. Flipping that default back to 'online' (the TanStack Query
// default) would leave `login.mutateAsync` pending forever here instead of rejecting, and this
// `findByText` would time out instead of finding the network error.
describe('P17: offline does not pause the login submit', () => {
  afterEach(() => {
    onlineManager.setOnline(true);
  });

  it('rejects and shows the network error UI while the browser reports offline', async () => {
    server.use(mswHttp.post('*/api/v1/auth/login', () => HttpResponse.error()));
    onlineManager.setOnline(false);

    const { user } = renderApp('/login', { session: anonymous });
    await fillAndSubmit(user);

    expect(await screen.findByText("Can't reach EasyCRM. Check your connection and try again.")).toBeInTheDocument();
  });
});

/** A minimal i18next backend whose `read` stays pending until `release()` is called -- the same
 * hold/release shape as `holdCookieLock()`, so the suspend below is deterministic instead of racing
 * a real dynamic import's actual resolve time. */
function createHeldBackend(resources: Record<string, unknown>) {
  let release: () => void = () => {};
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  const backend: BackendModule = {
    type: 'backend',
    init: () => {},
    read: (_language, namespace, callback) => {
      void gate.then(() => callback(null, resources[namespace] ?? {}));
    },
  };
  return { backend, release };
}

/** The classic "throw a promise" Suspense resource, used only to simulate a rejected import for
 * R71(b) without waiting out the real ~5s IMPORT_RETRY_SCHEDULE_MS. */
function createSuspenseResource<T>(promise: Promise<T>) {
  let state: { status: 'pending' } | { status: 'done'; value: T } | { status: 'error'; error: unknown } = {
    status: 'pending',
  };
  const tracked = promise.then(
    (value) => {
      state = { status: 'done', value };
    },
    (error: unknown) => {
      state = { status: 'error', error };
    },
  );
  return {
    read(): T {
      if (state.status === 'pending') throw tracked;
      if (state.status === 'error') throw state.error;
      return state.value;
    },
  };
}

function RejectingRoute({ resource }: { resource: { read: () => never } }) {
  resource.read();
  return null;
}

// R71: two integration proofs this task owns, because a real consumer route is needed to exercise
// them and /login is the first one to exist.
describe('R71: Suspense and error-boundary wiring for public routes', () => {
  it('(a) a real suspend on /login (i18n not yet loaded) shows RouteSkeleton, not a blank app', async () => {
    const instance = i18next.createInstance();
    const { backend, release } = createHeldBackend({ common: commonResource, auth: authResource });
    // Fire-and-forget, same as bootstrap.ts's `startApp()`: init() is never awaited before the
    // router renders, so a cold boot can genuinely reach /login before namespaces are ready.
    void instance.use(initReactI18next).use(backend).init({
      lng: 'en',
      fallbackLng: 'en',
      ns: ['common', 'auth'],
      defaultNS: 'common',
      interpolation: { escapeValue: false },
    });
    const queryClient = createQueryClient();
    const router = createMemoryRouter(appRoutes, { initialEntries: ['/login'] });

    render(
      <I18nextProvider i18n={instance}>
        <QueryClientProvider client={queryClient}>
          <RouterProvider router={router} />
        </QueryClientProvider>
      </I18nextProvider>,
    );

    // Regression guard: if router.tsx's local `<Suspense fallback={<RouteSkeleton />}>` around
    // LoginPage were removed, this suspend would instead bubble to RootLayout's own
    // `fallback={null}` and the screen would stay blank rather than show this placeholder.
    expect(await screen.findByRole('main', { hidden: true })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Sign in to EasyCRM' })).not.toBeInTheDocument();

    release();

    expect(await screen.findByRole('heading', { name: 'Sign in to EasyCRM' })).toBeInTheDocument();
  });

  it('(b) a rejected dynamic import() reaches RouteErrorBoundary rather than being swallowed by Suspense', async () => {
    // Suspense only catches a PENDING promise; once it settles rejected, React re-throws it as a
    // normal render error on the next attempt, which only an errorElement above the Suspense
    // boundary can catch (see RouteErrorBoundary.tsx and router.tsx's R47(b) comment). `schedule: []`
    // skips withImportRetry's real ~5s of backoff so this rejects immediately.
    const resource = createSuspenseResource(withImportRetry(() => Promise.reject(new Error('chunk load failed')), { schedule: [] }));
    const router = createMemoryRouter(
      [
        {
          path: '/broken',
          element: (
            <Suspense fallback={<RouteSkeleton />}>
              <RejectingRoute resource={resource} />
            </Suspense>
          ),
          errorElement: <RouteErrorBoundary />,
        },
      ],
      { initialEntries: ['/broken'] },
    );

    render(<RouterProvider router={router} />);

    expect(await screen.findByRole('heading', { name: 'Something went wrong' })).toBeInTheDocument();
  });
});
