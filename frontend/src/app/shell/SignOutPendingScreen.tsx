import { useTranslation } from 'react-i18next';
import { PageHeading } from '@/components/PageHeading';
import { useDocumentTitle } from '@/lib/useDocumentTitle';

export function SignOutPendingScreen() {
  const { t } = useTranslation();
  useDocumentTitle(t('signOutPending.title'));
  // A11y-6: no aria-busy on the landmark — some screen readers hold back or skip content marked
  // busy, and this screen's whole job is to explain why the app is blocked.
  //
  // Task 12 review, fix round 1, item 3: this paragraph no longer carries `role="status"` itself.
  // RootLayout now owns one permanently-mounted `role="status"` region above the Outlet/this-screen
  // swap (RootLayout never unmounts across navigations, so that region pre-exists every mount of
  // THIS screen) — a `role="status"` here would be born together with its own text on every mount,
  // the exact "fresh region + content at once" pattern AT does not reliably announce. This
  // paragraph stays as plain, always-visible text for sighted users.
  return (
    <main className="mx-auto grid w-full max-w-md gap-4 px-4 py-10">
      <PageHeading>{t('signOutPending.heading')}</PageHeading>
      <p>{t('signOutPending.body')}</p>
    </main>
  );
}
