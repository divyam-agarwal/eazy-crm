import { screen, waitFor, within } from '@testing-library/react';
import { delay, HttpResponse, http as mswHttp } from 'msw';
import { describe, expect, it } from 'vitest';
import { useSessionStore } from '@/session/sessionStore';
import { errorBody, inviteeSession, ownerMe } from '@/test/fixtures';
import { holdCookieLock } from '@/test/locks';
import { server } from '@/test/msw';
import { http } from '@/test/openapiHttp';
import { renderApp } from '@/test/renderApp';

const preview = { businessName: 'Ravi Traders', email: 'asha@shop.in', role: 'SALES_EXEC' };
const validPreview = http.get('/api/v1/auth/invitations/{token}', ({ params, response }) =>
  params.token === 'good-token' ? response(200).json(preview) : response(404).json(errorBody('NOT_FOUND', 'not found')),
);
const paragraph = (text: string) => (_: string, el: Element | null) => el?.tagName === 'P' && el.textContent === text;

describe('InvitePage', () => {
  it('shows a loading state sized like the card, then the invitation', async () => {
    server.use(
      http.get('/api/v1/auth/invitations/{token}', async ({ response }) => {
        await delay(50);
        return response(200).json(preview);
      }),
    );
    renderApp('/invite/good-token', { session: { status: 'anonymous' } });

    expect(await screen.findByText('Loading invitation')).toBeInTheDocument();
    expect(await screen.findByText(paragraph('Join Ravi Traders as Sales executive'))).toBeInTheDocument();
  });

  it('shows one invalid state for an unknown or expired token, with a way forward', async () => {
    server.use(validPreview);
    renderApp('/invite/bad-token', { session: { status: 'anonymous' } });
    expect(await screen.findByText('This invitation link is invalid or has expired.')).toBeInTheDocument();
    // Fix round 1 (item 4, a11y): the most likely reach of this branch — a forwarded WhatsApp link
    // hitting someone with no account — used to be a dead end with no link and no nav chrome.
    expect(screen.getByRole('link', { name: 'sign in' })).toHaveAttribute('href', '/login');
  });

  it('accepts as an anonymous visitor and replaces the token URL with home', async () => {
    let sent: unknown;
    server.use(
      validPreview,
      http.post('/api/v1/auth/invitations/{token}/accept', async ({ request, response }) => {
        sent = await request.json();
        return response(201).json(inviteeSession);
      }),
    );
    const { user, router } = renderApp('/invite/good-token', { session: { status: 'anonymous' } });

    const email = await screen.findByLabelText('Email');
    expect(email).toHaveValue('asha@shop.in');
    expect(email).toHaveAttribute('readonly');
    expect(email).toHaveAttribute('autocomplete', 'username');

    await user.type(screen.getByLabelText('Choose a password'), 'correct-horse-9');
    await user.click(screen.getByRole('button', { name: 'Join workspace' }));

    expect(await screen.findByText('Signed in to ravi-traders as asha@shop.in (Sales executive)')).toBeInTheDocument();
    expect(sent).toEqual({ password: 'correct-horse-9' });
    expect(router.state.location.pathname).toBe('/');
    expect(router.state.historyAction).toBe('REPLACE');
  });

  it('offers sign-out-and-accept to a signed-in user, then shows the accept form', async () => {
    server.use(validPreview, http.post('/api/v1/auth/logout', ({ response }) => response(204).empty()));
    const { user } = renderApp('/invite/good-token', { session: { status: 'authenticated', me: ownerMe, accessToken: 't' } });

    expect(
      await screen.findByText(paragraph("This invitation is for asha@shop.in to join Ravi Traders. You're signed in as ravi@shop.in.")),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Go to my workspace' })).toHaveAttribute('href', '/');

    await user.click(screen.getByRole('button', { name: 'Sign out and accept' }));

    expect(await screen.findByLabelText('Choose a password')).toBeInTheDocument();
  });

  it('after a lost accept response, a 404 suggests signing in instead of the invalid state', async () => {
    let calls = 0;
    server.use(
      validPreview,
      mswHttp.post('*/api/v1/auth/invitations/good-token/accept', () => {
        calls += 1;
        return calls === 1 ? HttpResponse.error() : HttpResponse.json(errorBody('NOT_FOUND', 'not found'), { status: 404 });
      }),
    );
    const { user } = renderApp('/invite/good-token', { session: { status: 'anonymous' } });

    await user.type(await screen.findByLabelText('Choose a password'), 'correct-horse-9');
    await user.click(screen.getByRole('button', { name: 'Join workspace' }));
    await screen.findByText("Can't reach EasyCRM. Check your connection and try again.");
    await user.click(screen.getByRole('button', { name: 'Join workspace' }));

    expect(await screen.findByText(paragraph('If you already set your password, sign in.'))).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText('This invitation link is invalid or has expired.')).not.toBeInTheDocument());
  });

  // R81: a real server 400 with `fields`/`fieldCodes`, through the actual TextField DOM — checks
  // aria-invalid, aria-describedby (via toHaveAccessibleDescription) and focus together, the same
  // way LoginPage.test.tsx and SignupPage.test.tsx prove it for their own routes.
  it('a 400 field error on phone lands on the right input: invalid, described and focused', async () => {
    server.use(
      validPreview,
      http.post('/api/v1/auth/invitations/{token}/accept', ({ response }) =>
        response(400).json(errorBody('VALIDATION_FAILED', 'invalid request', { fieldCodes: { phone: 'PATTERN' } })),
      ),
    );
    const { user } = renderApp('/invite/good-token', { session: { status: 'anonymous' } });

    await user.type(await screen.findByLabelText('Choose a password'), 'correct-horse-9');
    await user.click(screen.getByRole('button', { name: 'Join workspace' }));

    const phone = await screen.findByLabelText('Phone (optional)');
    expect(phone).toHaveAttribute('aria-invalid', 'true');
    expect(phone).toHaveAccessibleDescription('This value is not in the expected format.');
    expect(phone).toHaveFocus();
  });

  // R65/R78: guards `attempt={submitCount}` against being neutered into a constant, the same way
  // LoginPage.test.tsx and SignupPage.test.tsx guard their own submit alerts. Verified by swapping
  // in `attempt={1}` and rerunning — that swap typechecks and every other test here stays green, but
  // this one fails because the alert never re-focuses on the second, identical failure (see the task
  // report for the failure output).
  it('re-focuses the alert on a second, identical failure (guards the attempt wiring)', async () => {
    server.use(
      validPreview,
      mswHttp.post('*/api/v1/auth/invitations/good-token/accept', () => HttpResponse.json(errorBody('INTERNAL', 'boom'), { status: 500 })),
    );
    const { user } = renderApp('/invite/good-token', { session: { status: 'anonymous' } });

    await user.type(await screen.findByLabelText('Choose a password'), 'correct-horse-9');
    await user.click(screen.getByRole('button', { name: 'Join workspace' }));
    const alert = await screen.findByRole('alert');
    await waitFor(() => expect(alert).toHaveFocus());
    alert.blur();
    expect(alert).not.toHaveFocus();

    await user.click(screen.getByRole('button', { name: 'Join workspace' }));
    await waitFor(() => expect(alert).toHaveFocus());
  });

  // R23: proves the page is NOT unmounted by RootLayout's signing-out gate while "Sign out and
  // accept" is in flight — the whole point of exempting /invite/:token in RootLayout.tsx. Holds the
  // logout POST open so the mid-flight window is observable rather than inferred from timing.
  //
  // Fix round 1 (item 1, security): ALSO holds the preview GET open past its first (page-load) call.
  // sessionControls().logout() clears the whole query cache, so clicking "Sign out and accept"
  // triggers a SECOND preview fetch (react-query rebuilding a pending Query the instant its cache
  // entry disappears) at the same moment `status` becomes 'signing-out' — the original version of
  // this test let MSW resolve that refetch instantly, so the bug (loading skeleton, or "invalid or
  // expired", winning over "Keep this page open" because isPending/isError were checked first) never
  // showed up in CI. Holding it open makes `preview.isPending` genuinely true throughout the
  // mid-flight assertions, the same as it is in production on a slow connection.
  it('R23: stays mounted and shows its own signing-out state, even while the cache-cleared preview refetch is still pending', async () => {
    let releaseLogout: (() => void) | undefined;
    let releasePreviewRefetch: (() => void) | undefined;
    let previewCalls = 0;
    server.use(
      http.get('/api/v1/auth/invitations/{token}', async ({ params, response }) => {
        if (params.token !== 'good-token') return response(404).json(errorBody('NOT_FOUND', 'not found'));
        previewCalls += 1;
        if (previewCalls > 1) {
          // Only the cache-clear-triggered REFETCH is held open — the initial page load must still
          // resolve, or the "Sign out and accept" button this test clicks would never appear.
          await new Promise<void>((resolve) => {
            releasePreviewRefetch = resolve;
          });
        }
        return response(200).json(preview);
      }),
      http.post('/api/v1/auth/logout', async ({ response }) => {
        await new Promise<void>((resolve) => {
          releaseLogout = resolve;
        });
        return response(204).empty();
      }),
    );
    const { user } = renderApp('/invite/good-token', { session: { status: 'authenticated', me: ownerMe, accessToken: 't' } });

    await user.click(await screen.findByRole('button', { name: 'Sign out and accept' }));
    await waitFor(() => expect(previewCalls).toBeGreaterThan(1)); // the cache-clear-triggered refetch has genuinely started, and is held pending

    // RootLayout's global blocking screen must never have taken over — that would be exactly the
    // unmount R23 forbids.
    expect(screen.queryByRole('heading', { name: 'Sign-out did not complete — retrying' })).not.toBeInTheDocument();
    // The page itself is still rendering its own content (not blank), announcing the wait itself —
    // and NOT the loading skeleton or the invalid-token message the now-pending/errorable preview
    // refetch would otherwise win, were the branch order wrong (item 1).
    expect(screen.getByRole('heading', { name: 'Join a workspace' })).toBeInTheDocument();
    expect(screen.queryByText('Loading invitation')).not.toBeInTheDocument();
    expect(screen.queryByText('This invitation link is invalid or has expired.')).not.toBeInTheDocument();
    // Scoped to <main>: RootLayout also renders its own (always-empty, for this exempt route)
    // role="status" region as a sibling above the Outlet — see RootLayout.tsx's fix round 1, item 3.
    expect(within(screen.getByRole('main')).getByRole('status')).toHaveTextContent(
      'Keep this page open. This device is not signed out until this finishes.',
    );

    releasePreviewRefetch?.();
    releaseLogout?.();

    // Once the server confirms, the very same mounted page moves on to the anonymous accept form —
    // never a redirect, never a remount.
    expect(await screen.findByLabelText('Choose a password')).toBeInTheDocument();
  });

  // Fix round 1 (item 6): both sibling pages (/login, /signup) have this exact regression test
  // because this codebase already shipped and fixed a `disabled`-vs-`aria-disabled` bug once
  // (Task 10 fix round 1) — the code here already uses `aria-disabled`, but without this test a
  // regression to native `disabled` would be caught on two pages and pass silently on this third one.
  it('stays focusable and announces "Joining…" while pending, and guards against a resubmit', async () => {
    let requests = 0;
    let releaseAccept: (() => void) | undefined;
    server.use(
      validPreview,
      http.post('/api/v1/auth/invitations/{token}/accept', async ({ response }) => {
        requests += 1;
        await new Promise<void>((resolve) => {
          releaseAccept = resolve;
        });
        return response(201).json(inviteeSession);
      }),
    );
    const { user } = renderApp('/invite/good-token', { session: { status: 'anonymous' } });

    await user.type(await screen.findByLabelText('Choose a password'), 'correct-horse-9');
    await user.click(screen.getByRole('button', { name: 'Join workspace' }));

    const button = await screen.findByRole('button', { name: 'Joining…' });
    // Discriminating: reverting to `disabled={isSubmitting}` drops the `aria-disabled` attribute
    // entirely and, in a real browser (jsdom does not model this), blurs the button — see
    // LoginPage.test.tsx's identical caveat.
    expect(button).toHaveAttribute('aria-disabled', 'true');
    expect(button).toHaveFocus();
    // Scoped to <main> — see the R23 test's identical comment on why an unscoped getByRole('status')
    // is ambiguous here (RootLayout's own hoisted region is also present, always empty on this route).
    expect(within(screen.getByRole('main')).getByRole('status')).toHaveTextContent('Joining…');

    // A second activation does not fire a second POST. The refresh lock useAcceptInvitation (P15)
    // holds does NOT prevent this on its own -- it serializes, it does not deduplicate (Challenge
    // #98, corrected): a queued second `mutateAsync` would still reach the network once the first
    // call's lock hold ends. What actually stops it is `if (accept.isPending) return;` in
    // `onSubmit`, so the assertion is taken both during the hold and after release.
    await user.click(button);
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(requests).toBe(1);

    releaseAccept?.();
    await waitFor(() => expect(useSessionStore.getState().status).toBe('authenticated'));
    // If the second click's `mutateAsync` had been queued behind the lock (the pre-fix behaviour),
    // it would fire its own POST once the first call's hold ends -- give it time to, then check it
    // did not.
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(requests).toBe(1);
  });

  // Fix round 1 (item 2, a11y/R80): a cold preview failure (the FIRST time the error branch renders,
  // no user action yet) must not steal focus from PageHeading's own page-identity announcement — only
  // a genuine retry attempt should re-focus the alert. Fails under the old `preview.errorUpdateCount`
  // wiring: that counter is already >= 1 the first time isError becomes observable (react-query
  // doesn't expose the pending retries in between — only the final settled state), so FormAlert's
  // `attempt > 0` gate is satisfied immediately and the alert steals focus from the heading on cold
  // arrival (verified: swapping `retryAttempt` back for `preview.errorUpdateCount` makes the first
  // `expect(heading).toHaveFocus()` below fail). 429, not 500: queryClient.ts's `shouldRetryQuery`
  // retries a real 5xx automatically (up to twice, with backoff) before ever exposing `isError` to
  // the component at all — a real but slow, timer-dependent path this test doesn't need to exercise
  // to prove the FOCUS-gating bug, which is about the FIRST observable error render either way.
  it('R80: a cold preview failure does not steal focus from the page heading; a retry failure does re-focus the alert', async () => {
    server.use(
      mswHttp.get('*/api/v1/auth/invitations/good-token', () => HttpResponse.json(errorBody('RATE_LIMITED', 'too many requests'), { status: 429 })),
    );
    const { user } = renderApp('/invite/good-token', { session: { status: 'anonymous' } });

    const heading = await screen.findByRole('heading', { name: 'Join a workspace' });
    await waitFor(() => expect(heading).toHaveFocus());
    expect(screen.getByRole('alert')).not.toHaveFocus();

    await user.click(screen.getByRole('button', { name: 'Try again' }));

    // Re-queried, not the pre-click reference: react-query resets an errored-with-no-data query to
    // `status: 'pending'` for the duration of a refetch (query-core's `fetchState`), so this page
    // genuinely passes back through its loading branch and remounts a fresh FormAlert once the retry
    // also fails — that remount is real react-query behavior, not the bug under test, and the fresh
    // instance's own mount-time effect (attempt already 1) is what does the re-focusing here.
    await waitFor(() => expect(screen.getByRole('alert')).toHaveFocus());
  });
});

