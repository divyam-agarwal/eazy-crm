import { BASIC_SKELETON_CLASS } from './basicSkeletonClass';

/**
 * R47(a): the sized fallback a public route (login/signup/invite — Tasks 10-12) shows while its OWN
 * local `<Suspense>` is pending (its lazy route chunk has already resolved by the time it renders —
 * react-router awaits `lazy()` before rendering — so what this actually covers is an i18n namespace
 * still loading). Never used as the top-level router fallback: see RootLayout.tsx and providers.tsx
 * for why that would blank the whole app instead of showing this.
 *
 * Usage (Tasks 10-12), nested INSIDE the route's own element so this catches before RootLayout's
 * outer `fallback={null}` Suspense does:
 * ```tsx
 * { path: '/login', lazy: () => withImportRetry(() => import('./LoginPage')).then((m) => ({
 *     Component: () => <Suspense fallback={<RouteSkeleton />}><m.LoginPage /></Suspense>,
 *   })), errorElement: <RouteErrorBoundary /> }
 * ```
 */
export function RouteSkeleton() {
  return (
    <main className="mx-auto grid w-full max-w-md gap-4 px-4 py-10" aria-hidden="true">
      <div className={`${BASIC_SKELETON_CLASS} h-8 w-2/3`} />
      <div className={`${BASIC_SKELETON_CLASS} h-4 w-full`} />
      <div className={`${BASIC_SKELETON_CLASS} h-10 w-full`} />
      <div className={`${BASIC_SKELETON_CLASS} h-10 w-full`} />
      <div className={`${BASIC_SKELETON_CLASS} h-9 w-32`} />
    </main>
  );
}
