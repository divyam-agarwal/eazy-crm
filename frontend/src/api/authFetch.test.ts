// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import type { AuthBridge, RefreshOutcome } from './authBridge';
import { CLIENT_HEADER, RetryableRequestError, createAuthFetch, createBareFetch } from './authFetch';

const ORIGIN = 'http://app.test';

function fakeBridge(token: string | null, outcome: RefreshOutcome) {
  let current = token;
  return {
    getAccessToken: () => current,
    refresh: vi.fn(async (_tokenAtFailure: string | null) => {
      if (outcome === 'refreshed') current = 'token-2';
      return outcome;
    }),
    sessionExpired: vi.fn(),
  } satisfies AuthBridge;
}

function recordingFetch(statuses: number[]) {
  const seen: { url: string; headers: Headers; body: string; signal: AbortSignal }[] = [];
  const impl = vi.fn(async (request: Request) => {
    seen.push({
      url: request.url,
      headers: request.headers,
      signal: request.signal, // Architecture-4: the caller-signal tests read this
      body: await request.text(),
    });
    const status = statuses.shift() ?? 200;
    return new Response(status === 204 ? null : '{}', { status });
  });
  return { impl, seen };
}

const post = (path: string, body: unknown) =>
  new Request(ORIGIN + path, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  });

