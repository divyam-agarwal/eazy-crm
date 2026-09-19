import { http } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { server } from '@/test/msw';
import { holdCookieLock } from '@/test/locks';
import { inviteeSession, ownerSession } from '@/test/fixtures';
import { establishSession } from './session';
import { createInMemoryLocks, type LockProvider } from './lockProvider';
import { isLogoutPending, markLogoutPending } from './logoutPending';
import { startSession, stopSession } from './start';
import type { AuthChannel, AuthMessage } from './authChannel';
import type { SessionRuntime } from './runtime';
import { useSessionStore } from '@/session/sessionStore';

// Fix round 1, item 1 (Critical): start.ts had no test file at all, and three type-valid mutations
// — swapping 'login' -> 'logout' in the channel subscription, and stripping withCookieLock from
// either callLogout or refresh — passed the entire suite (114/114 green, typecheck clean). The
// first is a live correctness bug: with the wrong message type, a tab retrying a stale logout POST
// never learns someone else signed in, and its next retry logs the NEW user out — the exact hazard
// Challenge #89 names. These tests drive REAL AuthMessages through a fake channel and assert effects
// through the wiring (never by calling logout.onLoginBroadcast()/boot internals directly), because
// calling the exported functions directly is exactly what let the original settledElsewhere() test
// pass without ever exercising start.ts's own subscription wiring.

function fakeChannel(): AuthChannel & { deliver(message: AuthMessage): void; posted: AuthMessage[] } {
  const listeners = new Set<(message: AuthMessage) => void>();
  const posted: AuthMessage[] = [];
  return {
    post: (message) => posted.push(message),
    subscribe: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    deliver: (message) => listeners.forEach((listener) => listener(message)),
    posted,
  };
}

function fakeRuntime(locks: LockProvider = createInMemoryLocks()) {
  const channel = fakeChannel();
  const runtime: SessionRuntime = {
    clearQueryCache: vi.fn(),
    channel,
    locks,
    reload: vi.fn(),
    log: vi.fn(),
  };
  return { runtime, channel };
}

// `*` matches any origin (same convention as src/test/openapiHttp.ts) — this file runs in the
// default jsdom environment, whose location.origin is not 'http://localhost'.
const LOGOUT_URL = '*/api/v1/auth/logout';
const REFRESH_URL = '*/api/v1/auth/refresh';

const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

afterEach(() => {
  stopSession();
});

