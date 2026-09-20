import { QueryClientProvider, onlineManager } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
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

  // Challenge #99 (found and fixed during Task 11, which copies this exact Trans pattern): the
  // placeholder tag in `login.noAccount` used to be `<link>`, which react-i18next's internal HTML
  // parser treats as the void HTML <link> element -- it silently dropped the tag's children, leaving
  // this control real but completely empty (no accessible name, nothing clickable) even though the
  // page visually still showed the words "Create a workspace" as plain sibling text. `<Link>`
  // (capitalized) sidesteps that void-element lookup, which is case-sensitive on purpose.
  it('renders the "create a workspace" link with real content, not an empty anchor', async () => {
    renderApp('/login', { session: anonymous });
    const link = await screen.findByRole('link', { name: 'Create a workspace' });
    expect(link).toHaveAttribute('href', '/signup');
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

// Task 10 fix round 1: three review findings, each proven by a test rather than by reasoning about
// the fix.
describe('Task 10 fix round 1', () => {
  // Item 1 (Important, a11y): the pending window used to be silent (nothing announced) and, because
  // the submit button was `disabled` (not `aria-disabled`), the browser blurred it the instant
  // submission started -- stranding a keyboard/screen-reader user at <body> for the whole 2-5s round
  // trip. Holds the login POST open (a controllable promise, not a real delay) so the pending state
  // is provably observable rather than inferred from having usually caught it in time.
  it('stays focusable and announces "Signing in…" while pending, and guards against a resubmit', async () => {
    let requests = 0;
    let releaseLogin: (() => void) | undefined;
    server.use(
      http.post('/api/v1/auth/login', async ({ response }) => {
        requests += 1;
        await new Promise<void>((resolve) => {
          releaseLogin = resolve;
        });
        return response(200).json(ownerSession);
      }),
    );
    const { user } = renderApp('/login', { session: anonymous });

    await fillAndSubmit(user);

    const button = await screen.findByRole('button', { name: 'Signing in…' });
    // The discriminating assertion: `aria-disabled`, not the native `disabled` attribute, is what's
    // applied while pending -- confirmed by reverting to `disabled={isSubmitting}` and rerunning,
    // which fails here (attribute absent). `toHaveFocus()` below is NOT discriminating on its own:
    // jsdom, unlike a real browser, does not blur a focused element when `disabled` is applied to
    // it, so that assertion alone stayed green even against the reverted `disabled` code -- verified
    // by temporarily removing the `aria-disabled` line and rerunning against the `disabled` revert.
    // Kept anyway as a true statement of the intended behavior, not as the regression's proof.
    expect(button).toHaveAttribute('aria-disabled', 'true');
    expect(button).toHaveFocus();
    // Scoped to <main>: Task 12 fix round 1 gave RootLayout its own always-mounted role="status"
    // announcer (RootLayout.tsx), which makes an unscoped getByRole('status') ambiguous on any page.
    expect(within(screen.getByRole('main')).getByRole('status')).toHaveTextContent('Signing in…');

    // The button is `aria-disabled`, not `disabled` -- the browser still lets it be activated. The
    // `easycrm-refresh` Web Lock `useLogin` (P15) holds does NOT stop a second tap from firing a
    // second POST -- a lock SERIALIZES, it does not DEDUPLICATE: a second `mutateAsync` would queue
    // behind the still-held lock and then run once the first call's hold ends. What actually stops
    // it is `if (login.isPending) return;` at the top of `onSubmit` (Challenge #98, corrected),
    // which is why the assertion below is taken both DURING the hold and AFTER release -- the first
    // alone cannot distinguish "queued" from "never queued".
    await user.click(button);
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(requests).toBe(1);

    releaseLogin?.();
    await waitFor(() => expect(useSessionStore.getState().me?.email).toBe('ravi@shop.in'));
    // If the second click's `mutateAsync` had been queued behind the lock (the pre-fix behaviour),
    // it would fire its own POST once the first call's hold ends -- give it time to, then check it
    // did not: this is the assertion the reviewer's `requestsAfterRelease = 2` demonstration exposed
    // as missing. Verified red by temporarily removing the `isPending` guard from LoginPage.tsx.
    const requestsAfterRelease = requests;
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(requests).toBe(requestsAfterRelease);
    expect(requests).toBe(1);
  });

  // Item 3 (Minor, but the reason it matters is bigger than the finding): a prior review swapped
  // `attempt={submitCount}` for `attempt={1}` in LoginPage.tsx and all 13 tests stayed green --
  // TypeScript catches removing the prop, nothing caught neutering it into a constant. This test
  // exists to be that catch. Verified by making that exact swap and watching it fail (see the task
  // report for the failure output) before trusting it green again.
  it('re-focuses the alert on a second, identical failure (guards the `attempt` wiring, not just the message)', async () => {
    server.use(http.post('/api/v1/auth/login', ({ response }) => response(401).json(errorBody('UNAUTHORIZED', 'invalid credentials'))));
    const { user } = renderApp('/login', { session: anonymous });

    await fillAndSubmit(user);
    const alert = await screen.findByRole('alert');
    await waitFor(() => expect(alert).toHaveFocus());
    alert.blur();
    expect(alert).not.toHaveFocus();

    // Same message text, a second real attempt -- with a constant `attempt`, FormAlert's effect
    // deps ([message, attempt]) would be unchanged and this second focus would never happen.
    await fillAndSubmit(user);
    await waitFor(() => expect(alert).toHaveFocus());
  });

  // Item 4 (Minor): applyApiError is unit-tested generically elsewhere, but nothing proved a real
  // 400 with `fieldCodes` reaches actual TextField DOM on THIS route. 400 with fields/fieldCodes is
  // documented on POST /api/v1/auth/login (src/api/schema.d.ts) and reachable in practice (a
  // bean-validation failure on the request body), so it's worth a route-level proof, not just a
  // unit test of the mapping function.
  it('a 400 field error lands on the right input: invalid, described and focused', async () => {
    server.use(
      http.post('/api/v1/auth/login', ({ response }) =>
        response(400).json(errorBody('VALIDATION_FAILED', 'invalid request', { fieldCodes: { email: 'EMAIL' } })),
      ),
    );
    const { user } = renderApp('/login', { session: anonymous });

    await fillAndSubmit(user);

    const email = await screen.findByLabelText('Email');
    expect(email).toHaveAttribute('aria-invalid', 'true');
    expect(email).toHaveAccessibleDescription('Enter a valid email address.');
    expect(email).toHaveFocus();
  });
});
