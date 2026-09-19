import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Trans, useTranslation } from 'react-i18next';
import { Link, Navigate, useLocation, useSearchParams } from 'react-router';
import * as z from 'zod/mini';
import { toApiFailure } from '@/api/errors';
import { FormAlert } from '@/components/form/FormAlert';
import { PasswordField } from '@/components/form/PasswordField';
import { TextField } from '@/components/form/TextField';
import { PageHeading } from '@/components/PageHeading';
import { Button } from '@/components/ui/button';
import { applyApiError } from '@/lib/apiError';
import { forceCase } from '@/lib/caseInput';
import { useFieldError, useTranslator } from '@/lib/i18n/translator';
import { safeNext } from '@/lib/safeNext';
import { readLastWorkspace } from '@/lib/storage';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { useSessionStatus } from '@/session/useMe';
import { useLogin } from '../api/useLogin';
import { establishSession } from '../session/session';
import { readEndReasonState, readWorkspaceState } from '../workspaceState';

const loginSchema = z.object({
  slug: z.string().check(z.trim(), z.minLength(1, { error: 'validation.required' })),
  email: z.string().check(z.trim(), z.minLength(1, { error: 'validation.required' })),
  password: z.string().check(z.minLength(1, { error: 'validation.required' })),
});
type LoginValues = z.infer<typeof loginSchema>;
const LOGIN_FIELDS = ['slug', 'email', 'password'] as const;

export function LoginPage() {
  const { t } = useTranslation('auth');
  const { t: tc } = useTranslation('common');
  const translator = useTranslator();
  const fieldError = useFieldError();
  const status = useSessionStatus();
  const location = useLocation();
  const [params] = useSearchParams();
  const login = useLogin();
  // A11y-5: a session that expired mid-task, or was ended from another tab, otherwise drops the
  // user on an empty login page with no idea what happened. RootLayout passes the reason in state.
  const endedReason = readEndReasonState(location.state);
  const [formMessage, setFormMessage] = useState<string | null>(
    endedReason === 'expired' ? t('login.sessionEnded') : null,
  );
  useDocumentTitle(t('login.title'));

  const form = useForm<LoginValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { slug: readWorkspaceState(location.state) ?? readLastWorkspace() ?? '', email: '', password: '' },
  });

  // Also the post-login navigation: establishSession flips status, and this sends the user to `next`.
  if (status === 'authenticated') return <Navigate to={safeNext(params.get('next')) ?? '/'} replace />;

  const onSubmit = form.handleSubmit(async (values) => {
    setFormMessage(null);
    try {
      establishSession(await login.mutateAsync(values));
    } catch (error) {
      const failure = toApiFailure(error);
      // Never routed through the refresh/session-expiry path: /api/v1/auth/login is refresh-exempt
      // (src/api/authFetch.ts), so a 401 here always means wrong credentials, not an expired token.
      if (failure.kind === 'http' && failure.status === 401) {
        setFormMessage(t('login.invalidCredentials'));
        return;
      }
      setFormMessage(applyApiError(failure, { setError: form.setError, fields: LOGIN_FIELDS }, translator).formMessage);
    }
  });

  const slugField = form.register('slug');
  const { errors, isSubmitting, submitCount } = form.formState;

  return (
    <main className="mx-auto grid w-full max-w-sm gap-6 px-4 py-10">
      <PageHeading>{t('login.heading')}</PageHeading>
      <form noValidate onSubmit={onSubmit} className="grid gap-4">
        {/* R65: `attempt` must be a real counter (RHF's submitCount), never a constant -- otherwise
            the alert stays silent on a second, identical failure (the common case on flaky 4G). */}
        <FormAlert message={formMessage} attempt={submitCount} />
        <TextField
          id="login-slug"
          label={t('login.workspace')}
          description={t('login.workspaceHint')}
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
          error={fieldError(errors.slug)}
          {...slugField}
          onChange={(event) => {
            forceCase(event, 'lower'); // A11y-8: keeps the caret in place
            void slugField.onChange(event);
          }}
        />
        <TextField
          id="login-email"
          type="email"
          autoComplete="username"
          label={t('login.email')}
          error={fieldError(errors.email)}
          {...form.register('email')}
        />
        <PasswordField
          id="login-password"
          autoComplete="current-password"
          label={t('login.password')}
          showLabel={tc('password.show')}
          error={fieldError(errors.password)}
          {...form.register('password')}
        />
        <Button type="submit" disabled={isSubmitting}>
          {isSubmitting ? t('login.submitting') : t('login.submit')}
        </Button>
      </form>
      <p className="text-sm">
        <Trans t={t} i18nKey="login.noAccount" components={{ link: <Link to="/signup" className="underline" /> }} />
      </p>
    </main>
  );
}