// R14: proves P15 (every call that writes the refresh cookie holds the `easycrm-refresh` Web Lock)
// for useAcceptInvitation specifically, the same way start.test.ts proves it for boot/logout and
// LoginPage.test.tsx/SignupPage.test.tsx prove it for useLogin/useSignup.
describe('useAcceptInvitation and P15', () => {
  it('waits for an in-flight refresh before accepting', async () => {
    const hold = holdCookieLock();
    await hold.acquired;
    let acceptRequests = 0;
    server.use(
      validPreview,
      http.post('/api/v1/auth/invitations/{token}/accept', ({ response }) => {
        acceptRequests += 1;
        return response(201).json(inviteeSession);
      }),
    );
    const { user } = renderApp('/invite/good-token', { session: { status: 'anonymous' }, locks: hold.locks });

    await user.type(await screen.findByLabelText('Choose a password'), 'correct-horse-9');
    await user.click(screen.getByRole('button', { name: 'Join workspace' }));
    // Same reasoning as LoginPage.test.tsx/SignupPage.test.tsx: give an unlocked call plenty of real
    // time to complete against the near-instant, in-process mocked network before concluding it was
    // actually queued.
    await new Promise((resolve) => setTimeout(resolve, 150));
    expect(acceptRequests).toBe(0);

    hold.release();
    await waitFor(() => expect(acceptRequests).toBe(1));
  });
});
