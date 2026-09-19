import { createInMemoryLocks, REFRESH_LOCK, type LockProvider } from '@/features/auth/session/lockProvider';

/**
 * R14: proves P15 (every call that writes the refresh cookie holds the `easycrm-refresh` Web Lock)
 * at the unit level instead of chasing the E2E spec the plan never actually wrote (rulings.md R14).
 *
 * Returns a fresh in-memory `LockProvider` with `easycrm-refresh` already held. Inject `locks` into
 * whatever is under test (a hook's mutation, `logout()`, `boot.start()` — anything wired through
 * `runtime.locks`/`withCookieLock`); assert nothing the lock guards has happened yet, `await
 * acquired` if you need to know the hold has actually started, then `release()` and assert it now
 * has. Tasks 10–12 each build one hook test on this same helper.
 */
export function holdCookieLock(): { locks: LockProvider; release: () => void; acquired: Promise<void> } {
  const locks = createInMemoryLocks();
  let release!: () => void;
  let markAcquired!: () => void;
  const acquired = new Promise<void>((resolve) => {
    markAcquired = resolve;
  });
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  void locks.withLock(REFRESH_LOCK, async () => {
    markAcquired();
    await gate;
  });
  return { locks, release, acquired };
}
