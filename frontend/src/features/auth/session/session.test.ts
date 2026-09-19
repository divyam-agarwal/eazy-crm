import { describe, expect, it, vi } from 'vitest';
import { inviteeSession, ownerSession } from '@/test/fixtures';
import { getAccessToken } from './accessToken';
import type { AuthChannel, AuthMessage } from './authChannel';
import { createInMemoryLocks } from './lockProvider';
import { isLogoutPending, markLogoutPending } from './logoutPending';
import { configureSession, type SessionRuntime } from './runtime';
import { endSession, establishSession, setSessionStatus, subscribeToAuthChannel } from './session';
import { useSessionStore } from '@/session/sessionStore';

// R-ruling on the brief's defect: the brief's `describe('establishSession')` block called
// fakeRuntime() twice per test — once in a `beforeEach` whose result was immediately discarded,
// and again inside each `it`. Every `it` here builds its own runtime, so the `beforeEach` instance
// was never observed by an assertion; it is dropped rather than kept as dead setup.
function fakeRuntime() {
  let listener: ((m: AuthMessage) => void) | null = null;
  const channel = {
    post: vi.fn(),
    subscribe: vi.fn((l: (m: AuthMessage) => void) => {
      listener = l;
      return () => {
        listener = null;
      };
    }),
  } satisfies AuthChannel;
  const runtime = {
    clearQueryCache: vi.fn(),
    channel,
    locks: createInMemoryLocks(),
    reload: vi.fn(),
    log: vi.fn(),
  } satisfies SessionRuntime;
  configureSession(runtime);
  return { runtime, deliver: (m: AuthMessage) => listener?.(m) };
}

describe('establishSession', () => {
  it('stores the token, sets me, remembers the workspace and broadcasts login', () => {
    const { runtime } = fakeRuntime();

    expect(establishSession(ownerSession)).toBe('established');

    expect(getAccessToken()).toBe(ownerSession.accessToken);
    expect(useSessionStore.getState()).toMatchObject({
      status: 'authenticated',
      me: { email: 'ravi@shop.in', role: 'OWNER', tenantSlug: 'ravi-traders' },
    });
    expect(localStorage.getItem('easycrm.lastWorkspace')).toBe('ravi-traders');
    expect(runtime.channel.post).toHaveBeenCalledWith({
      type: 'login',
      userId: ownerSession.userId,
      tenantId: ownerSession.tenantId,
    });
  });

  it('ends the session and reloads when a refresh returns a different principal', () => {
    const { runtime } = fakeRuntime();
    establishSession(ownerSession);

    expect(establishSession(inviteeSession)).toBe('principal-changed');

    expect(getAccessToken()).toBeNull();
    expect(useSessionStore.getState()).toMatchObject({ status: 'anonymous', me: null });
    expect(runtime.clearQueryCache).toHaveBeenCalled();
    expect(runtime.reload).toHaveBeenCalledTimes(1);
  });

  it('rejects an unknown role rather than storing it', () => {
    fakeRuntime();
    expect(() => establishSession({ ...ownerSession, role: 'ADMIN' })).toThrow(/unknown role/);
    expect(getAccessToken()).toBeNull();
  });
});

describe('endSession', () => {
  it('clears the token, resets the store and clears the query cache', () => {
    const { runtime } = fakeRuntime();
    establishSession(ownerSession);

    endSession('logout');

    expect(getAccessToken()).toBeNull();
    expect(useSessionStore.getState()).toMatchObject({ status: 'anonymous', me: null });
    expect(runtime.clearQueryCache).toHaveBeenCalledTimes(1);
    // R26: endSession must actually USE its reason, not just accept it — assert it reaches
    // runtime.log so a future edit can't silently drop the parameter again.
    expect(runtime.log).toHaveBeenCalledWith('session ended: logout');
  });
});