describe('startSession wiring: real AuthMessages through a fake channel', () => {
  it('a verified login for a DIFFERENT principal stops the pending retry loop', async () => {
    let logoutCalls = 0;
    server.use(
      http.post(LOGOUT_URL, () => {
        logoutCalls += 1;
        return new Response(null, { status: 409 });
      }),
      http.post(REFRESH_URL, () => jsonResponse(inviteeSession)),
    );
    const { runtime, channel } = fakeRuntime();
    const controls = startSession(runtime, { autoBoot: false });
    establishSession(ownerSession); // gives logout() a real owner to capture and compare against

    await controls.logout(); // first POST fails (409): retry armed (a timer AND an online listener)
    expect(logoutCalls).toBe(1);

    channel.deliver({ type: 'login', userId: inviteeSession.userId, tenantId: inviteeSession.tenantId });
    await vi.waitFor(() => expect(useSessionStore.getState().status).toBe('anonymous'));
    expect(isLogoutPending()).toBe(false);

    // The retry's `online` listener is the fastest observable proof the loop actually stopped: if
    // it were still registered, dispatching `online` fires an immediate retry with no artificial
    // delay to wait out. (Mutating the 'login' check in start.ts's subscription to 'logout' makes
    // this go red: the status never reaches 'anonymous' and vi.waitFor times out.)
    window.dispatchEvent(new Event('online'));
    await new Promise((resolve) => setTimeout(resolve, 150));
    expect(logoutCalls).toBe(1); // still 1 — no second POST, the loop was torn down, not just paused
  });

  it('a login broadcast claiming the SAME principal being signed out does not stop the retry', async () => {
    let logoutCalls = 0;
    server.use(
      http.post(LOGOUT_URL, () => {
        logoutCalls += 1;
        return new Response(null, { status: 409 });
      }),
      http.post(REFRESH_URL, () => jsonResponse(ownerSession)),
    );
    const { runtime, channel } = fakeRuntime();
    const controls = startSession(runtime, { autoBoot: false });
    establishSession(ownerSession); // gives logout() a real owner to capture and compare against

    await controls.logout();
    expect(logoutCalls).toBe(1);

    // Forged or stale: the broadcast claims the departing user just "logged in" again, but the
    // server-verified cookie (mocked above) still belongs to that same owner — nothing changed.
    channel.deliver({ type: 'login', userId: ownerSession.userId, tenantId: ownerSession.tenantId });
    await new Promise((resolve) => setTimeout(resolve, 150));

    expect(useSessionStore.getState().status).toBe('signing-out'); // still blocked, not /login
    expect(isLogoutPending()).toBe(true); // marker survives

    window.dispatchEvent(new Event('online'));
    await vi.waitFor(() => expect(logoutCalls).toBe(2)); // the retry DID fire — still armed, as intended
  });

  // The regression test for the vulnerability itself (Challenge #90): an attacker forges a `login`
  // claim for some OTHER identity, but the server's real state (mocked below) still shows the
  // original owner's cookie. A pre-fix bare-trust handler would have cleared the marker and shown
  // /login here; verification must catch the lie.
  it('a forged login broadcast cannot clear the marker when the server disagrees with its claim', async () => {
    let logoutCalls = 0;
    server.use(
      http.post(LOGOUT_URL, () => {
        logoutCalls += 1;
        return new Response(null, { status: 409 });
      }),
      http.post(REFRESH_URL, () => jsonResponse(ownerSession)), // truth: still the owner's cookie
    );
    const { runtime, channel } = fakeRuntime();
    const controls = startSession(runtime, { autoBoot: false });
    establishSession(ownerSession);

    await controls.logout();
    channel.deliver({ type: 'login', userId: 'attacker-claimed-id', tenantId: 'attacker-claimed-tenant' });
    await new Promise((resolve) => setTimeout(resolve, 150));

    expect(isLogoutPending()).toBe(true);
    expect(useSessionStore.getState().status).toBe('signing-out');
    expect(logoutCalls).toBe(1); // no premature settle, no spurious extra POST either
  });

  // Fix round 2 (rulings.md R59, reversed): the case that motivated persisting the principal.
  // Nothing in THIS tab ever established a session — it simulates a fresh boot resuming a marker a
  // discarded tab left behind (Challenge #89's own scenario), then a genuinely different person
  // signs in for real. Before this fix, an owner-unknown boot-resumed retry could not tell that
  // apart from a forged claim and stayed pending — so its next tick would have POSTed logout
  // carrying the new person's freshly-issued cookie, ending their brand-new session.
  it('a boot-resumed logout (no local session) settles on a real different-principal login, using the persisted owner', async () => {
    // What an earlier tab already did before this one exists: started sign-out for ownerSession and
    // persisted both the marker and its principal.
    markLogoutPending({ userId: ownerSession.userId, tenantId: ownerSession.tenantId });
    let logoutCalls = 0;
    server.use(
      http.post(LOGOUT_URL, () => {
        logoutCalls += 1;
        return new Response(null, { status: 409 });
      }),
      http.post(REFRESH_URL, () => jsonResponse(inviteeSession)),
    );
    const { runtime, channel } = fakeRuntime();
    const controls = startSession(runtime, { autoBoot: false }); // no establishSession(): me stays null

    await controls.logout(); // the boot-resumed shape: re-marks, no local session, first POST fails
    expect(logoutCalls).toBe(1);

    channel.deliver({ type: 'login', userId: inviteeSession.userId, tenantId: inviteeSession.tenantId });
    await vi.waitFor(() => expect(useSessionStore.getState().status).toBe('anonymous'));
    expect(isLogoutPending()).toBe(false);

    window.dispatchEvent(new Event('online'));
    await new Promise((resolve) => setTimeout(resolve, 150));
    expect(logoutCalls).toBe(1); // no second POST carrying the new person's cookie
  });

  // The forged case must still stay pending even when the only principal available is the durable
  // one a discarded tab left behind, not an in-memory capture from this tab's own logout() call.
  it('a boot-resumed logout still rejects a forged claim that disagrees with the persisted owner', async () => {
    markLogoutPending({ userId: ownerSession.userId, tenantId: ownerSession.tenantId });
    let logoutCalls = 0;
    server.use(
      http.post(LOGOUT_URL, () => {
        logoutCalls += 1;
        return new Response(null, { status: 409 });
      }),
      http.post(REFRESH_URL, () => jsonResponse(ownerSession)), // truth: still the same owner's cookie
    );
    const { runtime, channel } = fakeRuntime();
    const controls = startSession(runtime, { autoBoot: false });

    await controls.logout();
    channel.deliver({ type: 'login', userId: 'attacker-claimed-id', tenantId: 'attacker-claimed-tenant' });
    await new Promise((resolve) => setTimeout(resolve, 150));

    expect(isLogoutPending()).toBe(true);
    expect(useSessionStore.getState().status).toBe('signing-out');
    expect(logoutCalls).toBe(1);
  });

  it('a login broadcast verified as 401 (no cookie at all) settles the pending logout', async () => {
    server.use(
      http.post(LOGOUT_URL, () => new Response(null, { status: 409 })),
      http.post(REFRESH_URL, () => new Response(null, { status: 401 })),
    );
    const { runtime, channel } = fakeRuntime();
    const controls = startSession(runtime, { autoBoot: false });

    await controls.logout();
    // Even a garbage/unrelated claim is fine here — the server's answer is what decides, not the
    // message's content.
    channel.deliver({ type: 'login', userId: 'whoever', tenantId: 'whatever' });

    await vi.waitFor(() => expect(useSessionStore.getState().status).toBe('anonymous'));
    expect(isLogoutPending()).toBe(false);
  });
});

