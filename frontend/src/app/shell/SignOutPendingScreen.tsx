import { useTranslation } from 'react-i18next';
import { PageHeading } from '@/components/PageHeading';
import { useDocumentTitle } from '@/lib/useDocumentTitle';

export function SignOutPendingScreen() {
  const { t } = useTranslation();
  useDocumentTitle(t('signOutPending.title'));
  // A11y-6: no aria-busy on the landmark — some screen readers hold back or skip content marked
  // busy, and this screen's whole job is to explain why the app is blocked.
  return (
    <main className="mx-auto grid w-full max-w-md gap-4 px-4 py-10">
      <PageHeading>{t('signOutPending.heading')}</PageHeading>
      <p role="status">{t('signOutPending.body')}</p>
    </main>
  );
}
