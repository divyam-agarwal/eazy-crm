import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { lazy, Suspense } from 'react';
import { RouterProvider, type createBrowserRouter } from 'react-router';

type AppRouter = ReturnType<typeof createBrowserRouter>;

// Testing-2: `import.meta.env.DEV` is TRUE under Vitest, so without the MODE check the devtools
// toggle button renders into every component test — including the one asserting the app renders
// nothing while booting.
const Devtools =
  import.meta.env.DEV && import.meta.env.MODE !== 'test'
    ? lazy(() => import('@tanstack/react-query-devtools').then((m) => ({ default: m.ReactQueryDevtools })))
    : () => null;

/**
 * R47(a): no `<Suspense fallback={null}>` wraps `<RouterProvider>` here. That would catch a suspend
 * from ANY route — including a future public route's own skeleton — at the very top and blank the
 * entire app instead. RootLayout (the router's root element) owns its own, narrower Suspense
 * boundary; see RootLayout.tsx.
 */
export function Providers({ router, queryClient }: { router: AppRouter; queryClient: QueryClient }) {
  return (
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
      <Suspense fallback={null}>
        <Devtools />
      </Suspense>
    </QueryClientProvider>
  );
}
