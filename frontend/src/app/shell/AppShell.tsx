import { useTranslation } from 'react-i18next';
import { Outlet } from 'react-router';
import { Button } from '@/components/ui/button';
import { sessionControls } from '@/features/auth/session/start';
import { useMe } from '@/session/useMe';

// R31: reads identity through `useMe()`, not `useSessionStore` directly — the read-side hook P16
// built otherwise ships with a unit test and zero consumers.
export function AppShell() {
  const { t } = useTranslation();
  const me = useMe();
  if (!me) return null;
  return (
    <div className="min-h-dvh">
      <header className="flex flex-wrap items-center justify-between gap-2 border-b px-4 py-3">
        <p className="font-semibold">{me.tenantSlug}</p>
        <div className="flex flex-wrap items-center gap-3 text-sm">
          <span>{me.email}</span>
          <span>{t(`roles.${me.role}`)}</span>
          <Button variant="outline" size="sm" onClick={() => void sessionControls().logout()}>
            {t('actions.signOut')}
          </Button>
        </div>
      </header>
      <div className="flex">
        <nav aria-label={t('shell.nav')} className="hidden w-56 border-r md:block" />
        <main className="flex-1 px-4 py-6">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
