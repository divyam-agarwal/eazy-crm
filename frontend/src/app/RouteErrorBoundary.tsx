import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { useRouteError } from 'react-router';
import { PageHeading } from '@/components/PageHeading';
import { Button } from '@/components/ui/button';
import { useDocumentTitle } from '@/lib/useDocumentTitle';

/**
 * R47(b): this `errorElement` is the boundary every route (and every route's local Suspense) sits
 * inside — see router.tsx. A rejected `lazy()` import surfaces here directly (react-router routes a
 * failed lazy load to the nearest `errorElement`); a rejected i18n-namespace-chunk import surfaces
 * here too, one level up, because React re-throws an eventually-rejected suspended promise as a
 * normal render error once `withImportRetry`'s retries are exhausted — and Suspense itself only
 * catches a *pending* promise, never a rejected one.
 */
export function RouteErrorBoundary() {
  const error = useRouteError();
  const { t } = useTranslation();
  useDocumentTitle(t('routeError.title'));
  useEffect(() => {
    console.error(error);
  }, [error]);
  return (
    <main className="mx-auto grid w-full max-w-md gap-4 px-4 py-10">
      <PageHeading>{t('routeError.heading')}</PageHeading>
      <p>{t('routeError.body')}</p>
      <Button onClick={() => window.location.reload()}>{t('actions.reload')}</Button>
    </main>
  );
}
