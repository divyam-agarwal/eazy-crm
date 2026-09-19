export const REFRESH_LOCK = 'easycrm-refresh';

/** Exclusive, held until fn's promise settles — the semantics of navigator.locks.request. */
export interface LockProvider {
  withLock<T>(name: string, fn: () => Promise<T>): Promise<T>;
}

export const webLocks: LockProvider = {
  withLock(name, fn) {
    // WebLocks flattens a thenable the callback returns (WHATWG spec; plain JS Promise semantics
    // do too). lib.dom's LockGrantedCallback<T> is `(lock) => T` and does not express that
    // flattening, so left alone, TS infers T = Promise<T> (fn's own return type) and reports
    // Promise<Promise<T>> against LockProvider's declared Promise<T>. The cast reflects what
    // actually resolves at runtime.
    return navigator.locks.request(name, { mode: 'exclusive' }, fn) as ReturnType<typeof fn>;
  },
};

export function supportsWebLocks(): boolean {
  return typeof navigator !== 'undefined' && 'locks' in navigator;
}

/** Same semantics within one JS realm. Tests, and the per-tab fallback when Web Locks are missing. */
export function createInMemoryLocks(): LockProvider {
  const tails = new Map<string, Promise<unknown>>();
  return {
    withLock<T>(name: string, fn: () => Promise<T>): Promise<T> {
      const previous = tails.get(name) ?? Promise.resolve();
      const run = previous.then(fn, fn);
      tails.set(
        name,
        run.catch(() => undefined),
      );
      return run;
    },
  };
}

/** Deliberately broken: used ONLY for Task 15's recorded red run. Never bind it in production. */
export const noopLocks: LockProvider = { withLock: (_name, fn) => fn() };
