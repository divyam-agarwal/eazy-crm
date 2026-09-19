import { setAuthBridge } from '@/api/authBridge';
import { getAccessToken } from './accessToken';
import { createBoot, type Boot } from './boot';
import { createSessionAuthBridge } from './bridge';
import { REFRESH_LOCK } from './lockProvider';
import { clearLogoutPending, isLogoutPending, markLogoutPending } from './logoutPending';
import { callLogout, createLogout } from './logout';
import { callRefresh } from './refreshCall';
import { createRefreshCoordinator } from './refreshCoordinator';
import { configureSession, type SessionRuntime } from './runtime';
import { endSession, establishSession, setSessionStatus, subscribeToAuthChannel } from './session';
import { useSessionStore } from '@/session/sessionStore';

// R33(a): `SessionControls` must declare `withCookieLock` in the INTERFACE, not only supply it on
// the returned object literal — otherwise the object literal's excess-property check fails `tsc`.
export interface SessionControls {
  boot: Boot;
  logout(): Promise<void>;
  /** P15: the handle Tasks 10–12 use so no page has to know the lock's name. */
  withCookieLock<T>(fn: () => Promise<T>): Promise<T>;
  stop(): void;
}

let controls: SessionControls | null = null;

export function sessionControls(): SessionControls {
  if (!controls) throw new Error('startSession() has not run');
  return controls;
}

export function stopSession(): void {
  controls?.stop();
  controls = null;
}

const schedule = (fn: () => void, ms: number) => {
  const id = setTimeout(fn, ms);
  return () => clearTimeout(id);
};

export function startSession(runtime: SessionRuntime, options: { autoBoot?: boolean } = {}): SessionControls {
  stopSession();
  configureSession(runtime);

  const coordinator = createRefreshCoordinator({
    locks: runtime.locks,
    callRefresh: () => callRefresh(),
    getToken: getAccessToken,
    onRefreshed: establishSession,
    onUnauthorized: () => endSession('expired'),
    log: runtime.log,
  });
  setAuthBridge(createSessionAuthBridge(coordinator));
  const unsubscribe = subscribeToAuthChannel();

  // Task 5's transition() discipline: boot and logout move `status` through this named export
  // rather than reaching for useSessionStore.setState directly.
  const setStatus = setSessionStatus;

  // P15: ONE place defines "this call writes the refresh cookie, so it serializes with refresh".
  // Login, signup, accept and logout all go through it — see useLogin/useSignup/useAcceptInvitation.
  const withCookieLock = <T,>(fn: () => Promise<T>) => runtime.locks.withLock(REFRESH_LOCK, fn);

  const logout = createLogout({
    callLogout: () => withCookieLock(() => callLogout()),
    endSession,
    broadcastLogout: (message) => runtime.channel.post(message),
    markPending: markLogoutPending,
    clearPending: clearLogoutPending,
    // Fix round 1, item 2: gates and feeds the verified settlement path — see logout.ts's
    // onLoginBroadcast(). isPending/currentPrincipal are cheap local reads; verifyPrincipal is the
    // one that actually asks the server, under the same lock as every other cookie-writing call.
    isPending: isLogoutPending,
    currentPrincipal: () => {
      const { me } = useSessionStore.getState();
      return me ? { userId: me.userId, tenantId: me.tenantId } : null;
    },
    verifyPrincipal: () => withCookieLock(() => callRefresh()),
    setStatus,
    onOnline: (fn) => {
      window.addEventListener('online', fn);
      return () => window.removeEventListener('online', fn);
    },
    schedule,
    log: runtime.log,
  });

  const boot = createBoot({
    // Boot takes the same lock as every other refresh: two tabs opening at once is the commonest race.
    refresh: () => withCookieLock(() => callRefresh()),
    establish: establishSession,
    getStatus: () => useSessionStore.getState().status,
    setStatus,
    schedule,
    log: runtime.log,
    // P14: a logout this device never got a 204 for outranks a refresh — refreshing first would
    // re-establish exactly the session the user asked to end.
    logoutPending: isLogoutPending,
    finishPendingLogout: () => logout.logout(),
  });

  // Fix round 1, item 2: a `login` anywhere COULD mean the server revoked our pending cookie
  // (AuthController.login/signup → auth::logout) — but the broadcast itself is same-origin
  // postMessage, not proof. onLoginBroadcast() verifies the claim against the server (under the
  // refresh lock) before deciding whether to stop retrying and clear the durable marker.
  const unsubscribeSettled = runtime.channel.subscribe((message) => {
    if (message.type === 'login') void logout.onLoginBroadcast();
  });

  // Performance-6: retry boot the moment the phone is back, instead of waiting out a 30 s step.
  const onOnline = () => {
    if (useSessionStore.getState().status === 'unreachable') void boot.retryNow();
  };
  window.addEventListener('online', onOnline);

  controls = {
    boot,
    logout: () => logout.logout(),
    withCookieLock,
    stop: () => {
      boot.stop();
      logout.stop();
      unsubscribe();
      unsubscribeSettled();
      window.removeEventListener('online', onOnline);
    },
  };
  if (options.autoBoot ?? true) void boot.start();
  return controls;
}
