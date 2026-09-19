import { bareApi } from '@/api/client';
import type * as Client from '@/api/client';
import { parseRetryAfter } from '@/api/errors';
import type { AuthMessage } from './authChannel';
import type { RefreshCallResult } from './refreshCall';
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
  /**
   * Fix round 1, item 2: the durable marker's CURRENT value, read fresh each time — gates
   * `onLoginBroadcast` so a tab with nothing pending doesn't pay for a network round trip on every
   * `login` message it sees.
   */
  isPending(): boolean;
  /**
   * Fix round 1, item 2: who this tab was signing out, captured BEFORE `endSession` clears `me`.
   * `null` when this `logout()` call has no local session to read — e.g. boot resumed a marker a
   * discarded tab left behind, with no `me` of its own yet. See the report's residual-limitation
   * note: an unknown owner cannot be proven "different", so `onLoginBroadcast` stays conservative.
   */
  currentPrincipal(): { userId: string; tenantId: string } | null;
  /**
   * Fix round 1, item 2: a LOCKED refresh (P15), used ONLY to verify a `login` broadcast's claim
   * against the server — never to establish a session in this tab. A same-origin `postMessage` is
   * not proof of anything; this is.
   */
  verifyPrincipal(): Promise<RefreshCallResult>;
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
  // Fix round 1, item 2: who this tab was signing out, captured before endSession() clears `me`.
  let signingOutPrincipal: { userId: string; tenantId: string } | null = null;

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

  /** The only two ways this tab is allowed to consider a pending logout resolved. */
  function settleVerified(): void {
    settled = true;
    stopRetrying?.();
    deps.clearPending();
    deps.setStatus('anonymous');
  }

  function finish(result: LogoutResult): void {
    if (result.kind === 'done') {
      settled = true;
      deps.clearPending();
      deps.broadcastLogout({ type: 'logout' });
      deps.setStatus('anonymous'); // spec §4.4: the login page ONLY after a 204
      return;
    }
    // Fix round 1, item 5: something else (onLoginBroadcast's verified settlement) already resolved
    // this while our own POST was still in flight — a late 'failed'/'forbidden' resolution must not
    // log a stale complaint or arm a fresh retry timer nobody will ever cancel.
    if (settled) return;
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
        // Captured before endSession() clears `me` — this is the only chance to know who we're
        // signing out (null when boot resumes a marker with no local session of its own).
        signingOutPrincipal = deps.currentPrincipal();
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
    /**
     * Fix round 1, item 2 (Critical, security). A `login` broadcast is same-origin `postMessage` —
     * any script on the page can forge one — so it is never, on its own, proof that the server
     * revoked this device's stale cookie. Ask the server directly, under the same lock every
     * cookie-writing call uses (`deps.verifyPrincipal`, P15), and decide from what it says:
     *  - 401 (no cookie at all): the sign-out is effectively done — settle.
     *  - 200 for a principal DIFFERENT from the one we're signing out: a real login genuinely
     *    happened (the server's own login/signup/accept revokes whatever cookie was presented), so
     *    our stale cookie is already gone — settle.
     *  - 200 for the SAME principal we're signing out: nothing has actually changed — the
     *    broadcast was forged or stale — keep retrying.
     *  - 200 with no known `signingOutPrincipal`, or 403/network: can't prove anything either way —
     *    stay pending. This is P14's accepted "fails toward signed-out" bias, not a bug: the
     *    alternative (settling on an unverifiable claim) is exactly the hole this fixes.
     */
    async onLoginBroadcast(): Promise<void> {
      if (settled || !deps.isPending()) return;
      const result = await deps.verifyPrincipal();
      if (result.kind === 'unauthorized') {
        settleVerified();
        return;
      }
      if (result.kind === 'ok') {
        const sameAsOwner =
          signingOutPrincipal !== null &&
          result.body.userId === signingOutPrincipal.userId &&
          result.body.tenantId === signingOutPrincipal.tenantId;
        if (sameAsOwner) {
          deps.log(
            'a login broadcast arrived while a sign-out was pending, but the server-verified cookie ' +
              'still belongs to the same principal — treating it as forged or stale, still retrying',
          );
          return;
        }
        if (signingOutPrincipal === null) {
          deps.log(
            'a login broadcast arrived while a sign-out was pending, but this device has no record ' +
              'of who it was signing out — cannot verify, staying pending',
          );
          return;
        }
        settleVerified();
        return;
      }
      // forbidden / unavailable: undetermined; leave the pending state exactly as it is.
      deps.log(`could not verify a login broadcast against a pending logout (${result.kind}) — staying pending`);
    },
    stop(): void {
      stopRetrying?.();
    },
  };
}
