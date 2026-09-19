import type { AuthResponse } from '@/api/types';
import { REFRESH_FORBIDDEN_MESSAGE, type RefreshCallResult } from './refreshCall';
import type { SessionStatus } from '@/session/types';

export const RETRY_SCHEDULE_MS = [2_000, 5_000, 15_000, 30_000] as const;
const LAST_RETRY_MS = 30_000;

/**
 * Plan decision P2. A 429 honours Retry-After: RateLimitFilter runs before any token is read, so a
 * 429'd refresh consumed nothing and retrying earlier only earns another 429. Everything else retries
 * first after 2 s so a refresh whose response was lost lands inside the 30 s grace window (spec §3.3).
 */
export function nextBootDelayMs(attempt: number, retryAfterSeconds?: number): number {
  if (retryAfterSeconds !== undefined) return Math.min(Math.max(retryAfterSeconds, 1), 60) * 1_000;
  return RETRY_SCHEDULE_MS[Math.min(attempt, RETRY_SCHEDULE_MS.length - 1)] ?? LAST_RETRY_MS;
}

export interface BootDeps {
  refresh(): Promise<RefreshCallResult>;
  establish(body: AuthResponse): unknown;
  getStatus(): SessionStatus;
  setStatus(status: SessionStatus): void;
  schedule(fn: () => void, ms: number): () => void;
  log(message: string): void;
  /** P14: true when this device owes the server a logout it never confirmed. */
  logoutPending(): boolean;
  /** P14: finish that logout before touching the cookie for a refresh. Resolves when settled. */
  finishPendingLogout(): Promise<void>;
}

export interface Boot {
  start(): Promise<void>;
  retryNow(): Promise<void>;
  stop(): void;
}

export function createBoot(deps: BootDeps): Boot {
  let attempt = 0;
  let cancelRetry: (() => void) | null = null;

  const stop = () => {
    cancelRetry?.();
    cancelRetry = null;
  };
  const pending = () => {
    const status = deps.getStatus();
    return status === 'booting' || status === 'unreachable';
  };

  async function run(): Promise<void> {
    stop();
    if (!pending()) return;

    // P14/Security-1: a logout this device started but never got a 204 for. Refreshing first would
    // re-establish the very session the user asked to end — the cookie is still live. Finish the
    // logout instead; createLogout owns the retry loop and the blocking status from there.
    if (deps.logoutPending()) {
      await deps.finishPendingLogout();
      return;
    }

    const result = await deps.refresh();
    if (!pending()) return; // plan decision P6
    switch (result.kind) {
      case 'ok':
        attempt = 0;
        try {
          deps.establish(result.body);
        } catch (error) {
          // Architecture-5: toMe throws on a role this build does not know (ROADMAP item 4a adds
          // one). Without this catch the rejection escapes `void boot.start()`, the status stays
          // `booting`, and the user watches the splash spin forever.
          deps.log(`session could not be established: ${String(error)}`);
          deps.setStatus('unreachable');
        }
        return;
      case 'unauthorized':
        deps.setStatus('anonymous');
        return;
      case 'forbidden':
        deps.log(REFRESH_FORBIDDEN_MESSAGE);
        deps.setStatus('unreachable');
        return;
      case 'unavailable':
        deps.setStatus('unreachable');
        cancelRetry = deps.schedule(() => void run(), nextBootDelayMs(attempt++, result.retryAfterSeconds));
        return;
    }
  }

  return { start: run, retryNow: run, stop };
}
