/**
 * How the HTTP layer reaches the session without importing a feature (spec §4.3: api is the lowest
 * layer). features/auth/session installs the real bridge at startup (Task 5).
 */
export type RefreshOutcome = 'refreshed' | 'ended' | 'unavailable';

export interface AuthBridge {
  getAccessToken(): string | null;
  /** Refresh unless the token already changed since `tokenAtFailure` was sent. */
  refresh(tokenAtFailure: string | null): Promise<RefreshOutcome>;
  /** The retried request was also refused: end the session and tell the router. */
  sessionExpired(): void;
}

// Fail-safe (every request looks anonymous, every 401 ends the session) is the correct default
// behaviour for the inert bridge — but fail-SILENT is not. If setAuthBridge() is never called
// (forgotten import, bootstrap ordering, a broken re-export — exactly the kind of mistake Task 5's
// startup wiring can make), every request silently looks anonymous and every 401 is silently
// swallowed as 'ended', which reads to a user as an infinite redirect to /login with nothing in the
// console distinguishing "logged out" from "auth wiring broken". A one-time console.error makes
// that distinguishable without changing the runtime behaviour at all.
let warned = false;

function warnInert(method: keyof AuthBridge): void {
  if (warned) return;
  warned = true;
  console.error(
    `[authBridge] AuthBridge.${method}() was called on the inert default bridge — setAuthBridge() ` +
      'was never invoked. Every request will look anonymous and every 401 will silently end the ' +
      'session. This usually means the real bridge failed to install at startup (forgotten import, ' +
      'bootstrap ordering, or a broken re-export), not that the user is actually logged out.',
  );
}

const inert: AuthBridge = {
  getAccessToken: () => {
    warnInert('getAccessToken');
    return null;
  },
  refresh: async () => {
    warnInert('refresh');
    return 'ended';
  },
  sessionExpired: () => {
    warnInert('sessionExpired');
  },
};

let current: AuthBridge = inert;

export function setAuthBridge(bridge: AuthBridge): void {
  current = bridge;
}

export function getAuthBridge(): AuthBridge {
  return current;
}

/**
 * Test-only: restores the module-scoped singleton to the inert default and re-arms the one-time
 * warning. Without this, `setAuthBridge()` calls in one test file (e.g. Task 5's startup wiring)
 * leak into the next test file sharing the same Vitest worker — green until run order changes.
 */
export function resetAuthBridge(): void {
  current = inert;
  warned = false;
}
