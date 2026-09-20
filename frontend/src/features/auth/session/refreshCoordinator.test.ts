// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { ownerSession } from '@/test/fixtures';
import { holdCookieLock } from '@/test/locks';
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
  onRefreshedThrows?: boolean;
}) {
  const deps = {
    locks: opts.locks ?? createInMemoryLocks(),
    callRefresh: vi.fn(opts.call),
    getToken: opts.token ?? (() => 'token-1'),
    onRefreshed: vi.fn((): 'established' | 'principal-changed' => {
      if (opts.onRefreshedThrows) throw new Error('unknown role from server: ADMIN');
      return opts.established === false ? 'principal-changed' : 'established';
    }),
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

  // P15 (spec §4.4): every path that rotates the refresh cookie must hold the SAME named lock
  // (REFRESH_LOCK = 'easycrm-refresh') as every other cookie-writing path — login, signup, invite,
  // logout and boot all go through `holdCookieLock()` in their own tests. This coordinator is the
  // one path P15 exists for (the 401 -> refresh race), and until this test existed nothing proved it
  // uses that same lock NAME rather than merely serializing against itself: swapping the imported
  // REFRESH_LOCK for a private string literal inside refreshCoordinator.ts left every other test in
  // this file green, because they all share one in-memory LockProvider instance and never contend
  // against a lock held from OUTSIDE the coordinator under a name the coordinator does not also use.
  it('holds REFRESH_LOCK — the same lock every other cookie-writing path holds — before calling callRefresh', async () => {
    const hold = holdCookieLock();
    await hold.acquired;
    const { deps, coordinator: c } = coordinator({
      locks: hold.locks,
      call: async () => ({ kind: 'ok', body: ownerSession }),
    });

    const refreshPromise = c.refresh('token-1');
    // Real time, not a fake timer: only a call genuinely queued behind the externally-held
    // REFRESH_LOCK can fail to have run by then (see start.test.ts's identical reasoning).
    await new Promise((resolve) => setTimeout(resolve, 150));
    expect(deps.callRefresh).not.toHaveBeenCalled();

    hold.release();
    await expect(refreshPromise).resolves.toBe('refreshed');
    expect(deps.callRefresh).toHaveBeenCalledTimes(1);
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

  // Fix round 1, item 1: onRefreshed (establishSession → toMe) THROWS on a role this bundle
  // doesn't recognize (ROADMAP 4a — a platform-admin role ships server-side, and any tab still on
  // an older bundle refreshes into a 200 body it can't parse). Without a guard, that throw would
  // propagate out of refresh() and reject the coordinator's promise — breaking AuthBridge.refresh's
  // documented no-reject contract from this side of the seam. It must behave exactly like an
  // explicit 401: a terminal 'ended', onUnauthorized called, and the failure logged so it isn't
  // silently swallowed.
  it('treats a throwing onRefreshed the same as an explicit 401, not as a rejected promise', async () => {
    const { deps, coordinator: c } = coordinator({
      call: async () => ({ kind: 'ok', body: ownerSession }),
      onRefreshedThrows: true,
    });

    await expect(c.refresh('token-1')).resolves.toBe('ended');
    expect(deps.onUnauthorized).toHaveBeenCalledTimes(1);
    expect(deps.log).toHaveBeenCalledWith(expect.stringContaining('onRefreshed threw'));
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

  // R44(b), rebuilt in fix round 1 (item 2 — the original version could not fail: with only two
  // racers and a fake that tolerated two uses, the assertions held regardless of whether the two
  // coordinators' calls actually overlapped, or even whether `deps.locks` was consulted at all).
  //
  // Web Locks (when supported) already serialize refreshes across tabs of one origin, but the
  // fallback path (Web Locks unsupported, or any two realms that don't share a lock manager) has no
  // such serialization — createInMemoryLocks() itself only holds "within one JS realm" (see
  // lockProvider.ts). In that fallback, two tabs can both dispatch POST /api/v1/auth/refresh with
  // the SAME refresh cookie at once. The backend's 30s single-use grace on a just-rotated token is
  // what stops that from becoming a double sign-out: the "losing" concurrent request, which presents
  // a token the server just rotated away, is honoured once more instead of being treated as token
  // replay/theft — but the grace is BOUNDED to one extra use, not "as many as happen to race".
  //
  // This version models that bound explicitly (a fake backend that tolerates exactly two uses of
  // one token generation, then rejects), adds a THIRD, later caller on the same stale token that
  // must be rejected — the only way to prove the cap is real rather than coincidentally never
  // reached — and asserts the concurrency premise itself (that A and B's backend calls genuinely
  // overlapped), so a coordinator that accidentally serialized them would fail here even though
  // every outcome-level assertion would still look right.
  it('two tabs racing the same rotated-away token both refresh; a third, later use of it is rejected', async () => {
    const GRACE_CAPACITY = 2; // the original use plus one grace re-use — matches the backend's model
    let inFlight = 0;
    let maxInFlight = 0;
    let uses = 0;
    const sharedBackend = async (): Promise<RefreshCallResult> => {
      inFlight += 1;
      maxInFlight = Math.max(maxInFlight, inFlight);
      await new Promise((r) => setTimeout(r, 10)); // hold the "network" open so a real race can form
      inFlight -= 1;
      uses += 1;
      return uses <= GRACE_CAPACITY ? { kind: 'ok', body: ownerSession } : { kind: 'unauthorized' };
    };
    const tabA = coordinator({ locks: createInMemoryLocks(), call: sharedBackend });
    const tabB = coordinator({ locks: createInMemoryLocks(), call: sharedBackend });
    const tabC = coordinator({ locks: createInMemoryLocks(), call: sharedBackend });

    // A and B race genuinely concurrently on the token the grace window exists to cover.
    const [outcomeA, outcomeB] = await Promise.all([
      tabA.coordinator.refresh('token-1'),
      tabB.coordinator.refresh('token-1'),
    ]);

    // The concurrency premise itself: without independent locks actually letting both calls run at
    // once, this would be 1, and everything below would still pass — that was fix round 1's finding.
    expect(maxInFlight).toBe(2);
    expect(outcomeA).toBe('refreshed');
    expect(outcomeB).toBe('refreshed');
    expect(tabA.deps.onUnauthorized).not.toHaveBeenCalled();
    expect(tabB.deps.onUnauthorized).not.toHaveBeenCalled();

    // C arrives afterward, still presenting the same now-doubly-used token (a third tab that also
    // had it cached). The grace is spent: this MUST be rejected, proving the cap is "one extra use",
    // not "however many callers happen to race".
    const outcomeC = await tabC.coordinator.refresh('token-1');
    expect(outcomeC).toBe('ended');
    expect(tabC.deps.onUnauthorized).toHaveBeenCalledTimes(1);
  });
});
