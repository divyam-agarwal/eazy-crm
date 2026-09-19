import type { AuthResponse } from '@/api/types';
import { writeLastWorkspace } from '@/lib/storage';
import { useSessionStore } from '@/session/sessionStore';
import type { Me, SessionStatus } from '@/session/types';
import { clearAccessToken, setAccessToken } from './accessToken';
import { clearLogoutPending } from './logoutPending';
import { sessionRuntime } from './runtime';
import { toMe } from './toMe';

export type EndReason = 'logout' | 'expired' | 'principal-changed' | 'remote-logout';
export type EstablishResult = 'established' | 'principal-changed';

/**
 * The ONLY place `status` is written to the store (fix round 1, item 4) — `me` is passed alongside
 * it explicitly on every call, rather than defaulted, so a transition can never silently leave a
 * stale `me` behind via Zustand's shallow merge. Task 6 adds boot.ts and logout.ts on top of this
 * module; routing every status change through one un-exported helper means they inherit one
 * auditable pattern instead of copying whichever raw `setState` call they find first.
 */
function transition(status: SessionStatus, me: Me | null): void {
  useSessionStore.setState({ status, me });
}

/** The side effects every session-ending path shares, EXCEPT the store's terminal status — each
 * caller picks its own via transition() so ending mid sign-out can land directly on 'signing-out'
 * without detouring through 'anonymous' first (that detour was a real double-write bug: two store
 * writes, two re-renders, to reach one state). */
function clearSession(reason: EndReason): void {
  sessionRuntime().log(`session ended: ${reason}`);
  clearAccessToken();
  sessionRuntime().clearQueryCache();
}

/**
 * Task 6: boot.ts and logout.ts move `status` on their own (a 403 to `unreachable`, a retry loop to
 * `signing-out`) without the rest of establishSession/endSession's side effects — e.g. logout's local
 * `signing-out` write happens right after endSession has already cleared `me`. This is the "small
 * named export" the Task 5 refactor asks for, so neither module reaches for `useSessionStore`
 * directly: it still goes through the sole writer, `transition`, and still passes `me` explicitly
 * (the current value, unchanged) rather than letting a bare `{ status }` merge risk a stale `me`.
 */
export function setSessionStatus(status: SessionStatus): void {
  transition(status, useSessionStore.getState().me);
}

/** Boot, login, signup, accept and every refresh go through here (spec §4.4). */
export function establishSession(response: AuthResponse): EstablishResult {
  const next = toMe(response);
  const { me } = useSessionStore.getState();
  if (me && (me.userId !== next.userId || me.tenantId !== next.tenantId)) {
    // The cookie jar is shared by every tab: another tab signed in as someone else, and this tab's
    // refresh picked up their cookie. Nothing cached for the previous principal may survive.
    endSession('principal-changed');
    sessionRuntime().reload();
    return 'principal-changed';
  }
  setAccessToken(response.accessToken);
  transition('authenticated', next);
  // P14: login, signup and accept revoke the stale cookie server-side, so any logout this device
  // still owed is now settled. Boot's own refresh does NOT reach here with a marker set: boot
  // finishes the pending logout first.
  clearLogoutPending();
  writeLastWorkspace(next.tenantSlug);
  sessionRuntime().channel.post({ type: 'login', userId: next.userId, tenantId: next.tenantId });
  return 'established';
}

/** EVERY way a session ends goes through here (spec §4.4). */
export function endSession(reason: EndReason): void {
  clearSession(reason);
  transition('anonymous', null);
}

export function subscribeToAuthChannel(): () => void {
  return sessionRuntime().channel.subscribe((message) => {
    const { me, status } = useSessionStore.getState();

    // P14/Security-1: an unconfirmed sign-out elsewhere. Clear local state, but block the UI
    // instead of showing /login — the cookie is still live in this tab too. Lands directly on
    // 'signing-out' (one write), not via endSession's 'anonymous' followed by a second write.
    if (message.type === 'signing-out') {
      if (!me && status !== 'authenticated') return;
      clearSession('remote-logout');
      transition('signing-out', null);
      return;
    }

    // Challenge #103: `logout` must end this tab's blocking screen even though `me` is already null
    // here — the `signing-out` branch above just cleared it. `logout` carries no principal to check,
    // so unlike `login` it needs nothing from `me`; gating it on `me` first meant a tab that had
    // already received `signing-out` (blocking screen up, `me` cleared) could never leave that screen
    // when the confirming `logout` broadcast arrived — the exact real-sign-out case P14/Security-1
    // exists for, not just the forged-message case. `status !== 'signing-out'` still makes it a no-op
    // for a tab that was never told to block (nothing to end).
    if (message.type === 'logout') {
      if (!me && status !== 'signing-out') return;
      endSession('remote-logout');
      return;
    }

    // Fix round 1, item 2 (Critical, security): this used to clear the durable marker and drop the
    // blocking screen on the mere WORD of a `login` broadcast. `easycrm-auth` is same-origin
    // `postMessage` — any script on the page can forge one — so treating it as proof the server
    // revoked this device's stale cookie let a forged message leave the marker gone, the UI on
    // /login, and the easycrm_rt cookie never revoked (the next boot would then silently restore
    // the previous user). A `login` message is no longer trusted here at all: while `me` is null
    // (this branch's `signing-out` case already cleared it), falling through to `if (!me) return;`
    // below makes a bare `login` broadcast a no-op for a signing-out tab. Settling a pending logout
    // now requires verifying the claim against the server under the refresh lock — see
    // `logout.ts`'s `onLoginBroadcast()`, wired from `start.ts`. Challenge #90.
    if (!me) return;
    if (me.userId !== message.userId || me.tenantId !== message.tenantId) {
      endSession('principal-changed');
      sessionRuntime().reload();
    }
  });
}
