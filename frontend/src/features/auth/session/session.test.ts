import { describe, expect, it, vi } from 'vitest';
import { inviteeSession, ownerSession } from '@/test/fixtures';
import { getAccessToken } from './accessToken';
import type { AuthChannel, AuthMessage } from './authChannel';
import { createInMemoryLocks } from './lockProvider';
import { configureSession, type SessionRuntime } from './runtime';
import { endSession, establishSession, subscribeToAuthChannel } from './session';
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

  // Architecture-2: a tab already showing signing-out has me === null. If it ignored broadcasts on
  // that basis, a later sign-in elsewhere would leave it stuck, and its retry loop would log the
  // NEW user out. A login means the server has revoked the old cookie: the sign-out is complete.
  it('a signing-out tab returns to anonymous when any tab signs in', () => {
    const { deliver } = fakeRuntime();
    subscribeToAuthChannel();
    establishSession(ownerSession);
    deliver({ type: 'signing-out' });

    deliver({ type: 'login', userId: inviteeSession.userId, tenantId: inviteeSession.tenantId });

    expect(useSessionStore.getState().status).toBe('anonymous');
  });
});
