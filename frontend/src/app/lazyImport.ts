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
 */
export function withImportRetry<T>(
  load: () => Promise<T>,
  options: { attempts?: number; delayMs?: number } = {},
): Promise<T> {
  const attempts = options.attempts ?? 3;
  const delayMs = options.delayMs ?? 500;

  const attempt = (n: number): Promise<T> =>
    load().catch((error: unknown) => {
      if (n >= attempts) throw error;
      return new Promise<T>((resolve, reject) => {
        setTimeout(() => {
          attempt(n + 1).then(resolve, reject);
        }, delayMs);
      });
    });

  return attempt(1);
}
