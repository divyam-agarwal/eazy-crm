import { screen, waitFor } from '@testing-library/react';
import { delay, HttpResponse, http as mswHttp } from 'msw';
import { describe, expect, it } from 'vitest';
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

  it('shows one invalid state for an unknown or expired token', async () => {
    server.use(validPreview);
    renderApp('/invite/bad-token', { session: { status: 'anonymous' } });
    expect(await screen.findByText('This invitation link is invalid or has expired.')).toBeInTheDocument();
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
  it('R23: stays mounted (and shows its own signing-out state) instead of RootLayout blocking the page', async () => {
    let releaseLogout: (() => void) | undefined;
    server.use(
      validPreview,
      http.post('/api/v1/auth/logout', async ({ response }) => {
        await new Promise<void>((resolve) => {
          releaseLogout = resolve;
        });
        return response(204).empty();
      }),
    );
    const { user } = renderApp('/invite/good-token', { session: { status: 'authenticated', me: ownerMe, accessToken: 't' } });

    await user.click(await screen.findByRole('button', { name: 'Sign out and accept' }));

    // RootLayout's global blocking screen must never have taken over — that would be exactly the
    // unmount R23 forbids.
    expect(screen.queryByRole('heading', { name: 'Sign-out did not complete — retrying' })).not.toBeInTheDocument();
    // The page itself is still rendering its own content (not blank), announcing the wait itself.
    expect(screen.getByRole('heading', { name: 'Join a workspace' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Keep this page open. This device is not signed out until this finishes.');

    releaseLogout?.();

    // Once the server confirms, the very same mounted page moves on to the anonymous accept form —
    // never a redirect, never a remount.
    expect(await screen.findByLabelText('Choose a password')).toBeInTheDocument();
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
