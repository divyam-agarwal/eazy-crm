import { act, screen, waitFor } from '@testing-library/react';
import { HttpResponse, http as mswHttp } from 'msw';
import { describe, expect, it } from 'vitest';
import { api } from '@/api/client';
import { getAccessToken } from '@/features/auth/session/accessToken';
import { REFRESH_FORBIDDEN_MESSAGE } from '@/features/auth/session/refreshCall';
import { emitSessionExpired } from '@/features/auth/session/sessionEvents';
import { useSessionStore } from '@/session/sessionStore';
import { errorBody, ownerMe, ownerSession } from '@/test/fixtures';
import { server } from '@/test/msw';
import { http } from '@/test/openapiHttp';
import { renderApp } from '@/test/renderApp';

const signedInText = 'Signed in to ravi-traders as ravi@shop.in (Owner)';
const authenticated = { status: 'authenticated' as const, me: ownerMe, accessToken: ownerSession.accessToken };

// The contract requires the header; this handler refuses a refresh without it, exactly as the server does.
const refreshRequiringHeader = http.post('/api/v1/auth/refresh', ({ request, response }) =>
  request.headers.get('X-EasyCRM-Client') === 'web'
    ? response(200).json(ownerSession)
    : response(403).json(errorBody('FORBIDDEN', 'the X-EasyCRM-Client: web header is missing')),
);

describe('RequireSession', () => {
  // R16: Step 3's corrected version — `renderApp('/')` matches lazy routes, so a synchronous
  // `expect(container).toBeEmptyDOMElement()` would pass whatever RequireSession does (the router
  // has not initialized yet). Wait for the router first, and use the positive control below to
  // prove the assertion can actually fail.
  it('renders nothing while booting, so the index.html splash stays visible', async () => {
    const { container, router } = renderApp('/');
    await waitFor(() => expect(router.state.initialized).toBe(true));
    expect(container).toBeEmptyDOMElement();
  });

  it('renders the shell once authenticated (the control for the test above)', async () => {
    renderApp('/', { session: authenticated });
    expect(await screen.findByRole('banner')).toBeInTheDocument();
  });

  it('renders the shell and home placeholder when authenticated, with title and focus', async () => {
    renderApp('/', { session: authenticated });
    expect(await screen.findByText(signedInText)).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Welcome' })).toHaveFocus();
    expect(document.title).toBe('Home · EasyCRM');
  });

  it('redirects an anonymous visitor to /login with a safe next', async () => {
    const { router } = renderApp('/?tab=1', { session: { status: 'anonymous' } });
    await waitFor(() => expect(router.state.location.pathname).toBe('/login'));
    expect(router.state.location.search).toBe(`?next=${encodeURIComponent('/?tab=1')}`);
  });

  it('shows the retry screen when unreachable, and retrying boots', async () => {
    server.use(refreshRequiringHeader);
    const { user } = renderApp('/', { session: { status: 'unreachable' } });

    await user.click(await screen.findByRole('button', { name: 'Try again' }));

    expect(await screen.findByText(signedInText)).toBeInTheDocument();
  });
});

