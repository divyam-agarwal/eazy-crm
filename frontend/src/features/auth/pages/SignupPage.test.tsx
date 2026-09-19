import { screen, waitFor, within } from '@testing-library/react';
import { HttpResponse, http as mswHttp } from 'msw';
import { describe, expect, it } from 'vitest';
import { useSessionStore } from '@/session/sessionStore';
import { errorBody, ownerSession } from '@/test/fixtures';
import { holdCookieLock } from '@/test/locks';
import { server } from '@/test/msw';
import { http } from '@/test/openapiHttp';
import { renderApp } from '@/test/renderApp';

const anonymous = { status: 'anonymous' as const };
const open = http.get('/api/v1/auth/signup/status', ({ response }) => response(200).json({ open: true }));
const slugTaken = http.post('/api/v1/auth/signup', ({ response }) =>
  response(409).json(errorBody('CONFLICT', 'slug already taken', { fields: { slug: 'slug already taken' }, fieldCodes: { slug: 'SLUG_TAKEN' } })),
);

async function fillValidForm(user: ReturnType<typeof renderApp>['user']) {
  await user.type(await screen.findByLabelText('Business name'), 'Ravi Traders');
  await user.selectOptions(screen.getByLabelText('State'), '27');
  await user.type(screen.getByLabelText('Email'), 'ravi@shop.in');
  await user.type(screen.getByLabelText('Password'), 'correct-horse-9');
}
const submit = (user: ReturnType<typeof renderApp>['user']) => user.click(screen.getByRole('button', { name: 'Create workspace' }));