describe('createAuthFetch', () => {
  it('attaches the bearer token and the client header', async () => {
    const { impl, seen } = recordingFetch([200]);
    const doFetch = createAuthFetch({ bridge: () => fakeBridge('token-1', 'refreshed'), fetchImpl: impl });

    await doFetch(new Request(`${ORIGIN}/api/v1/customers`));

    expect(seen[0]?.headers.get('Authorization')).toBe('Bearer token-1');
    expect(seen[0]?.headers.get(CLIENT_HEADER)).toBe('web');
  });

  it('sends no Authorization header when there is no token', async () => {
    const { impl, seen } = recordingFetch([200]);
    const doFetch = createAuthFetch({ bridge: () => fakeBridge(null, 'ended'), fetchImpl: impl });

    await doFetch(new Request(`${ORIGIN}/api/v1/auth/signup/status`));

    expect(seen[0]?.headers.has('Authorization')).toBe(false);
  });

  it('refreshes on 401 and retries once with the new token and the identical body', async () => {
    const { impl, seen } = recordingFetch([401, 201]);
    const bridge = fakeBridge('token-1', 'refreshed');
    const doFetch = createAuthFetch({ bridge: () => bridge, fetchImpl: impl });

    const response = await doFetch(post('/api/v1/customers', { businessName: 'Ravi Traders' }));

    expect(response.status).toBe(201);
    expect(bridge.refresh).toHaveBeenCalledWith('token-1');
    expect(seen).toHaveLength(2);
    expect(seen[1]?.headers.get('Authorization')).toBe('Bearer token-2');
    expect(seen[1]?.body).toBe(seen[0]?.body);
    expect(seen[1]?.body).toBe('{"businessName":"Ravi Traders"}');
  });

  it('ends the session when the retry is also refused', async () => {
    const { impl } = recordingFetch([401, 401]);
    const bridge = fakeBridge('token-1', 'refreshed');
    const doFetch = createAuthFetch({ bridge: () => bridge, fetchImpl: impl });

    const response = await doFetch(new Request(`${ORIGIN}/api/v1/customers`));

    expect(response.status).toBe(401);
    expect(bridge.sessionExpired).toHaveBeenCalledTimes(1);
  });

  it('ends the session without retrying when the refresh itself was refused', async () => {
    const { impl } = recordingFetch([401]);
    const bridge = fakeBridge('token-1', 'ended');
    const doFetch = createAuthFetch({ bridge: () => bridge, fetchImpl: impl });

    const response = await doFetch(new Request(`${ORIGIN}/api/v1/customers`));

    expect(response.status).toBe(401);
    expect(impl).toHaveBeenCalledTimes(1);
    expect(bridge.sessionExpired).toHaveBeenCalledTimes(1);
  });

  // Fix round 1, item 1: AuthBridge.refresh is contracted to never reject, but this guard is
  // defensive from BOTH sides of the seam (the coordinator itself now guards onRefreshed throwing —
  // see refreshCoordinator.test.ts). If it ever did reject anyway, authFetch must still reach a
  // terminal state — end the session and hand back the original 401 — rather than let the rejection
  // propagate unguarded out of doFetch().
  it('ends the session instead of throwing when bridge.refresh() itself rejects', async () => {
    const { impl } = recordingFetch([401]);
    const bridge = {
      getAccessToken: () => 'token-1',
      refresh: vi.fn(async () => {
        throw new Error('should never happen, but must not crash the caller');
      }),
      sessionExpired: vi.fn(),
    } satisfies AuthBridge;
    const doFetch = createAuthFetch({ bridge: () => bridge, fetchImpl: impl });

    const response = await doFetch(new Request(`${ORIGIN}/api/v1/customers`));

    expect(response.status).toBe(401);
    expect(bridge.sessionExpired).toHaveBeenCalledTimes(1);
  });

  it('keeps the session and fails retryably when the refresh could not be completed', async () => {
    const { impl } = recordingFetch([401]);
    const bridge = fakeBridge('token-1', 'unavailable');
    const doFetch = createAuthFetch({ bridge: () => bridge, fetchImpl: impl });

    await expect(doFetch(new Request(`${ORIGIN}/api/v1/customers`))).rejects.toBeInstanceOf(
      RetryableRequestError,
    );
    expect(bridge.sessionExpired).not.toHaveBeenCalled();
  });

  it.each([
    '/api/v1/auth/login',
    '/api/v1/auth/signup',
    '/api/v1/auth/refresh',
    '/api/v1/auth/logout',
    '/api/v1/auth/invitations/abc',
    '/api/v1/auth/invitations/abc/accept',
  ])('never refreshes on a 401 from %s', async (path) => {
    const { impl } = recordingFetch([401]);
    const bridge = fakeBridge('token-1', 'refreshed');
    const doFetch = createAuthFetch({ bridge: () => bridge, fetchImpl: impl });

    const response = await doFetch(post(path, {}));

    expect(response.status).toBe(401);
    expect(bridge.refresh).not.toHaveBeenCalled();
  });

  it('does refresh on a 401 from /api/v1/auth/me, which is bearer-authenticated', async () => {
    const { impl } = recordingFetch([401, 200]);
    const bridge = fakeBridge('token-1', 'refreshed');
    const doFetch = createAuthFetch({ bridge: () => bridge, fetchImpl: impl });

    await doFetch(new Request(`${ORIGIN}/api/v1/auth/me`));

    expect(bridge.refresh).toHaveBeenCalledTimes(1);
  });

  it('aborts a request that outlives the timeout', async () => {
    const hang = vi.fn(
      (request: Request) =>
        new Promise<Response>((_, reject) =>
          request.signal.addEventListener('abort', () => reject(request.signal.reason)),
        ),
    );
    const doFetch = createAuthFetch({ bridge: () => fakeBridge(null, 'ended'), fetchImpl: hang, timeoutMs: 20 });

    await expect(doFetch(new Request(`${ORIGIN}/api/v1/customers`))).rejects.toMatchObject({
      name: 'TimeoutError',
    });
  });

  // Architecture-4: the caller's signal must survive the snapshot/rebuild, or nothing above this
  // layer can cancel anything — TanStack Query hands a signal to every queryFn.
  it("aborts when the CALLER's signal aborts, not only on timeout", async () => {
    const hang = vi.fn(
      (request: Request) =>
        new Promise<Response>((_, reject) =>
          request.signal.addEventListener('abort', () => reject(request.signal.reason)),
        ),
    );
    const doFetch = createAuthFetch({ bridge: () => fakeBridge('t', 'refreshed'), fetchImpl: hang });
    const caller = new AbortController();

    const inFlight = doFetch(new Request(`${ORIGIN}/api/v1/customers`, { signal: caller.signal }));
    caller.abort();

    await expect(inFlight).rejects.toMatchObject({ name: 'AbortError' });
  });

  it('gives each attempt its own timeout, and the retry still carries the caller signal', async () => {
    // 401 → refresh → retry: the rebuilt request must still be linked to the caller.
    const { impl, seen } = recordingFetch([401, 200]);
    const doFetch = createAuthFetch({ bridge: () => fakeBridge('t2', 'refreshed'), fetchImpl: impl });
    const caller = new AbortController();

    await doFetch(new Request(`${ORIGIN}/api/v1/customers`, { signal: caller.signal }));

    expect(impl).toHaveBeenCalledTimes(2);
    expect(seen[1]?.signal.aborted).toBe(false);
    caller.abort();
    expect(seen[1]?.signal.aborted).toBe(true); // the retry's signal follows the caller's
  });
});

describe('createBareFetch', () => {
  it('sends the client header, no Authorization, and passes a 401 straight through', async () => {
    const { impl, seen } = recordingFetch([401]);
    const doFetch = createBareFetch({ fetchImpl: impl });

    const response = await doFetch(post('/api/v1/auth/refresh', {}));

    expect(response.status).toBe(401);
    expect(seen[0]?.headers.get(CLIENT_HEADER)).toBe('web');
    expect(seen[0]?.headers.has('Authorization')).toBe(false);
    expect(impl).toHaveBeenCalledTimes(1);
  });
});
