import type { AuthResponse } from '@/api/types';
import { writeLastWorkspace } from '@/lib/storage';
import { useSessionStore } from '@/session/sessionStore';
import { clearAccessToken, setAccessToken } from './accessToken';
import { clearLogoutPending } from './logoutPending';
import { sessionRuntime } from './runtime';
import { toMe } from './toMe';

export type EndReason = 'logout' | 'expired' | 'principal-changed' | 'remote-logout';
export type EstablishResult = 'established' | 'principal-changed';

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
  useSessionStore.setState({ status: 'authenticated', me: next });
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
  sessionRuntime().log(`session ended: ${reason}`);
  clearAccessToken();
  useSessionStore.setState({ status: 'anonymous', me: null });
  sessionRuntime().clearQueryCache();
}

export function subscribeToAuthChannel(): () => void {
  return sessionRuntime().channel.subscribe((message) => {
    const { me, status } = useSessionStore.getState();

    // P14/Security-1: an unconfirmed sign-out elsewhere. Clear local state, but block the UI
    // instead of showing /login — the cookie is still live in this tab too.
    if (message.type === 'signing-out') {
      if (!me && status !== 'authenticated') return;
      endSession('remote-logout');
      useSessionStore.setState({ status: 'signing-out' });
      return;
    }

    // Architecture-2: any successful sign-in revokes the pending cookie server-side
    // (AuthController.login/signup and PublicInvitationController.accept call auth::logout),
    // so a tab stuck in signing-out is done — and must stop retrying before its next POST
    // logs the NEW user out.
    if (message.type === 'login' && status === 'signing-out') {
      clearLogoutPending();
      useSessionStore.setState({ status: 'anonymous' });
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
