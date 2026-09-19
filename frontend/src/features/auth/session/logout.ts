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

type Principal = { userId: string; tenantId: string };

export interface LogoutDeps {
  callLogout(): Promise<LogoutResult>;
  endSession(reason: EndReason): void;
  broadcastLogout(message: AuthMessage): void;
  /**
   * P14 — the durable marker. `mark` before the POST, `clear` only once the server confirms.
   * Fix round 2: also persists `principal` alongside the marker (a deliberate, narrow exception to
   * "the marker is a flag, never data" — see the report). Pass `null`/omit when this call has no
   * local session to read one from (a boot-resumed retry): the durable store must not let that
   * overwrite a real principal an earlier call over this same marker already recorded.
   */
  markPending(principal: Principal | null): void;
  clearPending(): void;
  /**
   * Fix round 1, item 2: the durable marker's CURRENT value, read fresh each time — gates
   * `onLoginBroadcast` so a tab with nothing pending doesn't pay for a network round trip on every
   * `login` message it sees.
   */
  isPending(): boolean;
  /** Who THIS tab currently believes is signed in, read before `endSession` clears it — fed into
   * `markPending` at the top of `logout()`. Not consulted by `onLoginBroadcast`; see
   * `pendingPrincipal` below for that. */
  currentPrincipal(): Principal | null;
  /**
   * Fix round 2: the DURABLE principal `markPending` persisted — read fresh, not captured once in a
   * closure. Unlike an in-memory capture, this is populated even when THIS tab never called
   * `logout()` with a real `me` of its own (boot resumed a marker a discarded tab left behind): the
   * marker and the principal that made it are always written together, by whichever tab first had
   * one to give. `null` only when nothing was ever persisted or the persisted value doesn't parse.
   */
  pendingPrincipal(): Principal | null;
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
        // Read before endSession() clears `me` — this is the only chance THIS call has to supply a
        // principal (null on a boot-resumed retry with no local session; markPending() then leaves
        // whatever was already persisted untouched rather than erasing it).
        // Local state goes FIRST (Security-3): on 4G the POST can take up to 15 s, and the
        // previous user's screen must not stay readable for that long.
        deps.markPending(deps.currentPrincipal());
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
     *  - 200 for a principal DIFFERENT from `deps.pendingPrincipal()`: a real login genuinely
     *    happened (the server's own login/signup/accept revokes whatever cookie was presented), so
     *    our stale cookie is already gone — settle.
     *  - 200 for the SAME principal: nothing has actually changed — the broadcast was forged or
     *    stale — keep retrying.
     *  - 200 with no `pendingPrincipal()` at all (both the mark and the read failed — a storage
     *    problem, not a design gap; see Challenge #89), or 403/network: can't prove anything either
     *    way — stay pending. Fix round 2 (rulings.md R59, reversed): this branch used to fire on
     *    every boot-resumed retry, because the only principal available was an in-memory capture
     *    that a freshly loaded tab never has. That was reported as "fails toward signed-out" and
     *    the framing was wrong — the security lens showed it actually terminates a DIFFERENT,
     *    legitimate, just-authenticated user's brand-new session (the zombie retry POSTs their
     *    cookie next). `deps.pendingPrincipal()` reads the DURABLE principal instead of an
     *    in-memory one, so it survives exactly the tab discard this whole feature is built around
     *    and this branch is now reachable only when storage itself failed.
     */
    async onLoginBroadcast(): Promise<void> {
      if (settled || !deps.isPending()) return;
      const result = await deps.verifyPrincipal();
      if (result.kind === 'unauthorized') {
        settleVerified();
        return;
      }
      if (result.kind === 'ok') {
        const owner = deps.pendingPrincipal();
        const sameAsOwner = owner !== null && result.body.userId === owner.userId && result.body.tenantId === owner.tenantId;
        if (sameAsOwner) {
          deps.log(
            'a login broadcast arrived while a sign-out was pending, but the server-verified cookie ' +
              'still belongs to the same principal — treating it as forged or stale, still retrying',
          );
          return;
        }
        if (owner === null) {
          deps.log(
            'a login broadcast arrived while a sign-out was pending, but no principal is persisted ' +
              'for it (a storage failure, not the normal case) — cannot verify, staying pending',
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
