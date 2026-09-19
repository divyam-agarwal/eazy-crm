// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { ownerSession } from '@/test/fixtures';
import { createBoot, nextBootDelayMs } from './boot';
import { REFRESH_FORBIDDEN_MESSAGE, type RefreshCallResult } from './refreshCall';
import type { SessionStatus } from '@/session/types';

// R33(b): the brief's harness omits `logoutPending`/`finishPendingLogout`, which the
// "finishes a pending logout" test below already assumes exist on `deps`.
function harness(results: RefreshCallResult[]) {
  let status: SessionStatus = 'booting';
  const scheduled: { fn: () => void; ms: number; cancel: ReturnType<typeof vi.fn> }[] = [];
  const deps = {
    refresh: vi.fn(async () => results.shift() ?? { kind: 'unavailable' as const }),
    establish: vi.fn(() => {
      status = 'authenticated';
    }),
    getStatus: () => status,
    setStatus: vi.fn((s: SessionStatus) => {
      status = s;
    }),
    schedule: vi.fn((fn: () => void, ms: number) => {
      const cancel = vi.fn();
      scheduled.push({ fn, ms, cancel });
      return cancel;
    }),
    log: vi.fn(),
    logoutPending: vi.fn(() => false),
    finishPendingLogout: vi.fn(async () => {}),
  };
  return { deps, boot: createBoot(deps), scheduled, status: () => status, setStatus: (s: SessionStatus) => (status = s) };
}

describe('nextBootDelayMs (plan decision P2)', () => {
  it('retries first within 5 s — inside the 30 s grace window — then backs off', () => {
    expect([0, 1, 2, 3, 4, 9].map((a) => nextBootDelayMs(a))).toEqual([2000, 5000, 15000, 30000, 30000, 30000]);
  });

  it('lets a 429 Retry-After win, clamped to 1–60 s', () => {
    expect(nextBootDelayMs(0, 20)).toBe(20_000);
    expect(nextBootDelayMs(0, 0)).toBe(1_000);
    expect(nextBootDelayMs(0, 600)).toBe(60_000);
  });
});

describe('boot', () => {
  it('establishes the session on 200', async () => {
    const h = harness([{ kind: 'ok', body: ownerSession }]);
    await h.boot.start();
    expect(h.deps.establish).toHaveBeenCalledWith(ownerSession);
  });

  it('is anonymous on 401', async () => {
    const h = harness([{ kind: 'unauthorized' }]);
    await h.boot.start();
    expect(h.status()).toBe('anonymous');
  });

  it('treats 403 as a client bug: logged, unreachable, never anonymous, no automatic retry', async () => {
    const h = harness([{ kind: 'forbidden' }]);
    await h.boot.start();
    expect(h.deps.log).toHaveBeenCalledWith(REFRESH_FORBIDDEN_MESSAGE);
    expect(h.status()).toBe('unreachable');
    expect(h.deps.schedule).not.toHaveBeenCalled();
  });

  it('is unreachable on a network failure and retries automatically, first after 2 s', async () => {
    const h = harness([{ kind: 'unavailable' }, { kind: 'unavailable' }, { kind: 'ok', body: ownerSession }]);

    await h.boot.start();
    expect(h.status()).toBe('unreachable');
    expect(h.scheduled[0]?.ms).toBe(2_000);

    h.scheduled[0]?.fn();
    await vi.waitFor(() => expect(h.scheduled[1]?.ms).toBe(5_000));

    h.scheduled[1]?.fn();
    await vi.waitFor(() => expect(h.deps.establish).toHaveBeenCalled());
  });

  it('waits for Retry-After on a 429', async () => {
    const h = harness([{ kind: 'unavailable', retryAfterSeconds: 20 }]);
    await h.boot.start();
    expect(h.scheduled[0]?.ms).toBe(20_000);
  });

  it('cancels the pending automatic retry when the user retries now', async () => {
    const h = harness([{ kind: 'unavailable' }, { kind: 'ok', body: ownerSession }]);
    await h.boot.start();

    await h.boot.retryNow();

    expect(h.scheduled[0]?.cancel).toHaveBeenCalled();
    expect(h.deps.establish).toHaveBeenCalled();
  });

  it('discards a boot result that arrives after the user signed in interactively (plan decision P6)', async () => {
    let finish!: (r: RefreshCallResult) => void;
    const h = harness([]);
    h.deps.refresh.mockImplementationOnce(() => new Promise((r) => (finish = r)));

    const started = h.boot.start();
    h.setStatus('authenticated'); // login succeeded while the boot refresh was in flight
    finish({ kind: 'unauthorized' });
    await started;

    expect(h.status()).toBe('authenticated');
  });

  it('stop cancels a scheduled retry', async () => {
    const h = harness([{ kind: 'unavailable' }]);
    await h.boot.start();
    h.boot.stop();
    expect(h.scheduled[0]?.cancel).toHaveBeenCalled();
  });

  // P14/Security-1 — the whole point of the durable marker.
  it('finishes a pending logout instead of refreshing', async () => {
    const h = harness([{ kind: 'ok', body: ownerSession }]);
    h.deps.logoutPending.mockReturnValue(true);

    await h.boot.start();

    expect(h.deps.finishPendingLogout).toHaveBeenCalledTimes(1);
    expect(h.deps.refresh).not.toHaveBeenCalled();
    expect(h.deps.establish).not.toHaveBeenCalled();
  });

  // Architecture-5: an unknown role must not strand the splash.
  it('leaves booting when establish throws on a role this build does not know', async () => {
    const h = harness([{ kind: 'ok', body: { ...ownerSession, role: 'PLATFORM_ADMIN' } }]);
    h.deps.establish.mockImplementation(() => {
      throw new Error('unknown role from server: PLATFORM_ADMIN');
    });

    await expect(h.boot.start()).resolves.toBeUndefined(); // never rejects
    expect(h.status()).toBe('unreachable');
    expect(h.deps.log).toHaveBeenCalledWith(expect.stringContaining('unknown role'));
  });
});
