import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { PageHeading } from '@/components/PageHeading';
import { Button } from '@/components/ui/button';
import { useDocumentTitle } from '@/lib/useDocumentTitle';

export function UnreachableScreen({ onRetry }: { onRetry: () => Promise<void> }) {
  const { t } = useTranslation();
  const [retrying, setRetrying] = useState(false);
  const [failures, setFailures] = useState(0);
  useDocumentTitle(t('unreachable.title'));

  // A11y-6: without this, pressing "Try again" and failing again leaves the screen byte-identical,
  // so the user keeps pressing. The status line reports each failed attempt.
  const retry = async () => {
    setRetrying(true);
    try {
      await onRetry();
    } finally {
      setRetrying(false);
      setFailures((n) => n + 1);
    }
  };

  return (
    <main className="mx-auto grid w-full max-w-md gap-4 px-4 py-10">
      <PageHeading>{t('unreachable.heading')}</PageHeading>
      <p>{t('unreachable.body')}</p>
      <Button onClick={() => void retry()} disabled={retrying}>
        {retrying ? t('actions.retrying') : t('actions.retry')}
      </Button>
      <p role="status">{failures > 0 && !retrying ? t('unreachable.stillFailing') : ''}</p>
    </main>
  );
}
