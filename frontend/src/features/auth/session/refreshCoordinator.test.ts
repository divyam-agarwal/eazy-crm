// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { ownerSession } from '@/test/fixtures';
import { createInMemoryLocks, type LockProvider } from './lockProvider';
import { REFRESH_FORBIDDEN_MESSAGE, type RefreshCallResult } from './refreshCall';
import { createRefreshCoordinator } from './refreshCoordinator';

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => (resolve = r));
  return { promise, resolve };
}

function coordinator(opts: {
  locks?: LockProvider;
  token?: () => string | null;
  call: () => Promise<RefreshCallResult>;
  established?: boolean;
}) {
  const deps = {
    locks: opts.locks ?? createInMemoryLocks(),
    callRefresh: vi.fn(opts.call),
    getToken: opts.token ?? (() => 'token-1'),
    onRefreshed: vi.fn(
      (): 'established' | 'principal-changed' =>
        opts.established === false ? 'principal-changed' : 'established',
    ),
    onUnauthorized: vi.fn(),
    log: vi.fn(),
  };
  return { deps, coordinator: createRefreshCoordinator(deps) };
}

describe('refresh coordinator', () => {
  it('shares one in-flight refresh between concurrent callers in a tab', async () => {
    const gate = deferred<RefreshCallResult>();
    const { deps, coordinator: c } = coordinator({ call: () => gate.promise });

    const first = c.refresh('token-1');
    const second = c.refresh('token-1');
    gate.resolve({ kind: 'ok', body: ownerSession });

    expect(await first).toBe('refreshed');
    expect(await second).toBe('refreshed');
    expect(deps.callRefresh).toHaveBeenCalledTimes(1);
  });

  it('serializes refreshes across tabs that share a lock', async () => {
    const locks = createInMemoryLocks();
    let inFlight = 0;
    let maxInFlight = 0;
    const call = async (): Promise<RefreshCallResult> => {
      inFlight += 1;
      maxInFlight = Math.max(maxInFlight, inFlight);
      await new Promise((r) => setTimeout(r, 10));
      inFlight -= 1;
      return { kind: 'ok', body: ownerSession };
    };
    const tabA = coordinator({ locks, call }).coordinator;
    const tabB = coordinator({ locks, call }).coordinator;

    await Promise.all([tabA.refresh('token-1'), tabB.refresh('token-1')]);

    expect(maxInFlight).toBe(1);
  });

  it('does not refresh when the token already changed since the failing request', async () => {
    const { deps, coordinator: c } = coordinator({
      token: () => 'token-2',
      call: async () => ({ kind: 'ok', body: ownerSession }),
    });

    expect(await c.refresh('token-1')).toBe('refreshed');
    expect(deps.callRefresh).not.toHaveBeenCalled();
  });

  it('ends the session on 401', async () => {
    const { deps, coordinator: c } = coordinator({ call: async () => ({ kind: 'unauthorized' }) });

    expect(await c.refresh('token-1')).toBe('ended');
    expect(deps.onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it('keeps the session on a network, 5xx or 429 failure', async () => {
    const { deps, coordinator: c } = coordinator({
      call: async () => ({ kind: 'unavailable', retryAfterSeconds: 5 }),
    });

    expect(await c.refresh('token-1')).toBe('unavailable');
    expect(deps.onUnauthorized).not.toHaveBeenCalled();
  });

  it('treats 403 as a logged client bug, never as a signed-out user', async () => {
    const { deps, coordinator: c } = coordinator({ call: async () => ({ kind: 'forbidden' }) });

    expect(await c.refresh('token-1')).toBe('unavailable');
    expect(deps.log).toHaveBeenCalledWith(REFRESH_FORBIDDEN_MESSAGE);
    expect(deps.onUnauthorized).not.toHaveBeenCalled();
  });

  it('reports ended when the refreshed principal differs', async () => {
    const { coordinator: c } = coordinator({
      call: async () => ({ kind: 'ok', body: ownerSession }),
      established: false,
    });

    expect(await c.refresh('token-1')).toBe('ended');
  });

  it('refreshes again after the previous refresh settled', async () => {
    const { deps, coordinator: c } = coordinator({ call: async () => ({ kind: 'ok', body: ownerSession }) });

    await c.refresh('token-1');
    await c.refresh('token-1');

    expect(deps.callRefresh).toHaveBeenCalledTimes(2);
  });

  // R44(a): AuthBridge.refresh(tokenAtFailure)'s whole shape — one shared in-flight promise per
  // coordinator, gated by tokenAtFailure — exists so that when N requests in one tab all hit a 401
  // at once (e.g. an expired access token used by five parallel queryFns), exactly ONE network call
  // reaches POST /api/v1/auth/refresh, not five. The two-caller "shares one in-flight refresh" test
  // above proves the mechanism exists; this proves it holds at N, which is the shape a real page
  // triggers (a dashboard firing several queries on mount, every one racing the same stale token).
  it('N parallel 401s in one tab produce exactly ONE refresh() call', async () => {
    const gate = deferred<RefreshCallResult>();
    const { deps, coordinator: c } = coordinator({ call: () => gate.promise });
    const N = 5;

    const calls = Array.from({ length: N }, () => c.refresh('token-1'));
    gate.resolve({ kind: 'ok', body: ownerSession });
    const outcomes = await Promise.all(calls);

    expect(outcomes).toEqual(Array<'refreshed'>(N).fill('refreshed'));
    expect(deps.callRefresh).toHaveBeenCalledTimes(1);
  });

  // R44(b): the cross-tab rotation grace window. Web Locks (when supported) already serialize
  // refreshes across tabs of one origin, but the fallback path (Web Locks unsupported, or any two
  // realms that do not share a lock manager) has no such serialization — createInMemoryLocks()
  // itself only holds "within one JS realm" (see lockProvider.ts). In that fallback, two tabs can
  // both dispatch POST /api/v1/auth/refresh with the SAME refresh cookie at once. The backend's 30s
  // single-use grace on a just-rotated token is what stops that from becoming a double sign-out: the
  // "losing" concurrent request, which presents a token the server just rotated away, is honoured
  // once more instead of being treated as token replay/theft. Model that here: two coordinators with
  // INDEPENDENT locks (no cross-tab serialization at all) race a shared backend that tolerates
  // exactly one extra concurrent use of the same token before treating a repeat as stale.
  it('two tabs refreshing near-simultaneously both end up refreshed, not logged out', async () => {
    let used = 0;
    const sharedBackend = async (): Promise<RefreshCallResult> => {
      used += 1;
      // The racing pair (both still holding the token that was "current" when they fired) both land
      // inside the grace window. A third, later call on a token nobody still holds would not.
      return used <= 2 ? { kind: 'ok', body: ownerSession } : { kind: 'unauthorized' };
    };
    const tabA = coordinator({ locks: createInMemoryLocks(), call: sharedBackend });
    const tabB = coordinator({ locks: createInMemoryLocks(), call: sharedBackend });

    const [outcomeA, outcomeB] = await Promise.all([
      tabA.coordinator.refresh('token-1'),
      tabB.coordinator.refresh('token-1'),
    ]);

    expect(outcomeA).toBe('refreshed');
    expect(outcomeB).toBe('refreshed');
    expect(tabA.deps.onUnauthorized).not.toHaveBeenCalled();
    expect(tabB.deps.onUnauthorized).not.toHaveBeenCalled();
  });
});