describe('boot', () => {
  it('sends X-EasyCRM-Client on the boot refresh and signs in', async () => {
    server.use(refreshRequiringHeader);
    renderApp('/', { boot: true });
    expect(await screen.findByText(signedInText)).toBeInTheDocument();
  });

  it('treats a boot 403 as a client bug: retry screen, not the login page', async () => {
    server.use(
      http.post('/api/v1/auth/refresh', ({ response }) =>
        response(403).json(errorBody('FORBIDDEN', 'the X-EasyCRM-Client: web header is missing')),
      ),
    );
    const { router, runtime } = renderApp('/', { boot: true });

    expect(await screen.findByRole('heading', { name: "Can't reach EasyCRM" })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/');
    expect(runtime.log).toHaveBeenCalledWith(REFRESH_FORBIDDEN_MESSAGE);
  });

  it('retries automatically within 5 s after a network failure', async () => {
    let calls = 0;
    server.use(
      mswHttp.post('*/api/v1/auth/refresh', () => {
        calls += 1;
        return calls === 1 ? HttpResponse.error() : HttpResponse.json(ownerSession);
      }),
    );
    const started = Date.now();
    renderApp('/', { boot: true });

    expect(await screen.findByRole('heading', { name: "Can't reach EasyCRM" })).toBeInTheDocument();
    expect(await screen.findByText(signedInText, undefined, { timeout: 5_000 })).toBeInTheDocument();
    expect(Date.now() - started).toBeLessThan(5_000);
  });
});

describe('RootLayout', () => {
  it('navigates to /login with a sanitized next when the session expires', async () => {
    // On a public route, so RequireSession's own redirect cannot be what moves the location.
    const { router } = renderApp('/nope?x=1', { session: { status: 'anonymous' } });
    await screen.findByRole('heading', { name: 'Page not found' });

    act(() => emitSessionExpired());

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'));
    expect(router.state.location.search).toBe(`?next=${encodeURIComponent('/nope?x=1')}`);
  });

  it('blocks every route while sign-out is pending', async () => {
    renderApp('/anything', { session: { status: 'signing-out' } });
    expect(await screen.findByRole('heading', { name: 'Sign-out did not complete — retrying' })).toBeInTheDocument();
  });

  // Task 12 review, fix round 1, item 3: RootLayout's `role="status"` announcer must be mounted
  // BEFORE the transition into 'signing-out', not created together with its text — otherwise many
  // AT/browser pairs never announce it (the same anti-pattern InvitePage.tsx's own hoisted region,
  // and SignOutPendingScreen.tsx, were fixed to avoid). `/nope` (NotFoundPage) has no role="status"
  // of its own, so this stays unambiguous: exactly one such region exists, throughout.
  it('mounts its signing-out announcer up front and only mutates its text, never recreating it', async () => {
    renderApp('/nope', { session: { status: 'anonymous' } });
    await screen.findByRole('heading', { name: 'Page not found' });
    const region = screen.getByRole('status');
    expect(region).toHaveTextContent('');

    act(() => useSessionStore.setState({ status: 'signing-out', me: null }));

    await waitFor(() =>
      expect(region).toHaveTextContent('Keep this page open. This device is not signed out until this finishes.'),
    );
    // Still the SAME node (not a second one that appeared alongside it) — this call would throw on
    // ambiguity if a fresh region had been created instead of the existing one mutating.
    expect(screen.getByRole('status')).toBe(region);
  });

  it('renders the not-found page for an unknown route', async () => {
    renderApp('/nope', { session: { status: 'anonymous' } });
    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument();
  });
});

// Testing-1: the whole point of the session design — a 401 mid-session refreshes once and retries
// — is otherwise tested only with fakes. `renderApp` runs the REAL startSession, so this exercises
// api → authFetch → bridge → coordinator → callRefresh as one wired system. Without it, deleting
// `setAuthBridge(...)` from start.ts leaves the inert bridge answering 'ended': every expired token
// silently logs the user out, and all 17 tasks stay green. F1 would be the first to notice.
describe('refresh on 401 (real wiring)', () => {
  it('refreshes once for three concurrent 401s and retries each with the new token', async () => {
    const STALE = 'stale-token';
    const FRESH = 'fresh-token';
    let refreshes = 0;
    server.use(
      http.get('/api/v1/auth/me', ({ request, response }) =>
        request.headers.get('Authorization') === `Bearer ${FRESH}`
          ? response(200).json(ownerMe)
          : response(401).json(errorBody('UNAUTHORIZED', 'expired')),
      ),
      http.post('/api/v1/auth/refresh', ({ response }) => {
        refreshes += 1;
        return response(200).json({ ...ownerSession, accessToken: FRESH });
      }),
    );
    renderApp('/', { session: { ...authenticated, accessToken: STALE } });

    const results = await Promise.all([
      api.GET('/api/v1/auth/me'),
      api.GET('/api/v1/auth/me'),
      api.GET('/api/v1/auth/me'),
    ]);

    expect(results.map((r) => r.response.status)).toEqual([200, 200, 200]);
    expect(refreshes).toBe(1); // the coordinator, under the Web Lock, is what makes this 1 and not 3
    expect(getAccessToken()).toBe(FRESH);
  });

  it('ends the session and lands on /login when the refresh itself returns 401', async () => {
    server.use(
      http.get('/api/v1/auth/me', ({ response }) => response(401).json(errorBody('UNAUTHORIZED', 'expired'))),
      http.post('/api/v1/auth/refresh', ({ response }) => response(401).json(errorBody('UNAUTHORIZED', 'gone'))),
    );
    const { router } = renderApp('/', { session: authenticated });

    await api.GET('/api/v1/auth/me');

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'));
    expect(useSessionStore.getState().me).toBeNull();
  });
});

describe('AppShell', () => {
  it('signs out: POST logout with the client header, then leaves the protected route', async () => {
    let sawHeader: string | null = null;
    server.use(
      http.post('/api/v1/auth/logout', ({ request, response }) => {
        sawHeader = request.headers.get('X-EasyCRM-Client');
        return response(204).empty();
      }),
    );
    const { user, router } = renderApp('/', { session: authenticated });

    await user.click(await screen.findByRole('button', { name: 'Sign out' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'));
    expect(sawHeader).toBe('web');
    expect(useSessionStore.getState().me).toBeNull();
  });

  // R17/R18: proves the ONE binding `renderApp` used to skip when it hand-rolled its own runtime
  // instead of going through `createSessionRuntime` — `clearQueryCache: () => queryClient.clear()`.
  // Needs its own logout handler: the default MSW server errors on any unhandled request.
  it('clears cached server data when the session ends', async () => {
    server.use(http.post('/api/v1/auth/logout', ({ response }) => response(204).empty()));
    const { queryClient, user } = renderApp('/', { session: authenticated });
    queryClient.setQueryData(['probe'], 'previous user data');

    await user.click(await screen.findByRole('button', { name: /sign out/i }));

    await waitFor(() => expect(queryClient.getQueryCache().getAll()).toHaveLength(0));
  });
});
