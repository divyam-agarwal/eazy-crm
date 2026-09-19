import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { PageHeading } from '@/components/PageHeading';
import { useDocumentTitle } from '@/lib/useDocumentTitle';

export function NotFoundPage() {
  const { t } = useTranslation();
  useDocumentTitle(t('notFound.title'));
  return (
    <main className="mx-auto grid w-full max-w-md gap-4 px-4 py-10">
      <PageHeading>{t('notFound.heading')}</PageHeading>
      <Link to="/" className="underline">
        {t('notFound.home')}
      </Link>
    </main>
  );
}
