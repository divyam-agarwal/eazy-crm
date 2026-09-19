import type { FieldValues, Path, UseFormSetError } from 'react-hook-form';
import { parseRetryAfter, type ApiFailure } from '@/api/errors';
import type { Translator } from './i18n/translator';

export interface ParsedEnvelope {
  code: string | null;
  message: string | null;
  fields: Record<string, string>;
  fieldCodes: Record<string, string>;
}

function stringEntries(value: unknown): Record<string, string> {
  if (typeof value !== 'object' || value === null) return {};
  return Object.fromEntries(Object.entries(value).filter((e): e is [string, string] => typeof e[1] === 'string'));
}

/** Defensive by design (spec §4.6): a body-less 401/403 or a non-JSON body never throws. */
export function parseEnvelope(body: unknown): ParsedEnvelope | null {
  if (typeof body !== 'object' || body === null || !('error' in body)) return null;
  const error = body.error;
  if (typeof error !== 'object' || error === null) return null;
  const record = error as Record<string, unknown>;
  return {
    code: typeof record.code === 'string' ? record.code : null,
    message: typeof record.message === 'string' ? record.message : null,
    fields: stringEntries(record.fields),
    fieldCodes: stringEntries(record.fieldCodes),
  };
}

export interface ApplyResult<T extends FieldValues> {
  formMessage: string | null;
  appliedFields: Path<T>[];
  code: string | null;
}

const FIELD_STATUSES = new Set([400, 409, 422]);

/** The one mapping from an API failure to a form, used by every form in F0–F3 (spec §4.6). */
export function applyApiError<T extends FieldValues>(
  failure: ApiFailure,
  form: { setError: UseFormSetError<T>; fields: readonly Path<T>[] },
  tr: Translator,
): ApplyResult<T> {
  // R41: a caller-cancelled request (e.g. a superseded submit) is not a failure the user should
  // see — never a form message, never a field error.
  if (failure.kind === 'aborted') return { formMessage: null, appliedFields: [], code: null };
  if (failure.kind === 'network') return { formMessage: tr.t('errors.network'), appliedFields: [], code: null };

  const envelope = parseEnvelope(failure.body);
  const code = envelope?.code ?? null;

  if (failure.status === 429) {
    const seconds = parseRetryAfter(failure.headers.get('Retry-After'));
    const formMessage = seconds === undefined ? tr.t('errors.rateLimited') : tr.t('errors.rateLimitedIn', { count: seconds });
    return { formMessage, appliedFields: [], code };
  }
  if (failure.status >= 500) return { formMessage: tr.t('errors.server'), appliedFields: [], code };

  if (envelope && FIELD_STATUSES.has(failure.status)) {
    const appliedFields: Path<T>[] = [];
    for (const field of form.fields) {
      const fieldCode = envelope.fieldCodes[field];
      const serverText = envelope.fields[field];
      if (fieldCode === undefined && serverText === undefined) continue;
      // A11y-3: a per-field key wins over the generic constraint key, because a bare `SIZE` code
      // carries no min or max — "This value has the wrong length." replaces the server's
      // "password must be at least 8 characters" and tells the user nothing about how to fix it.
      // Order: errors.fields.<field>.<CODE> → errors.fields.<CODE> → errors.codes.<code> → server text.
      // The per-field texts state real limits, so confirm each number against the backend DTO's
      // @Size/@Pattern before writing it (SignupRequest, AcceptInvitationRequest) — a wrong limit
      // here is worse than the generic message it replaces. Record in `docs/api/error-codes.md`
      // that SIZE and PATTERN are parameterised and therefore want field-specific keys.
      const specific = fieldCode !== undefined ? `errors.fields.${field}.${fieldCode}` : undefined;
      const message =
        specific !== undefined && tr.exists(specific)
          ? tr.t(specific)
          : fieldCode !== undefined && tr.exists(`errors.fields.${fieldCode}`)
            ? tr.t(`errors.fields.${fieldCode}`)
            : code !== null && tr.exists(`errors.codes.${code}`)
              ? tr.t(`errors.codes.${code}`)
              : (serverText ?? tr.t('errors.unexpected'));
      form.setError(field, { type: 'server', message }, { shouldFocus: appliedFields.length === 0 });
      appliedFields.push(field);
    }
    if (appliedFields.length > 0) return { formMessage: null, appliedFields, code };
  }

  const formMessage =
    code !== null && tr.exists(`errors.codes.${code}`)
      ? tr.t(`errors.codes.${code}`)
      : (envelope?.message ?? tr.t('errors.unexpected'));
  return { formMessage, appliedFields: [], code };
}
