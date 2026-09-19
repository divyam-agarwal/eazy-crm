/**
 * R47(b): a route's `lazy: () => import('./Page')` and i18next-resources-to-backend's
 * `resourcesToBackend((lng, ns) => import(...))` both call a bare dynamic `import()`. On a dropped
 * 4G connection that `import()` rejects — and neither react-router nor react-i18next retries it.
 * Worse, a REJECTION is not something `<Suspense>` catches (Suspense only catches a *pending*
 * promise); it surfaces as a thrown render error, caught only by an error boundary above the
 * Suspense boundary (see `router.tsx`'s `errorElement`s). Without a retry here, one blip turns a
 * route or a namespace into a dead end with no retry affordance — the opposite of boot's own retry
 * philosophy (spec §4.4). This wraps any `import()`-returning thunk with a few retries before
 * letting the failure through for real.
 *
 * <p>Fix round 1, item 3: the original schedule was three FLAT 500 ms waits — giving up after
 * ~1 s total. The failures this exists for (a tunnel, a lift, a tower handoff) routinely last
 * several seconds, and spec §4.4 already sets this app's bar for its own boot retry at "the first
 * automatic retry happens within 5 s… later retries may back off further". A schedule that gives up
 * in ~1 s is not a self-healing policy for that failure mode, it's a slightly-delayed manual Reload.
 * `IMPORT_RETRY_SCHEDULE_MS` backs off toward that same ~5 s benchmark instead.
 */
export const IMPORT_RETRY_SCHEDULE_MS = [500, 1_500, 3_000] as const;

export function withImportRetry<T>(
  load: () => Promise<T>,
  options: { schedule?: readonly number[] } = {},
): Promise<T> {
  const schedule = options.schedule ?? IMPORT_RETRY_SCHEDULE_MS;

  const attempt = (n: number): Promise<T> =>
    load().catch((error: unknown) => {
      const delayMs = schedule[n];
      if (delayMs === undefined) throw error; // schedule exhausted — let the real rejection through
      return new Promise<T>((resolve, reject) => {
        setTimeout(() => {
          attempt(n + 1).then(resolve, reject);
        }, delayMs);
      });
    });

  return attempt(0);
}
