import { useTranslation } from 'react-i18next';
import { PageHeading } from '@/components/PageHeading';
import { useMe } from '@/session/useMe';
import { useDocumentTitle } from '@/lib/useDocumentTitle';

/** Placeholder; F3 replaces it with the role-aware dashboard. */
export function HomePage() {
  const { t } = useTranslation();
  const me = useMe();
  useDocumentTitle(t('shell.homeTitle'));
  if (!me) return null;
  return (
    <div className="grid gap-2">
      <PageHeading>{t('shell.homeHeading')}</PageHeading>
      <p>{t('shell.signedInAs', { slug: me.tenantSlug, email: me.email, role: t(`roles.${me.role}`) })}</p>
    </div>
  );
}
