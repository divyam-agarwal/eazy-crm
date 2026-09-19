import { getAuthBridge, type AuthBridge } from './authBridge';

export const CLIENT_HEADER = 'X-EasyCRM-Client';
export const REQUEST_TIMEOUT_MS = 15_000;

export type FetchLike = (request: Request) => Promise<Response>;

/** The request could not be completed, but the session is intact: the caller may retry. */
export class RetryableRequestError extends Error {
  constructor(cause?: unknown) {
    super('request failed and may be retried', { cause });
    this.name = 'RetryableRequestError';
  }
}

// Routes whose 401 means "wrong credentials" or "no session", never "access token expired".
//
// /api/v1/auth/signup/status is deliberately NOT in this list, even though the generated contract
// documents a 401 for it. Verified against the backend source, not assumed: SecurityConfig.java
// permitAll()s GET /api/v1/auth/signup/status, so it never reaches the "authenticated" gate that
// produces a 401 at all; and JwtAuthenticationFilter.java swallows an invalid/expired bearer token
// into an unauthenticated (not rejected) request for any route that doesn't require auth --
// "invalid token: leave unauthenticated; SecurityConfig will 401 protected routes" (this route
// isn't protected). The controller method itself (AuthController.signupStatus()) does no
// auth-dependent check either. So this route cannot answer 401 in practice regardless of what the
// contract documents, and refresh()-on-401 here would never trigger — exempting it would be inert,
// not wrong, but the contract's documented 401 for a permitAll route looks like a stray artifact
// worth flagging to whoever owns the backend contract, not a reason to grow this list speculatively.
const REFRESH_EXEMPT = [
  /^\/api\/v1\/auth\/(login|signup|refresh|logout)$/,
  /^\/api\/v1\/auth\/invitations\//,
];

export function isRefreshExempt(url: string): boolean {
  const { pathname } = new URL(url);
  return REFRESH_EXEMPT.some((pattern) => pattern.test(pathname));
}

interface RequestSnapshot {
  url: string;
  method: string;
  headers: Headers;
  body: ArrayBuffer | undefined;
  /** The CALLER's signal (TanStack Query passes one to every queryFn). Kept, never replaced. */
  callerSignal: AbortSignal | null;
}

// Plan decision P4: read the body once, then build a fresh Request per attempt. A Request body is a
// single-use stream, so a retry built from the original would send nothing.
async function snapshot(request: Request): Promise<RequestSnapshot> {
  const body =
    request.method === 'GET' || request.method === 'HEAD' ? undefined : await request.arrayBuffer();
  return {
    url: request.url,
    method: request.method,
    headers: new Headers(request.headers),
    body,
    callerSignal: request.signal ?? null,
  };
}

function build(s: RequestSnapshot, token: string | null, timeoutMs: number): Request {
  const headers = new Headers(s.headers);
  headers.set(CLIENT_HEADER, 'web');
  if (token) headers.set('Authorization', `Bearer ${token}`);
  else headers.delete('Authorization');
  // Architecture-4: the timeout is OURS, the other signal is the CALLER's. Dropping the caller's
  // makes every request uncancellable — a superseded type-ahead in F2 would hold its connection
  // for the full 15 s, and clearing the query cache on logout could not abort a request still
  // carrying the previous user's bearer token. A fresh timeout per attempt is deliberate: each
  // retry gets its own budget.
  const timeout = AbortSignal.timeout(timeoutMs);
  const signal = s.callerSignal ? AbortSignal.any([s.callerSignal, timeout]) : timeout;
  return new Request(s.url, { method: s.method, headers, body: s.body, signal });
}

const globalFetch: FetchLike = (request) => globalThis.fetch(request);

// AbortSignal.any() resolves an already-aborted result SYNCHRONOUSLY, at construction, whenever one
// of its sources is aborted before it runs — and per the WHATWG spec it never re-fires the 'abort'
// event for listeners attached afterward (the transition already happened). Because this module
// always awaits `snapshot()` before calling `build()`, there's an unavoidable microtask gap between
// receiving a request and dispatching it; a caller that aborts in that gap hands us an
// already-aborted combined signal. Native `fetch()` handles this by checking `signal.aborted`
// synchronously before doing anything else, rather than relying solely on the event — so we do too.
// Skipping this check would silently hang forever on an abort that lands in that gap.
async function send(fetchImpl: FetchLike, request: Request): Promise<Response> {
  if (request.signal.aborted) throw request.signal.reason;
  return fetchImpl(request);
}

export function createAuthFetch(
  opts: { bridge?: () => AuthBridge; fetchImpl?: FetchLike; timeoutMs?: number } = {},
): FetchLike {
  const bridgeOf = opts.bridge ?? getAuthBridge;
  const fetchImpl = opts.fetchImpl ?? globalFetch;
  const timeoutMs = opts.timeoutMs ?? REQUEST_TIMEOUT_MS;

  return async (request) => {
    const snap = await snapshot(request);
    const bridge = bridgeOf();
    const tokenAtSend = bridge.getAccessToken();
    const first = await send(fetchImpl, build(snap, tokenAtSend, timeoutMs));
    if (first.status !== 401 || isRefreshExempt(snap.url)) return first;

    // AuthBridge.refresh is contracted to never reject (its result is a typed RefreshOutcome, not
    // an exception). Defended from this side of the seam too, matching the coordinator's own
    // guard against onRefreshed throwing (refreshCoordinator.ts) — the contract should hold
    // whichever side someone breaks it from.
    let outcome: Awaited<ReturnType<AuthBridge['refresh']>>;
    try {
      outcome = await bridge.refresh(tokenAtSend);
    } catch {
      bridge.sessionExpired();
      return first;
    }
    if (outcome === 'unavailable') throw new RetryableRequestError();
    if (outcome === 'ended') {
      bridge.sessionExpired();
      return first;
    }
    const retried = await send(fetchImpl, build(snap, bridge.getAccessToken(), timeoutMs));
    if (retried.status === 401) bridge.sessionExpired();
    return retried;
  };
}

/**
 * For refresh and logout only. Skips Authorization and 401 handling — it must never recurse into a
 * refresh — but still sends X-EasyCRM-Client, without which both routes answer 403 (spec §4.4).
 */
export function createBareFetch(opts: { fetchImpl?: FetchLike; timeoutMs?: number } = {}): FetchLike {
  const fetchImpl = opts.fetchImpl ?? globalFetch;
  const timeoutMs = opts.timeoutMs ?? REQUEST_TIMEOUT_MS;
  return async (request) => send(fetchImpl, build(await snapshot(request), null, timeoutMs));
}
