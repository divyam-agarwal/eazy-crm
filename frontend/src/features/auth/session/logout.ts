import { bareApi } from '@/api/client';
import type * as Client from '@/api/client';
import { parseRetryAfter } from '@/api/errors';
import type { AuthMessage } from './authChannel';
import type { EndReason } from './session';
import type { SessionStatus } from '@/session/types';

export const LOGOUT_RETRY_MS = 5_000;

/** Minor-4: a 403 is a client bug (retrying forever is pointless); a 429 names its own interval. */
export type LogoutResult =
  | { kind: 'done' }
  | { kind: 'failed'; retryAfterSeconds?: number }
  | { kind: 'forbidden' };

export async function callLogout(
  client: ReturnType<typeof Client.createBareClient> = bareApi,
): Promise<LogoutResult> {
  try {
    const { response } = await client.POST('/api/v1/auth/logout', {
      params: { header: { 'X-EasyCRM-Client': 'web' } },
    });
    if (response.status === 204) return { kind: 'done' };
    if (response.status === 403) return { kind: 'forbidden' };
    const retryAfterSeconds =
      response.status === 429 ? parseRetryAfter(response.headers.get('Retry-After')) : undefined;
    return { kind: 'failed', retryAfterSeconds };
  } catch {
    return { kind: 'failed' };
  }
}

export interface LogoutDeps {
  callLogout(): Promise<LogoutResult>;
  endSession(reason: EndReason): void;
  broadcastLogout(message: AuthMessage): void;
  /** P14 — the durable marker. `mark` before the POST, `clear` only once the server confirms. */
  markPending(): void;
  clearPending(): void;
  setStatus(status: SessionStatus): void;
  onOnline(fn: () => void): () => void;
  schedule(fn: () => void, ms: number): () => void;
  log(message: string): void;
}

/**
 * Spec §4.4. The cookie is httpOnly, so JS cannot delete it: until the server answers 204 the device
 * is still signed in, whatever local state says. On a shared counter phone that is the difference
 * between signed out and looking signed out.
 *
 * <p>P14/Security-1: the "logout owed" state is therefore DURABLE (a localStorage marker), not just
 * a status in this tab's memory — the tab can be closed or discarded by Android while the cookie
 * lives another 30 days. Other tabs are told `signing-out`, never `logout`, until the 204 lands.
 *
 * <p>P15: the POST runs inside the refresh Web Lock, so it cannot interleave with a rotation
 * (logout racing a grace refresh can otherwise leave the successor live — HANDOFF's backend
 * follow-up, whose named mitigation is this lock).
 */
export function createLogout(deps: LogoutDeps) {
  let stopRetrying: (() => void) | null = null;
  let inFlight: Promise<void> | null = null;
  let settled = false;

  function scheduleRetry(retryAfterSeconds?: number) {
    const cleanup = () => {
      offOnline();
      cancelTimer();
      stopRetrying = null;
    };
    const again = () => {
      cleanup();
      void retry();
    };
    const ms = retryAfterSeconds ? Math.min(Math.max(retryAfterSeconds, 1), 60) * 1_000 : LOGOUT_RETRY_MS;
    const offOnline = deps.onOnline(again);
    const cancelTimer = deps.schedule(again, ms);
    stopRetrying = cleanup;
  }

  function finish(result: LogoutResult): void {
    if (result.kind === 'done') {
      settled = true;
      deps.clearPending();
      deps.broadcastLogout({ type: 'logout' });
      deps.setStatus('anonymous'); // spec §4.4: the login page ONLY after a 204
      return;
    }
    if (result.kind === 'forbidden') {
      // The same bug class boot treats as unreachable: this client is sending something wrong, so
      // 5 s forever achieves nothing. The marker stays, so the next boot tries again.
      deps.log('logout was refused (X-EasyCRM-Client) — not retrying automatically');
      return;
    }
    scheduleRetry(result.retryAfterSeconds);
  }

  async function retry(): Promise<void> {
    if (settled) return; // Architecture-2: another tab signed in; the old cookie is already revoked
    finish(await deps.callLogout());
  }

  return {
    async logout(): Promise<void> {
      if (inFlight) return inFlight; // Minor-1: a double tap must not start a second loop
      inFlight = (async () => {
        // Local state goes FIRST (Security-3): on 4G the POST can take up to 15 s, and the
        // previous user's screen must not stay readable for that long.
        deps.markPending();
        deps.endSession('logout');
        deps.setStatus('signing-out');
        deps.broadcastLogout({ type: 'signing-out' });
        finish(await deps.callLogout());
      })().finally(() => {
        inFlight = null;
      });
      return inFlight;
    },
    /** A `login` broadcast: the server revoked the pending cookie for us. Stop retrying. */
    settledElsewhere(): void {
      settled = true;
      stopRetrying?.();
      deps.clearPending();
    },
    stop(): void {
      stopRetrying?.();
    },
  };
}
