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

const inert: AuthBridge = {
  getAccessToken: () => null,
  refresh: async () => 'ended',
  sessionExpired: () => {},
};

let current: AuthBridge = inert;

export function setAuthBridge(bridge: AuthBridge): void {
  current = bridge;
}

export function getAuthBridge(): AuthBridge {
  return current;
}
