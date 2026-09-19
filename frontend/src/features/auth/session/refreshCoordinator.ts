import type { RefreshOutcome } from '@/api/authBridge';
import type { AuthResponse } from '@/api/types';
import { REFRESH_LOCK, type LockProvider } from './lockProvider';
import { REFRESH_FORBIDDEN_MESSAGE, type RefreshCallResult } from './refreshCall';
import type { EstablishResult } from './session';

export interface RefreshCoordinator {
  refresh(tokenAtFailure: string | null): Promise<RefreshOutcome>;
}

export interface RefreshCoordinatorDeps {
  locks: LockProvider;
  callRefresh(): Promise<RefreshCallResult>;
  getToken(): string | null;
  onRefreshed(body: AuthResponse): EstablishResult;
  onUnauthorized(): void;
  log(message: string): void;
}

/**
 * Spec §4.4. Web Locks serialization is load-bearing: two concurrent refreshes of one cookie both
 * succeed (the second through the grace window) and the later one revokes the earlier successor, so
 * whichever Set-Cookie lands last-but-not-latest leaves every tab holding a dead cookie.
 */
export function createRefreshCoordinator(deps: RefreshCoordinatorDeps): RefreshCoordinator {
  let inFlight: Promise<RefreshOutcome> | null = null;

  async function underLock(tokenAtFailure: string | null): Promise<RefreshOutcome> {
    const current = deps.getToken();
    if (current !== null && current !== tokenAtFailure) return 'refreshed';
    const result = await deps.callRefresh();
    switch (result.kind) {
      case 'ok':
        // ROADMAP 4a: onRefreshed (establishSession) calls toMe(), which THROWS on a role this
        // bundle doesn't recognize (see toMe.ts) — e.g. an older tab refreshing after a new role
        // ships server-side. AuthBridge.refresh is typed as never rejecting, and authFetch.ts
        // awaits it unguarded, so an uncaught throw here would break that contract from this side
        // of the seam and leave the caller in no terminal state at all. Map it to the same outcome
        // an explicit 401 already produces.
        try {
          return deps.onRefreshed(result.body) === 'established' ? 'refreshed' : 'ended';
        } catch (error) {
          deps.log(
            `onRefreshed threw for an otherwise-successful refresh: ${error instanceof Error ? error.message : String(error)}`,
          );
          deps.onUnauthorized();
          return 'ended';
        }
      case 'unauthorized':
        deps.onUnauthorized();
        return 'ended';
      case 'forbidden':
        deps.log(REFRESH_FORBIDDEN_MESSAGE);
        return 'unavailable';
      case 'unavailable':
        return 'unavailable';
    }
  }

  return {
    refresh(tokenAtFailure) {
      inFlight ??= deps.locks
        .withLock(REFRESH_LOCK, () => underLock(tokenAtFailure))
        .finally(() => {
          inFlight = null;
        });
      return inFlight;
    },
  };
}