describe('P15: boot and logout take the refresh lock before touching the cookie', () => {
  // Fix round 1, items 1 and 3: R14 scoped P15's proof to Tasks 10-12 ("covered by construction"),
  // but boot's own refresh call and logout's own POST are each an independent line in start.ts, and
  // boot's refresh is one of the two actors in P15's own motivating race (two tabs, one cookie jar).
  // holdCookieLock() (src/test/locks.ts) is the shared helper Tasks 10-12 reuse for the same proof.

  it('logout takes the lock before POSTing — stripping withCookieLock from callLogout makes this red', async () => {
    const hold = holdCookieLock();
    await hold.acquired;
    let logoutCalls = 0;
    server.use(
      http.post(LOGOUT_URL, () => {
        logoutCalls += 1;
        return new Response(null, { status: 204 });
      }),
    );
    const { runtime } = fakeRuntime(hold.locks);
    const controls = startSession(runtime, { autoBoot: false });

    const logoutPromise = controls.logout();
    // A synchronous check here would pass either way — fetch is always async even without a lock.
    // The real proof is that the POST STILL hasn't happened after giving an *unlocked* call plenty
    // of real time to complete against the (near-instant, in-process) mocked network — only a call
    // that is genuinely queued behind our still-held lock can fail to have run by then.
    await new Promise((resolve) => setTimeout(resolve, 150));
    expect(logoutCalls).toBe(0);

    hold.release();
    await logoutPromise;
    expect(logoutCalls).toBe(1);
  });

  it("boot takes the lock before its own refresh call — stripping withCookieLock from refresh makes this red", async () => {
    const hold = holdCookieLock();
    await hold.acquired;
    let refreshCalls = 0;
    server.use(
      http.post(REFRESH_URL, () => {
        refreshCalls += 1;
        return jsonResponse(ownerSession);
      }),
    );
    const { runtime } = fakeRuntime(hold.locks);

    startSession(runtime); // autoBoot defaults true; boot.start() fires immediately, fire-and-forget
    await new Promise((resolve) => setTimeout(resolve, 150)); // see the sibling test's comment above
    expect(refreshCalls).toBe(0); // still blocked behind our held lock

    hold.release();
    await vi.waitFor(() => expect(refreshCalls).toBe(1));
    await vi.waitFor(() => expect(useSessionStore.getState().status).toBe('authenticated'));
  });
});
