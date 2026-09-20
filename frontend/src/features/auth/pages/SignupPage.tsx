import { zodResolver } from '@hookform/resolvers/zod';
import { useRef, useState, type ChangeEvent } from 'react';
import { useForm } from 'react-hook-form';
import { Trans, useTranslation } from 'react-i18next';
import { Link, Navigate } from 'react-router';
import * as z from 'zod/mini';
import { toApiFailure } from '@/api/errors';
import { FormAlert } from '@/components/form/FormAlert';
import { PageHeading } from '@/components/PageHeading';
import { PasswordField } from '@/components/form/PasswordField';
import { SelectField } from '@/components/form/SelectField';
import { TextField } from '@/components/form/TextField';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { applyApiError, parseEnvelope } from '@/lib/apiError';
import { forceCase } from '@/lib/caseInput';
import { GST_STATES } from '@/lib/gst/states';
import { useFieldError, useTranslator } from '@/lib/i18n/translator';
import { suggestSlug } from '@/lib/slug';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { useSessionStatus } from '@/session/useMe';
import { useSignup } from '../api/useSignup';
import { useSignupStatus } from '../api/useSignupStatus';
import { establishSession } from '../session/session';

const signupSchema = z.object({
  businessName: z.string().check(z.trim(), z.minLength(1, { error: 'validation.required' })),
  slug: z.string().check(z.regex(/^[a-z0-9-]{3,64}$/, { error: 'validation.slugFormat' })),
  stateCode: z.string().check(z.regex(/^\d{2}$/, { error: 'validation.stateRequired' })),
  gstin: z.string().check(
    z.trim(),
    z.toUpperCase(),
    z.refine((value) => value === '' || /^[0-9A-Z]{15}$/.test(value), { error: 'validation.gstinShape' }),
  ),
  email: z.email({ error: 'validation.email' }),
  phone: z.string().check(z.trim()),
  password: z.string().check(z.minLength(8, { error: 'validation.passwordMin' })),
});
type SignupValues = z.infer<typeof signupSchema>;
const SIGNUP_FIELDS = ['businessName', 'slug', 'stateCode', 'gstin', 'email', 'phone', 'password'] as const;
const STATE_OPTIONS = GST_STATES.map((s) => ({ value: s.code, label: `${s.code} – ${s.name}` }));

