import { Suspense } from 'react';
import { createBrowserRouter, type RouteObject } from 'react-router';
import { withImportRetry } from '@/lib/lazyImport';
import { RequireSession } from './RequireSession';
import { RootLayout } from './RootLayout';
import { RouteErrorBoundary } from './RouteErrorBoundary';
import { RouteSkeleton } from './RouteSkeleton';

export const appRoutes: RouteObject[] = [
  {
    element: <RootLayout />,
    errorElement: <RouteErrorBoundary />,
    children: [
      // Public routes (Tasks 10–12) go HERE, before RequireSession. They render without waiting on
      // boot. R47(a): each one should wrap its own lazy Component in a LOCAL `<Suspense
      // fallback={<RouteSkeleton />}>` (see RouteSkeleton.tsx) — nested inside its own element, not
      // added here — so an i18n-namespace suspend shows that route's skeleton instead of bubbling to
      // RootLayout's `fallback={null}` and blanking the app. R47(b): wrap the dynamic `import()` in
      // `withImportRetry` (below) so a dropped connection retries the route chunk before giving up.
      {
        path: 'login',
        lazy: () =>
          withImportRetry(() => import('@/features/auth/pages/LoginPage')).then((m) => ({
            Component: () => (
              <Suspense fallback={<RouteSkeleton />}>
                <m.LoginPage />
              </Suspense>
            ),
          })),
        errorElement: <RouteErrorBoundary />,
      },
      {
        path: 'signup',
        lazy: () =>
          withImportRetry(() => import('@/features/auth/pages/SignupPage')).then((m) => ({
            Component: () => (
              <Suspense fallback={<RouteSkeleton />}>
                <m.SignupPage />
              </Suspense>
            ),
          })),
        errorElement: <RouteErrorBoundary />,
      },
      {
        // R71: the same local-Suspense pattern as /login and /signup — this route's own boundary
        // catches an i18n-namespace suspend instead of bubbling to RootLayout's `fallback={null}`.
        path: 'invite/:token',
        lazy: () =>
          withImportRetry(() => import('@/features/auth/pages/InvitePage')).then((m) => ({
            Component: () => (
              <Suspense fallback={<RouteSkeleton />}>
                <m.InvitePage />
              </Suspense>
            ),
          })),
        errorElement: <RouteErrorBoundary />,
      },
      {
        element: <RequireSession />,
        children: [
          {
            lazy: () => withImportRetry(() => import('./shell/AppShell')).then((m) => ({ Component: m.AppShell })),
            errorElement: <RouteErrorBoundary />,
            children: [
              {
                index: true,
                lazy: () =>
                  withImportRetry(() => import('./shell/HomePage')).then((m) => ({ Component: m.HomePage })),
                errorElement: <RouteErrorBoundary />,
              },
            ],
          },
        ],
      },
      {
        path: '*',
        lazy: () => withImportRetry(() => import('./NotFoundPage')).then((m) => ({ Component: m.NotFoundPage })),
        errorElement: <RouteErrorBoundary />,
      },
    ],
  },
];

export function createAppRouter() {
  return createBrowserRouter(appRoutes);
}
