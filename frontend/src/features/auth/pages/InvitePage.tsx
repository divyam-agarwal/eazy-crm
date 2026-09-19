import { zodResolver } from '@hookform/resolvers/zod';
import { useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Trans, useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router';
import * as z from 'zod/mini';
import { ApiHttpError, toApiFailure } from '@/api/errors';
import { FormAlert } from '@/components/form/FormAlert';
import { PasswordField } from '@/components/form/PasswordField';
import { TextField } from '@/components/form/TextField';
import { PageHeading } from '@/components/PageHeading';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { applyApiError } from '@/lib/apiError';
import { useFieldError, useTranslator } from '@/lib/i18n/translator';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { useMe, useSessionStatus } from '@/session/useMe';
import { useAcceptInvitation } from '../api/useAcceptInvitation';
import { useInvitationPreview } from '../api/useInvitationPreview';
import { useRoleLabel } from '../roleLabel';
import { establishSession } from '../session/session';
import { sessionControls } from '../session/start';

const acceptSchema = z.object({
  password: z.string().check(z.minLength(8, { error: 'validation.passwordMin' })),
  phone: z.string().check(z.trim()),
});
type AcceptValues = z.infer<typeof acceptSchema>;
const ACCEPT_FIELDS = ['password', 'phone'] as const;
const CARD = 'mx-auto grid w-full max-w-md gap-6 px-4 py-10';

export function InvitePage() {
  const { token = '' } = useParams();
  const { t } = useTranslation('auth');
  const { t: tc } = useTranslation('common');
  const translator = useTranslator();
  const fieldError = useFieldError();
  const roleLabel = useRoleLabel();
  const navigate = useNavigate();
  // R31/AR-1: identity is read through @/session/useMe, never useSessionStore directly — the auth
  // feature's own sessionStore import zone (import-x/no-restricted-paths) excludes everything under
  // features/auth EXCEPT features/auth/session/**, and this page lives in features/auth/pages/.
  const status = useSessionStatus();
  const me = useMe();
  const preview = useInvitationPreview(token); // in parallel with boot (spec §5.1)
  const accept = useAcceptInvitation(token);
  const acceptLost = useRef(false);
  const [formMessage, setFormMessage] = useState<string | null>(null);
  const [maybeAccepted, setMaybeAccepted] = useState(false);
  const [accepted, setAccepted] = useState(false);
  useDocumentTitle(t('invite.title'));

  const form = useForm<AcceptValues>({ resolver: zodResolver(acceptSchema), defaultValues: { password: '', phone: '' } });

  if (accepted) return null; // navigating home; never flash the signed-in card for our own new session

  if (preview.isPending || status === 'booting') {
    return (
      <main className={CARD} key="loading" aria-busy="true">
        <p className="sr-only">{t('invite.loading')}</p>
        <Skeleton aria-hidden className="h-8 w-2/3" />
        <Skeleton aria-hidden className="h-6 w-full" />
        <Skeleton aria-hidden className="h-64 w-full" />
      </main>
    );
  }

  if (preview.isError) {
    const invalid = preview.error instanceof ApiHttpError && preview.error.status === 404;
    // A11y-7: not every failure is the network. Telling someone on good Wi-Fi to check their
    // connection because the server rate-limited them is simply wrong; reuse the same 429/5xx/
    // network branches applyApiError already has. No new keys needed.
    const message = applyApiError(toApiFailure(preview.error), { setError: () => {}, fields: [] }, translator).formMessage;
    return (
      // A11y-4: `key` per branch. React keeps one <main>/<PageHeading> instance across these
      // branches, so the heading's focus-on-mount effect never re-runs while the focused "Try
      // again" button is removed — focus falls to <body>.
      <main className={CARD} key={invalid ? 'invalid' : 'error'}>
        <PageHeading>{t('invite.heading')}</PageHeading>
        {invalid ? (
          <p>{t('invite.invalid')}</p>
        ) : (
          <>
            {/* R65-equivalent: a real, monotonic counter, not a constant — errorUpdateCount ticks on
                every settled error, including this same query erroring again after "Try again". */}
            <FormAlert message={message} attempt={preview.errorUpdateCount} />
            <Button onClick={() => void preview.refetch()}>{tc('actions.retry')}</Button>
          </>
        )}
      </main>
    );
  }

  const invitation = preview.data;

  // R23 (controller ruling): RootLayout is exempted from its signing-out gate for this route (see
  // RootLayout.tsx), so this page — not RootLayout — owns what renders while a "Sign out and
  // accept" logout is in flight. Without this branch the page would fall straight through to the
  // anonymous accept form the instant `me` is cleared, before the server has actually confirmed the
  // sign-out (spec §4.4: the anonymous UI belongs only after that confirmation).
  if (status === 'signing-out') {
    return (
      <main className={CARD} key="signing-out">
        <PageHeading>{t('invite.heading')}</PageHeading>
        <p role="status">{tc('signOutPending.body')}</p>
      </main>
    );
  }

  if (status === 'authenticated' && me) {
    // F0-10: no redirect (it would lose the link), no silent accept (it would replace this session
    // in every tab).
    return (
      <main className={CARD} key="signed-in">
        <PageHeading>{t('invite.heading')}</PageHeading>
        <p>
          <Trans
            t={t}
            i18nKey="invite.signedInAs"
            values={{ inviteEmail: invitation.email, businessName: invitation.businessName, email: me.email }}
            components={{ strong: <strong /> }}
          />
        </p>
        <div className="flex flex-wrap gap-3">
          {/* Same shape as AppShell's sign-out button: sessionControls().logout() holds the cookie
              lock and moves `status` through 'signing-out' synchronously (before its first await),
              so this click and the branch above hand off in the same render — no local pending flag
              needed, and nothing here can double-fire a second logout. */}
          <Button onClick={() => void sessionControls().logout()}>{t('invite.signOutAndAccept')}</Button>
          <Button variant="outline" asChild>
            <Link to="/">{t('invite.goToWorkspace')}</Link>
          </Button>
        </div>
      </main>
    );
  }

  const onSubmit = form.handleSubmit(async (values) => {
    setFormMessage(null);
    setMaybeAccepted(false);
    try {
      const session = await accept.mutateAsync({ password: values.password, phone: values.phone || undefined });
      setAccepted(true);
      establishSession(session);
      void navigate('/', { replace: true }); // Back must not return to the token URL
    } catch (error) {
      const failure = toApiFailure(error);
      if (failure.kind === 'network') {
        // A lost response: the accept may have gone through on the server even though this tab saw
        // nothing. Remembered so a REPEAT 404 (the token was single-use and is now consumed) reads
        // as "you probably already did this", not as an invalid link.
        acceptLost.current = true;
      } else if (failure.kind === 'http' && failure.status === 404) {
        if (acceptLost.current) {
          setMaybeAccepted(true);
          return;
        }
        setFormMessage(t('invite.invalid'));
        return;
      }
      setFormMessage(applyApiError(failure, { setError: form.setError, fields: ACCEPT_FIELDS }, translator).formMessage);
    }
  });

  const { errors, isSubmitting, submitCount } = form.formState;

  return (
    <main className={CARD} key="accept">
      <PageHeading>{t('invite.heading')}</PageHeading>
      <p>
        <Trans
          t={t}
          i18nKey="invite.join"
          values={{ businessName: invitation.businessName, role: roleLabel(invitation.role) }}
          components={{ strong: <strong /> }}
        />
      </p>
      <form noValidate onSubmit={onSubmit} className="grid gap-4">
        {/* R65: `attempt` must be a real counter (RHF's submitCount), never a constant. */}
        <FormAlert message={formMessage} attempt={submitCount} />
        {/* A single, PERMANENTLY-mounted `role="status"` region — same pattern as /login and
            /signup, not one created fresh when content arrives (many AT/browser pairs only announce
            a MUTATION inside a region that already existed). Carries both the pending announcement
            and the lost-response hint; the two never coexist (isSubmitting is false by the time
            maybeAccepted is set), so one node stays singular for findByRole('status') either way. */}
        <div role="status" className={maybeAccepted ? 'rounded-md border p-3 text-sm' : 'sr-only'}>
          {isSubmitting && t('invite.submitting')}
          {maybeAccepted && (
            <p>
              <Trans t={t} i18nKey="invite.maybeAccepted" components={{ Link: <Link to="/login" className="underline" /> }} />
            </p>
          )}
        </div>
        {/* Read-only, but a real input with autocomplete=username so password managers save the
            right account. */}
        <TextField id="invite-email" type="email" autoComplete="username" readOnly value={invitation.email} label={t('invite.email')} />
        <PasswordField
          id="invite-password"
          autoComplete="new-password"
          label={t('invite.password')}
          description={t('invite.passwordHint')}
          showLabel={tc('password.show')}
          error={fieldError(errors.password)}
          {...form.register('password')}
        />
        <TextField
          id="invite-phone"
          type="tel"
          autoComplete="tel"
          label={t('invite.phone')}
          error={fieldError(errors.phone)}
          {...form.register('phone')}
        />
        {/* aria-disabled, not disabled — see LoginPage.tsx's identical comment: a native `disabled`
            control loses focus the instant it's applied, stranding a keyboard user at <body> for the
            whole round trip. useAcceptInvitation (P15) holds the refresh lock for the whole call, so
            a second activation queues behind that lock rather than reaching the network again. */}
        <Button type="submit" aria-disabled={isSubmitting || undefined}>
          {isSubmitting ? t('invite.submitting') : t('invite.submit')}
        </Button>
      </form>
    </main>
  );
}