export function SignupPage() {
  const { t } = useTranslation('auth');
  const { t: tc } = useTranslation('common');
  const translator = useTranslator();
  const fieldError = useFieldError();
  const status = useSessionStatus();
  const signupStatus = useSignupStatus();
  const signup = useSignup();
  // Slugs whose submit got no response: the workspace may exist even though we never heard back.
  // A ref (not state) so it survives re-render without itself triggering one.
  const lostSlugs = useRef(new Set<string>());
  const [formMessage, setFormMessage] = useState<string | null>(null);
  const [maybeCreatedSlug, setMaybeCreatedSlug] = useState<string | null>(null);
  useDocumentTitle(t('signup.title'));

  const form = useForm<SignupValues>({
    resolver: zodResolver(signupSchema),
    defaultValues: { businessName: '', slug: '', stateCode: '', gstin: '', email: '', phone: '', password: '' },
  });
  // Architecture Minor-2: suggest in the change handler, not in an effect. An effect reacting to a
  // user event costs an extra render per keystroke, and `isDirty` flips back to false when the user
  // clears the slug — so the suggestion would silently overwrite a field they deliberately emptied.
  const slugEdited = useRef(false);
  const onBusinessNameChange = (event: ChangeEvent<HTMLInputElement>) => {
    if (!slugEdited.current) form.setValue('slug', suggestSlug(event.target.value));
  };

  // Also the post-signup navigation: establishSession flips status, and this sends the user home.
  if (status === 'authenticated') return <Navigate to="/" replace />;

  if (signupStatus.isPending) {
    return (
      <main className="mx-auto grid w-full max-w-md gap-6 px-4 py-10" aria-busy="true">
        <p className="sr-only">{t('signup.loading')}</p>
        <Skeleton aria-hidden className="h-8 w-3/4" />
        <Skeleton aria-hidden className="h-[34rem] w-full" />
      </main>
    );
  }

  if (signupStatus.data?.open === false) {
    return (
      <main className="mx-auto grid w-full max-w-md gap-4 px-4 py-10">
        <PageHeading>{t('signup.closedHeading')}</PageHeading>
        <p>
          {/* Challenge #99: `<Link>`, not `<link>` -- see LoginPage.tsx's identical comment. */}
          <Trans t={t} i18nKey="signup.closedBody" components={{ Link: <Link to="/login" className="underline" /> }} />
        </p>
      </main>
    );
  }
  // A failed status check still shows the form: the server enforces the switch either way.

  const onSubmit = form.handleSubmit(async (values) => {
    // Challenge #98 (corrected): the refresh Web Lock serializes concurrent submits, it does not
    // deduplicate them. This guard is what actually stops a resubmit while one is in flight.
    if (signup.isPending) return;
    setFormMessage(null);
    setMaybeCreatedSlug(null);
    try {
      establishSession(
        await signup.mutateAsync({
          businessName: values.businessName,
          slug: values.slug,
          stateCode: values.stateCode,
          email: values.email,
          password: values.password,
          gstin: values.gstin || undefined,
          phone: values.phone || undefined,
        }),
      );
    } catch (error) {
      const failure = toApiFailure(error);
      // A network failure means we never heard back — the signup may have gone through on the
      // server even though this tab saw nothing. Remember the slug so a REPEAT SLUG_TAKEN for it
      // reads as "you probably already did this", not "someone else took your name".
      if (failure.kind === 'network') lostSlugs.current.add(values.slug);
      const slugTaken =
        failure.kind === 'http' && failure.status === 409 && parseEnvelope(failure.body)?.fieldCodes.slug === 'SLUG_TAKEN';
      if (slugTaken && lostSlugs.current.has(values.slug)) {
        setMaybeCreatedSlug(values.slug);
        return;
      }
      setFormMessage(applyApiError(failure, { setError: form.setError, fields: SIGNUP_FIELDS }, translator).formMessage);
    }
  });

  const businessNameField = form.register('businessName');
  const slugField = form.register('slug', {
    onChange: () => {
      slugEdited.current = true; // once touched, the suggestion stops overwriting it
    },
  });
  const gstinField = form.register('gstin');
  const { errors, isSubmitting, submitCount } = form.formState;

  return (
    <main className="mx-auto grid w-full max-w-md gap-6 px-4 py-10">
      <PageHeading>{t('signup.heading')}</PageHeading>
      <form noValidate onSubmit={onSubmit} className="grid gap-4">
        {/* R65: `attempt` must be a real counter (RHF's submitCount), never a constant -- otherwise
            the alert stays silent on a second, identical failure (the common case on flaky 4G). */}
        <FormAlert message={formMessage} attempt={submitCount} />
        {/* Fix round 1 (Important, a11y): a PERMANENTLY-mounted `role="status"` region, same as
            /login's and FormAlert's, not one created fresh when content arrives. A region inserted
            into the DOM together with its content is exactly the case the WAI-ARIA "insert the
            container first, then add content" guidance exists for -- many AT/browser pairs only
            announce a MUTATION inside a region that already existed, so a freshly-mounted status
            span with text already in it can go unannounced. The two messages this region carries
            (the pending announcement and the lost-response hint) still never coexist -- `isSubmitting`
            is false by the time `maybeCreatedSlug` is set (React 19 batches that state update together
            with RHF's own `isSubmitting` flip) -- so one node keeps `findByRole('status')` singular
            while also pre-existing its content, satisfying both constraints at once. */}
        <div role="status" className={maybeCreatedSlug ? 'rounded-md border p-3 text-sm' : 'sr-only'}>
          {isSubmitting && t('signup.submitting')}
          {maybeCreatedSlug && (
            <Trans
              t={t}
              i18nKey="signup.maybeCreated"
              components={{ Link: <Link to="/login" state={{ workspace: maybeCreatedSlug }} className="underline" /> }}
            />
          )}
        </div>
        <TextField
          id="signup-business"
          autoComplete="organization"
          label={t('signup.businessName')}
          error={fieldError(errors.businessName)}
          {...businessNameField}
          onChange={(event) => {
            onBusinessNameChange(event); // suggests the slug in the handler, not an effect
            void businessNameField.onChange(event);
          }}
        />
        <TextField
          id="signup-slug"
          label={t('signup.slug')}
          description={t('signup.slugHint')}
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
        <SelectField
          id="signup-state"
          label={t('signup.state')}
          placeholder={t('signup.statePlaceholder')}
          options={STATE_OPTIONS}
          error={fieldError(errors.stateCode)}
          {...form.register('stateCode')}
        />
        <TextField
          id="signup-gstin"
          label={t('signup.gstin')}
          description={t('signup.gstinHint')}
          autoCapitalize="characters"
          autoCorrect="off"
          spellCheck={false}
          error={fieldError(errors.gstin)}
          {...gstinField}
          onChange={(event) => {
            forceCase(event, 'upper'); // A11y-8: keeps the caret in place
            void gstinField.onChange(event);
          }}
        />
        <TextField
          id="signup-email"
          type="email"
          autoComplete="email"
          label={t('signup.email')}
          error={fieldError(errors.email)}
          {...form.register('email')}
        />
        <TextField
          id="signup-phone"
          type="tel"
          autoComplete="tel"
          label={t('signup.phone')}
          error={fieldError(errors.phone)}
          {...form.register('phone')}
        />
        <PasswordField
          id="signup-password"
          autoComplete="new-password"
          label={t('signup.password')}
          description={t('signup.passwordHint')}
          showLabel={tc('password.show')}
          error={fieldError(errors.password)}
          {...form.register('password')}
        />
        {/* aria-disabled, not disabled -- see LoginPage.tsx's identical comment (Task 10 fix round
            1, item 1): a native `disabled` control loses focus the instant it's applied, stranding a
            keyboard user at <body> for the whole round trip. The refresh Web Lock useSignup (P15)
            holds does NOT prevent a second activation from resubmitting -- it serializes, it does
            not deduplicate. The actual guard is `if (signup.isPending) return;` in `onSubmit` --
            see Challenge #98 (corrected). */}
        <Button type="submit" aria-disabled={isSubmitting || undefined}>
          {isSubmitting ? t('signup.submitting') : t('signup.submit')}
        </Button>
      </form>
      <p className="text-sm">
        <Trans t={t} i18nKey="signup.haveAccount" components={{ Link: <Link to="/login" className="underline" /> }} />
      </p>
    </main>
  );
}