describe('subscribeToAuthChannel', () => {
  it('ends this tab’s session when another tab logs out', () => {
    const { deliver } = fakeRuntime();
    subscribeToAuthChannel();
    establishSession(ownerSession);

    deliver({ type: 'logout' });

    expect(useSessionStore.getState().status).toBe('anonymous');
  });

  it('ends and reloads when another tab signs in as someone else', () => {
    const { runtime, deliver } = fakeRuntime();
    subscribeToAuthChannel();
    establishSession(ownerSession);

    deliver({ type: 'login', userId: inviteeSession.userId, tenantId: inviteeSession.tenantId });

    expect(useSessionStore.getState().me).toBeNull();
    expect(runtime.reload).toHaveBeenCalledTimes(1);
  });

  it('ignores a login broadcast for the same principal', () => {
    const { runtime, deliver } = fakeRuntime();
    subscribeToAuthChannel();
    establishSession(ownerSession);

    deliver({ type: 'login', userId: ownerSession.userId, tenantId: ownerSession.tenantId });

    expect(useSessionStore.getState().status).toBe('authenticated');
    expect(runtime.reload).not.toHaveBeenCalled();
  });

  // P14/Security-1: a sign-out the server has not confirmed must NOT put other tabs on the login
  // page — the cookie is still live there, and a reload would sign the same user back in.
  it('shows signing-out, not anonymous, when another tab is mid sign-out', () => {
    const { deliver } = fakeRuntime();
    subscribeToAuthChannel();
    establishSession(ownerSession);

    deliver({ type: 'signing-out' });

    expect(useSessionStore.getState()).toMatchObject({ status: 'signing-out', me: null });
    expect(getAccessToken()).toBeNull(); // local state is cleared either way
  });

  // Challenge #103: found only by a real two-tab E2E run (cross-tab-logout.spec.ts), never by this
  // unit suite, because no test here delivered `logout` AFTER `signing-out` had already cleared
  // `me`. The old code gated `logout` behind `if (!me) return`, so the confirming broadcast that is
  // supposed to release the P14/Security-1 blocking screen was silently dropped for exactly the tab
  // that needed it — that tab could never leave `signing-out`.
  it('ends signing-out on the confirming logout broadcast, even though me is already null', () => {
    const { deliver } = fakeRuntime();
    subscribeToAuthChannel();
    establishSession(ownerSession);
    deliver({ type: 'signing-out' });
    expect(useSessionStore.getState().status).toBe('signing-out');

    deliver({ type: 'logout' });

    expect(useSessionStore.getState()).toMatchObject({ status: 'anonymous', me: null });
  });

  // Challenge #105 (accepted residual, deliberately NOT gated further — see the comment above the
  // `logout` branch in session.ts). This locks in the current, INTENTIONAL behavior: `logout` ends
  // the blocking screen even while the durable `logoutPending` marker is still set, i.e. even for a
  // broadcast that could be forged rather than the genuine confirmation. A gate keyed on
  // `isLogoutPending()` looked cheap (the real flow clears the marker synchronously before
  // broadcasting) but rests on an assumption — that a localStorage write is reliably visible to
  // another tab before a BroadcastChannel message posted after it — that was tested empirically in
  // two real Chromium tabs (task-14-report.md) and found FALSE: a tight loop of
  // `localStorage.setItem(seq)` immediately followed by `channel.postMessage({seq})`, 3000
  // iterations, produced real ordering violations on every run (2/3000, then 52/3000). Shipping that
  // gate would sometimes reject the genuine confirming broadcast too, reintroducing the #103 stuck
  // screen — strictly worse than this residual, which has no durable or credential impact. If this
  // test ever needs to change to assert the tab STAYS blocked instead, the ordering assumption above
  // must be re-verified in real tabs first, not just re-implemented.
  it('accepted residual: a bare logout still ends signing-out even while the marker is still set', () => {
    const { deliver } = fakeRuntime();
    subscribeToAuthChannel();
    establishSession(ownerSession);
    markLogoutPending({ userId: ownerSession.userId, tenantId: ownerSession.tenantId });
    deliver({ type: 'signing-out' });
    expect(isLogoutPending()).toBe(true); // the durable marker is still set — a real sign-out is mid-flight

    deliver({ type: 'logout' }); // indistinguishable, at this layer, from a forged broadcast

    expect(useSessionStore.getState()).toMatchObject({ status: 'anonymous', me: null });
  });

  it('ignores a stray logout broadcast when this tab was never told to block', () => {
    const { deliver } = fakeRuntime();
    subscribeToAuthChannel(); // no establishSession(): this tab is still 'booting', never signing-out

    deliver({ type: 'logout' });

    // Unchanged from before this fix: `me` is null and status isn't 'signing-out', so this is a
    // no-op — a booting tab settles through boot.ts's own refresh, not a channel message.
    expect(useSessionStore.getState().status).toBe('booting');
  });

  // Fix round 1, item 2 (Critical, security): a bare `login` broadcast used to clear a signing-out
  // tab straight to 'anonymous' — trusting a same-origin postMessage that any script can forge, with
  // no server round trip. That is the Critical fix this replaces: subscribeToAuthChannel() ALONE
  // (no verification wiring) must now leave a signing-out tab exactly as it was. Settling a pending
  // logout on a `login` broadcast requires verifying the claim against the server first — that
  // decision now lives in logout.ts's onLoginBroadcast(), wired from start.ts and covered by
  // start.test.ts (the different-principal / same-principal / 401 / forged-claim cases).
  it('does NOT leave signing-out on a bare login broadcast — verification lives in start.ts', () => {
    const { deliver } = fakeRuntime();
    subscribeToAuthChannel();
    establishSession(ownerSession);
    deliver({ type: 'signing-out' });

    deliver({ type: 'login', userId: inviteeSession.userId, tenantId: inviteeSession.tenantId });

    expect(useSessionStore.getState().status).toBe('signing-out');
  });
});

// Task 6: boot.ts and logout.ts move `status` through this export instead of reaching for
// useSessionStore.setState directly (Task 5's transition() discipline).
describe('setSessionStatus', () => {
  it('writes status without disturbing the current me', () => {
    fakeRuntime();
    establishSession(ownerSession);

    setSessionStatus('unreachable');

    expect(useSessionStore.getState()).toMatchObject({
      status: 'unreachable',
      me: { userId: ownerSession.userId },
    });
  });
});