describe('SignupPage', () => {
  it('shows the closed state with a sign-in link and no form', async () => {
    server.use(http.get('/api/v1/auth/signup/status', ({ response }) => response(200).json({ open: false })));
    renderApp('/signup', { session: anonymous });

    expect(await screen.findByRole('heading', { name: 'Signups are currently closed' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'sign in' })).toHaveAttribute('href', '/login');
    expect(screen.queryByRole('button', { name: 'Create workspace' })).not.toBeInTheDocument();
  });

  it('suggests a slug from the business name until the slug is edited', async () => {
    server.use(open);
    const { user } = renderApp('/signup', { session: anonymous });

    await user.type(await screen.findByLabelText('Business name'), 'Ravi Traders');
    expect(screen.getByLabelText('Workspace name')).toHaveValue('ravi-traders');

    await user.clear(screen.getByLabelText('Workspace name'));
    await user.type(screen.getByLabelText('Workspace name'), 'Ravi-HQ');
    await user.type(screen.getByLabelText('Business name'), ' and Sons');
    expect(screen.getByLabelText('Workspace name')).toHaveValue('ravi-hq');
  });

  it('creates the workspace, omits blank optional fields, and lands on home', async () => {
    let sent: unknown;
    server.use(
      open,
      http.post('/api/v1/auth/signup', async ({ request, response }) => {
        sent = await request.json();
        return response(201).json(ownerSession);
      }),
    );
    const { user } = renderApp('/signup', { session: anonymous });

    await fillValidForm(user);
    await submit(user);

    expect(await screen.findByText('Signed in to ravi-traders as ravi@shop.in (Owner)')).toBeInTheDocument();
    expect(sent).toEqual({ businessName: 'Ravi Traders', slug: 'ravi-traders', stateCode: '27', email: 'ravi@shop.in', password: 'correct-horse-9' });
    expect(useSessionStore.getState().status).toBe('authenticated');
  });

  it('puts SLUG_TAKEN on the slug field', async () => {
    server.use(open, slugTaken);
    const { user } = renderApp('/signup', { session: anonymous });

    await fillValidForm(user);
    await submit(user);

    expect(await screen.findByText('This workspace name is taken.')).toBeInTheDocument();
    expect(screen.getByLabelText('Workspace name')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Workspace name')).toHaveFocus();
  });

  it('focuses the GSTIN on a 422 GSTIN_CHECKSUM, uppercasing what was typed', async () => {
    let sentGstin: unknown;
    server.use(
      open,
      http.post('/api/v1/auth/signup', async ({ request, response }) => {
        sentGstin = (await request.json()).gstin;
        return response(422).json(
          errorBody('VALIDATION_FAILED', 'invalid GSTIN', { fields: { gstin: 'GSTIN check digit mismatch' }, fieldCodes: { gstin: 'GSTIN_CHECKSUM' } }),
        );
      }),
    );
    const { user } = renderApp('/signup', { session: anonymous });

    await fillValidForm(user);
    await user.type(screen.getByLabelText('GSTIN (optional)'), '27aaaaa0000a1z0');
    await submit(user);

    expect(await screen.findByText('This GSTIN is not valid. Check it for typos.')).toBeInTheDocument();
    expect(screen.getByLabelText('GSTIN (optional)')).toHaveFocus();
    expect(sentGstin).toBe('27AAAAA0000A1Z0');
  });

  it('after a lost response, a SLUG_TAKEN for the same slug offers sign-in instead of a field error', async () => {
    let calls = 0;
    server.use(
      open,
      mswHttp.post('*/api/v1/auth/signup', () => {
        calls += 1;
        return calls === 1
          ? HttpResponse.error()
          : HttpResponse.json(errorBody('CONFLICT', 'slug already taken', { fields: { slug: 'slug already taken' }, fieldCodes: { slug: 'SLUG_TAKEN' } }), {
              status: 409,
            });
      }),
    );
    const { user } = renderApp('/signup', { session: anonymous });

    await fillValidForm(user);
    await submit(user);
    expect(await screen.findByText("Can't reach EasyCRM. Check your connection and try again.")).toBeInTheDocument();

    await submit(user);

    // Scoped to the hint: the page footer ("Already have a workspace? Sign in") has a link of the same name.
    const hint = within(await screen.findByRole('status')).getByRole('link', { name: 'Sign in' });
    expect(hint).toHaveAttribute('href', '/login');
    expect(screen.getByLabelText('Workspace name')).not.toHaveAttribute('aria-invalid');
  });

  it('keeps a genuine SLUG_TAKEN (no earlier lost response) as a field error', async () => {
    server.use(open, slugTaken);
    const { user } = renderApp('/signup', { session: anonymous });

    await fillValidForm(user);
    await submit(user);
    await screen.findByText('This workspace name is taken.');
    await submit(user);

    await waitFor(() => expect(screen.getByLabelText('Workspace name')).toHaveAttribute('aria-invalid', 'true'));
    expect(screen.queryByText(/may already have been created/)).not.toBeInTheDocument();
  });

  it('validates on the client, marks the first field invalid and focuses it', async () => {
    server.use(open);
    const { user } = renderApp('/signup', { session: anonymous });

    await screen.findByLabelText('Business name');
    await submit(user);

    expect(await screen.findAllByText('This field is required.')).not.toHaveLength(0);
    expect(screen.getByLabelText('Business name')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Business name')).toHaveFocus();
  });

  // R81: a real server 400 with `fields`/`fieldCodes`, through the actual TextField DOM — at 6-7
  // fields a key mismatch (e.g. reusing the wrong SIGNUP_FIELDS entry) is likelier than on /login,
  // so this checks aria-invalid, aria-describedby (via toHaveAccessibleDescription) and focus together.
  it('a 400 field error on phone lands on the right input: invalid, described and focused', async () => {
    server.use(
      open,
      http.post('/api/v1/auth/signup', ({ response }) =>
        response(400).json(errorBody('VALIDATION_FAILED', 'invalid request', { fieldCodes: { phone: 'PATTERN' } })),
      ),
    );
    const { user } = renderApp('/signup', { session: anonymous });

    await fillValidForm(user);
    await submit(user);

    const phone = await screen.findByLabelText('Phone (optional)');
    expect(phone).toHaveAttribute('aria-invalid', 'true');
    expect(phone).toHaveAccessibleDescription('This value is not in the expected format.');
    expect(phone).toHaveFocus();
  });
});

// R14: proves P15 (every call that writes the refresh cookie holds the `easycrm-refresh` Web Lock)
// for useSignup specifically, the same way start.test.ts proves it for boot/logout and LoginPage.test.tsx
// proves it for useLogin.
describe('useSignup and P15', () => {
  it('waits for an in-flight refresh before signing up', async () => {
    const hold = holdCookieLock();
    await hold.acquired;
    let signupRequests = 0;
    server.use(
      open,
      http.post('/api/v1/auth/signup', ({ response }) => {
        signupRequests += 1;
        return response(201).json(ownerSession);
      }),
    );
    const { user } = renderApp('/signup', { session: anonymous, locks: hold.locks });

    await fillValidForm(user);
    await submit(user);
    // Same reasoning as LoginPage.test.tsx: give an unlocked call plenty of real time to complete
    // against the near-instant, in-process mocked network before concluding it was actually queued.
    await new Promise((resolve) => setTimeout(resolve, 150));
    expect(signupRequests).toBe(0);

    hold.release();
    await waitFor(() => expect(signupRequests).toBe(1));
  });
});

// Mirrors LoginPage.test.tsx's "Task 10 fix round 1" suite: the /login patterns this page must also
// carry, each proven by a test rather than by reasoning about the fix.
describe('pending state and FormAlert attempt wiring', () => {
  it('stays focusable and announces "Creating…" while pending, and guards against a resubmit', async () => {
    let requests = 0;
    let releaseSignup: (() => void) | undefined;
    server.use(
      open,
      http.post('/api/v1/auth/signup', async ({ response }) => {
        requests += 1;
        await new Promise<void>((resolve) => {
          releaseSignup = resolve;
        });
        return response(201).json(ownerSession);
      }),
    );
    const { user } = renderApp('/signup', { session: anonymous });

    await fillValidForm(user);
    await submit(user);

    const button = await screen.findByRole('button', { name: 'Creating…' });
    // Discriminating: reverting to `disabled={isSubmitting}` drops the `aria-disabled` attribute
    // entirely (a native `disabled` control never carries it), which this assertion alone would
    // still pass without noticing if it also lost focus — jsdom, unlike a real browser, does not
    // blur a focused element when `disabled` is applied, so `toHaveFocus()` alone is not
    // discriminating (see LoginPage.test.tsx's identical caveat). Kept together, both checked.
    expect(button).toHaveAttribute('aria-disabled', 'true');
    expect(button).toHaveFocus();

    // A second activation does not fire a second POST: useSignup (P15) holds the refresh lock for
    // the whole call, so this queues behind the still-held lock rather than reaching the network
    // again.
    await user.click(button);
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(requests).toBe(1);

    releaseSignup?.();
    await waitFor(() => expect(useSessionStore.getState().status).toBe('authenticated'));
  });

  // R65/R78: guards `attempt={submitCount}` against being neutered into a constant. Verified by
  // temporarily swapping in `attempt={1}` and rerunning — that swap typechecks and every other test
  // stays green, but this one fails because the alert never re-focuses on the second, identical
  // failure (see the task report for the failure output).
  it('re-focuses the alert on a second, identical failure', async () => {
    // A 500 always produces a form-level message (not a field error), so FormAlert is what's on
    // screen to re-focus. 500 is not one of the contract's documented statuses for this operation
    // (openapi-msw's typed `response()` would reject it), so this uses the raw msw handler, same as
    // LoginPage.test.tsx's 429 case.
    server.use(
      open,
      mswHttp.post('*/api/v1/auth/signup', () => HttpResponse.json(errorBody('INTERNAL', 'boom'), { status: 500 })),
    );
    const { user } = renderApp('/signup', { session: anonymous });

    await fillValidForm(user);
    await submit(user);
    const alert = await screen.findByRole('alert');
    await waitFor(() => expect(alert).toHaveFocus());
    alert.blur();
    expect(alert).not.toHaveFocus();

    await submit(user);
    await waitFor(() => expect(alert).toHaveFocus());
  });
});
