import { describe, expect, it, vi } from 'vitest';
import { createBareClient } from '@/api/client';
import { callLogout, createLogout, LOGOUT_RETRY_MS, type LogoutResult } from './logout';
import type { AuthMessage } from './authChannel';
import type { SessionStatus } from '@/session/types';

function harness(outcomes: LogoutResult[]) {
  const calls: string[] = [];
  let onlineListener: (() => void) | null = null;
  const timers: { fn: () => void; ms: number; cancel: ReturnType<typeof vi.fn> }[] = [];
  const deps = {
    callLogout: vi.fn(async () => {
      calls.push('POST');
      return outcomes.shift() ?? { kind: 'failed' as const };
    }),
    endSession: vi.fn(() => calls.push('endSession')),
    broadcastLogout: vi.fn((_m: AuthMessage) => calls.push('broadcast')),
    // P14: the durable half of the state. `mark` before the POST, `clear` only on 204.
    markPending: vi.fn(() => calls.push('mark')),
    clearPending: vi.fn(() => calls.push('clear')),
    log: vi.fn(),
    setStatus: vi.fn((s: SessionStatus) => {
      if (s === 'signing-out') calls.push('signing-out');
    }),
    onOnline: vi.fn((fn: () => void) => {
      onlineListener = fn;
      return () => {
        onlineListener = null;
      };
    }),
    schedule: vi.fn((fn: () => void, ms: number) => {
      const cancel = vi.fn();
      timers.push({ fn, ms, cancel });
      return cancel;
    }),
  };
  return { deps, logout: createLogout(deps), calls, timers, goOnline: () => onlineListener?.() };
}

describe('logout', () => {
  // Minor-1 (architecture) + Security-3: local state and the blocking screen come FIRST, so the
  // previous user's data is off the screen immediately instead of up to 15 s later on 4G. The
  // login page still waits for the 204 (spec §4.4 step 3) — that is `anonymous`, not `signing-out`.
  //
  // R5: the implementation broadcasts `signing-out` BEFORE the POST, and the harness records every
  // broadcastLogout call — so this is a seven-entry sequence, not the plan's six-entry one. The
  // controller ruled the implementation right and the plan's array wrong (rulings.md R5).
  it('on 204: clears local state and marks pending first, then POSTs, then goes anonymous', async () => {
    const h = harness([{ kind: 'done' }]);
    await h.logout.logout();
    expect(h.calls).toEqual(['mark', 'endSession', 'signing-out', 'broadcast', 'POST', 'clear', 'broadcast']);
    expect(h.deps.endSession).toHaveBeenCalledWith('logout');
    expect(h.deps.setStatus).toHaveBeenLastCalledWith('anonymous');
    expect(h.deps.broadcastLogout).toHaveBeenLastCalledWith({ type: 'logout' });
  });

  it('on failure: stays on signing-out, keeps the durable marker, and tells other tabs the same', async () => {
    const h = harness([{ kind: 'failed' }]);
    await h.logout.logout();
    expect(h.deps.endSession).toHaveBeenCalled();
    // P14/Security-1: NOT { type: 'logout' } — that would send other tabs to /login while the
    // cookie is still live, and a reload there would sign the same user back in.
    expect(h.deps.broadcastLogout).toHaveBeenCalledWith({ type: 'signing-out' });
    expect(h.deps.clearPending).not.toHaveBeenCalled();
    expect(h.deps.setStatus).toHaveBeenLastCalledWith('signing-out');
    expect(h.timers[0]?.ms).toBe(LOGOUT_RETRY_MS);
  });

  it('is idempotent: a second tap does not start a second POST or a second retry loop', async () => {
    const h = harness([{ kind: 'failed' }, { kind: 'failed' }]);
    const first = h.logout.logout();
    const second = h.logout.logout();
    await Promise.all([first, second]);
    expect(h.deps.callLogout).toHaveBeenCalledTimes(1);
    expect(h.timers).toHaveLength(1);
  });

  it('stops retrying when any tab signs in, without another POST', async () => {
    // Architecture-2: the retry POSTs whatever cookie is in the jar NOW. After someone else signs
    // in, that is THEIR cookie, and the server would happily revoke it.
    const h = harness([{ kind: 'failed' }]);
    await h.logout.logout();

    h.logout.settledElsewhere(); // start.ts calls this on a `login` broadcast

    h.timers[0]?.fn();
    expect(h.deps.callLogout).toHaveBeenCalledTimes(1);
    expect(h.deps.clearPending).toHaveBeenCalled();
  });

  it('does not auto-retry a 403, and honours Retry-After on a 429', async () => {
    // Minor-4: a 403 means this client is broken (the same bug class boot treats as unreachable);
    // 5 s forever is pointless. A 429 must wait the server's own interval.
    const h = harness([{ kind: 'forbidden' }]);
    await h.logout.logout();
    expect(h.timers).toHaveLength(0);
    expect(h.deps.log).toHaveBeenCalled();

    const r = harness([{ kind: 'failed', retryAfterSeconds: 20 }]);
    await r.logout.logout();
    expect(r.timers[0]?.ms).toBe(20_000);
  });

  it('shows the login page only after a later 204, retried when the network returns', async () => {
    const h = harness([{ kind: 'failed' }, { kind: 'done' }]);
    await h.logout.logout();

    h.goOnline();

    await vi.waitFor(() => expect(h.deps.setStatus).toHaveBeenLastCalledWith('anonymous'));
    expect(h.timers[0]?.cancel).toHaveBeenCalled();
  });

  it('keeps retrying on a timer while the POST keeps failing', async () => {
    const h = harness([{ kind: 'failed' }, { kind: 'failed' }]);
    await h.logout.logout();

    h.timers[0]?.fn();

    await vi.waitFor(() => expect(h.timers).toHaveLength(2));
    expect(h.deps.setStatus).not.toHaveBeenCalledWith('anonymous');
  });
});

describe('callLogout', () => {
  const clientAnswering = (status: number) => {
    const seen: Request[] = [];
    const client = createBareClient(async (request) => {
      seen.push(request);
      return new Response(null, { status });
    });
    return { client, seen };
  };

  it('sends the client header and reports done on 204', async () => {
    const { client, seen } = clientAnswering(204);
    expect(await callLogout(client)).toEqual({ kind: 'done' });
    expect(seen[0]?.headers.get('X-EasyCRM-Client')).toBe('web');
  });

  it('reports failed on any other status and on a network error', async () => {
    expect(await callLogout(clientAnswering(500).client)).toEqual({ kind: 'failed' });
    const broken = createBareClient(async () => Promise.reject(new TypeError('offline')));
    expect(await callLogout(broken)).toEqual({ kind: 'failed' });
  });

  it('distinguishes a 403 client bug and carries Retry-After from a 429 (Minor-4)', async () => {
    expect(await callLogout(clientAnswering(403).client)).toEqual({ kind: 'forbidden' });
    const limited = createBareClient(
      async () => new Response('{}', { status: 429, headers: { 'Retry-After': '20' } }),
    );
    expect(await callLogout(limited)).toEqual({ kind: 'failed', retryAfterSeconds: 20 });
  });
});
