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

    // Architecture-2: any successful sign-in revokes the pending cookie server-side
    // (AuthController.login/signup and PublicInvitationController.accept call auth::logout),
    // so a tab stuck in signing-out is done — and must stop retrying before its next POST
    // logs the NEW user out.
    if (message.type === 'login' && status === 'signing-out') {
      clearLogoutPending();
      transition('anonymous', null);
      return;
    }

    if (!me) return;
    if (message.type === 'logout') {
      endSession('remote-logout');
      return;
    }
    if (me.userId !== message.userId || me.tenantId !== message.tenantId) {
      endSession('principal-changed');
      sessionRuntime().reload();
    }
  });
}
