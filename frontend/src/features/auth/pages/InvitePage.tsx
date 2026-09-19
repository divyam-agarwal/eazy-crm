import { zodResolver } from '@hookform/resolvers/zod';
import { useRef, useState, type ReactNode } from 'react';
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
const STACK = 'grid gap-6';

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
  // Fix round 1 (item 2, a11y/R80): a real "did a human retry" counter, not preview.errorUpdateCount
  // — that ticks on the background query's OWN automatic failures, so it is already >= 1 the very
  // FIRST time the error branch renders. Gating FormAlert's focus-steal on `attempt > 0` then does
  // nothing: a cold 5xx/timeout on the preview fetch would immediately steal focus from PageHeading
  // instead of announcing the page identity first. This counter starts at 0 and only moves on an
  // actual "Try again" click.
  const [retryAttempt, setRetryAttempt] = useState(0);
  useDocumentTitle(t('invite.title'));

  const form = useForm<AcceptValues>({ resolver: zodResolver(acceptSchema), defaultValues: { password: '', phone: '' } });
  const { errors, isSubmitting, submitCount } = form.formState;

  if (accepted) return null; // navigating home; never flash the signed-in card for our own new session

  // Fix round 1 (item 1, security). Checked BEFORE preview.isPending/isError, not after.
  // sessionControls().logout() clears the WHOLE query cache (session.ts's clearSession →
  // clearQueryCache) as part of ending the session — so the very next render after clicking "Sign
  // out and accept" has BOTH `status === 'signing-out'` AND a freshly-pending `preview` (react-query
  // rebuilds a pending Query the instant its cache entry is gone, and re-fetches it). Checking
  // isPending/isError first would show a generic loading skeleton instead of "Keep this page open"
  // — or worse, "This invitation link is invalid or has expired" if that refetch then fails on the
  // same flaky connection that's ALSO the reason the sign-out POST hasn't landed yet, while the sign-
  // out retry loop keeps running silently behind it. Engineering challenge #100/#101: a parent-level
  // side effect (this time the cache clear, not RootLayout's own unmount) reaching back into the one
  // page specifically re-architected to survive the status flip, through a different door.
  const isSigningOut = status === 'signing-out';

  // TS-narrowing note: `preview.isPending`/`preview.isError` are checked directly in this chain's
  // OWN conditions below, not via an intermediate boolean (an earlier draft used `const isLoading =
  // !isSigningOut && (preview.isPending || ...)`) — an intermediate boolean is opaque to
  // TypeScript's control-flow narrowing of `preview`'s discriminated union, so `preview.data` (used
  // as `invitation` in the final `else`) came back "possibly undefined" even though this chain rules
  // that out at runtime. `isSigningOut`'s own branch first is fine: it's unrelated to `preview`'s
  // type, so it doesn't interfere with the narrowing the later `preview.isPending`/`preview.isError`
  // checks still need to do.
  let body: ReactNode;
  if (isSigningOut) {
    body = (
      <div key="signing-out" className={STACK}>
        <PageHeading>{t('invite.heading')}</PageHeading>
      </div>
    );
  } else if (preview.isPending || status === 'booting') {
    body = (
      <div key="loading" className={STACK} aria-busy="true">
        <p className="sr-only">{t('invite.loading')}</p>
        <Skeleton aria-hidden className="h-8 w-2/3" />
        <Skeleton aria-hidden className="h-6 w-full" />
        <Skeleton aria-hidden className="h-64 w-full" />
      </div>
    );
  } else if (preview.isError) {
    const invalid = preview.error instanceof ApiHttpError && preview.error.status === 404;
    // A11y-7: not every failure is the network. Telling someone on good Wi-Fi to check their
    // connection because the server rate-limited them is simply wrong; reuse the same 429/5xx/
    // network branches applyApiError already has. No new keys needed.
    const message = applyApiError(toApiFailure(preview.error), { setError: () => {}, fields: [] }, translator).formMessage;
    body = (
      // A11y-4: `key` per branch. A sibling's own key change forces React to remount everything
      // under it, including PageHeading, so its focus-on-mount effect re-runs on every transition
      // (e.g. "Try again" going from error back to loading and, if it fails again, back to error) —
      // without a key here a focused "Try again" button could be removed from the tree while its
      // ancestor stays the same instance, dropping focus to <body> silently.
      <div key={invalid ? 'invalid' : 'error'} className={STACK}>
        <PageHeading>{t('invite.heading')}</PageHeading>
        {invalid ? (
          <>
            <p>{t('invite.invalid')}</p>
            {/* Fix round 1 (item 4, a11y): the most likely way this branch is reached is a forwarded
                WhatsApp link landing on someone who, by definition, has no account — a dead end with
                no way forward was the actual finding. Same shape as signup.closedBody. */}
            <p>
              <Trans t={t} i18nKey="invite.invalidHint" components={{ Link: <Link to="/login" className="underline" /> }} />
            </p>
          </>
        ) : (
          <>
            {/* R65-equivalent, and fix round 1 item 2: `retryAttempt`, a real "a human clicked retry"
                counter starting at 0 — see its declaration above for why preview.errorUpdateCount
                was wrong here. */}
            <FormAlert message={message} attempt={retryAttempt} />
            <Button
              onClick={() => {
                setRetryAttempt((n) => n + 1);
                void preview.refetch();
              }}
            >
              {tc('actions.retry')}
            </Button>
          </>
        )}
      </div>
    );
  } else {
    const invitation = preview.data;
    if (status === 'authenticated' && me) {
      // F0-10: no redirect (it would lose the link), no silent accept (it would replace this session
      // in every tab).
      body = (
        <div key="signed-in" className={STACK}>
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
                so this click and the signing-out branch above hand off in the same render — no local
                pending flag needed, and nothing here can double-fire a second logout. */}
            <Button onClick={() => void sessionControls().logout()}>{t('invite.signOutAndAccept')}</Button>
            <Button variant="outline" asChild>
              <Link to="/">{t('invite.goToWorkspace')}</Link>
            </Button>
          </div>
        </div>
      );
    } else {
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
            // A lost response: the accept may have gone through on the server even though this tab
            // saw nothing. Remembered so a REPEAT 404 (the token was single-use and is now consumed)
            // reads as "you probably already did this", not as an invalid link.
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

      body = (
        <div key="accept" className={STACK}>
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
            {/* Read-only, but a real input with autocomplete=username so password managers save the
                right account. */}
            <TextField
              id="invite-email"
              type="email"
              autoComplete="username"
              readOnly
              value={invitation.email}
              label={t('invite.email')}
            />
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
            {/* aria-disabled, not disabled — see LoginPage.tsx's identical comment: a native
                `disabled` control loses focus the instant it's applied, stranding a keyboard user at
                <body> for the whole round trip. useAcceptInvitation (P15) holds the refresh lock for
                the whole call, so a second activation queues behind that lock rather than reaching
                the network again. */}
            <Button type="submit" aria-disabled={isSubmitting || undefined}>
              {isSubmitting ? t('invite.submitting') : t('invite.submit')}
            </Button>
          </form>
        </div>
      );
    }
  }

  return (
    <main className={CARD}>
      {/* Fix round 1 (item 3, a11y): ONE permanently-mounted `role="status"` region, hoisted ABOVE
          every branch-keyed `body` above — never created together with its content. The old
          `key="signing-out"` paragraph mounted fresh with its text already inside it, exactly the
          anti-pattern this same file's accept-form region was already built to avoid (see the R65/R78
          history); this is that same fix applied one level higher, to the branch swap itself, and
          interacts helpfully with item 1's reorder: because this node is a sibling of `body`, not
          inside any one branch, it survives the loading branch untouched and is already present the
          instant `isSigningOut` flips true — a MUTATION, not an insertion. Carries the signing-out
          message, the accept-form's pending announcement, and its lost-response hint; the three never
          coexist in the same render (mutually exclusive branches), so this stays a single node for
          `findByRole('status')` either way. */}
      <div role="status" className={isSigningOut || maybeAccepted ? 'rounded-md border p-3 text-sm' : 'sr-only'}>
        {isSigningOut && tc('signOutPending.body')}
        {!isSigningOut && isSubmitting && t('invite.submitting')}
        {maybeAccepted && (
          <p>
            <Trans t={t} i18nKey="invite.maybeAccepted" components={{ Link: <Link to="/login" className="underline" /> }} />
          </p>
        )}
      </div>
      {body}
    </main>
  );
}
