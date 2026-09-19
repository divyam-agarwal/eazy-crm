import { describe, expect, it, vi } from 'vitest';
import type { ApiFailure } from '@/api/errors';
import { applyApiError, parseEnvelope } from './apiError';
import type { Translator } from './i18n/translator';

const KEYS: Record<string, string> = {
  'errors.network': 'NETWORK',
  'errors.server': 'SERVER',
  'errors.unexpected': 'UNEXPECTED',
  'errors.rateLimited': 'RATE',
  'errors.rateLimitedIn': 'RATE {{count}}',
  'errors.fields.GSTIN_CHECKSUM': 'GSTIN BAD',
  'errors.fields.SLUG_TAKEN': 'SLUG TAKEN',
  'errors.codes.FORBIDDEN': 'NOT ALLOWED',
};
const tr: Translator = {
  exists: (key) => key in KEYS,
  t: (key, options) => (KEYS[key] ?? `!!${key}`).replace('{{count}}', String(options?.count ?? '')),
};

const http = (status: number, body: unknown, headers: Record<string, string> = {}): ApiFailure => ({
  kind: 'http',
  status,
  body,
  headers: new Headers(headers),
});

type Form = { slug: string; gstin: string; email: string };
const FIELDS = ['slug', 'gstin', 'email'] as const;

describe('applyApiError', () => {
  it('translates fieldCodes and focuses only the first field', () => {
    const setError = vi.fn();
    const result = applyApiError<Form>(
      http(422, {
        error: {
          code: 'VALIDATION_FAILED',
          message: 'invalid',
          fields: { gstin: 'bad checksum', slug: 'taken' },
          fieldCodes: { gstin: 'GSTIN_CHECKSUM', slug: 'SLUG_TAKEN' },
        },
      }),
      { setError, fields: FIELDS },
      tr,
    );

    expect(result).toEqual({ formMessage: null, appliedFields: ['slug', 'gstin'], code: 'VALIDATION_FAILED' });
    expect(setError).toHaveBeenNthCalledWith(1, 'slug', { type: 'server', message: 'SLUG TAKEN' }, { shouldFocus: true });
    expect(setError).toHaveBeenNthCalledWith(2, 'gstin', { type: 'server', message: 'GSTIN BAD' }, { shouldFocus: false });
  });

  it('falls back from an unknown field code to the code key, then to the server text', () => {
    const setError = vi.fn();
    applyApiError<Form>(
      http(400, {
        error: { code: 'VALIDATION_FAILED', message: 'x', fields: { email: 'must be a well-formed email address' }, fieldCodes: { email: 'NEW_CODE' } },
      }),
      { setError, fields: FIELDS },
      tr,
    );
    expect(setError).toHaveBeenCalledWith('email', { type: 'server', message: 'must be a well-formed email address' }, { shouldFocus: true });
  });

  it.each([400, 409, 422])('maps field errors for %s', (status) => {
    const setError = vi.fn();
    applyApiError<Form>(
      http(status, { error: { code: 'X', message: 'x', fields: { slug: 'taken' }, fieldCodes: { slug: 'SLUG_TAKEN' } } }),
      { setError, fields: FIELDS },
      tr,
    );
    expect(setError).toHaveBeenCalledTimes(1);
  });

  it('uses the envelope message when no field matches the form', () => {
    const result = applyApiError<Form>(
      http(409, { error: { code: 'CONFLICT', message: 'already exists', fields: { other: 'x' } } }),
      { setError: vi.fn(), fields: FIELDS },
      tr,
    );
    expect(result.formMessage).toBe('already exists');
  });

  it('prefers a translated code for the form message', () => {
    const result = applyApiError<Form>(
      http(403, { error: { code: 'FORBIDDEN', message: 'role not permitted' } }),
      { setError: vi.fn(), fields: FIELDS },
      tr,
    );
    expect(result.formMessage).toBe('NOT ALLOWED');
  });

  it.each([
    ['a body-less 401', http(401, undefined)],
    ['a non-JSON 403', http(403, '<html>forbidden</html>')],
    ['an envelope without error', http(400, { message: 'nope' })],
  ])('never throws on %s', (_label, failure) => {
    expect(applyApiError<Form>(failure, { setError: vi.fn(), fields: FIELDS }, tr).formMessage).toBe('UNEXPECTED');
  });

  it('uses Retry-After on 429', () => {
    const withHeader = applyApiError<Form>(http(429, undefined, { 'Retry-After': '12' }), { setError: vi.fn(), fields: FIELDS }, tr);
    const without = applyApiError<Form>(http(429, undefined), { setError: vi.fn(), fields: FIELDS }, tr);
    expect(withHeader.formMessage).toBe('RATE 12');
    expect(without.formMessage).toBe('RATE');
  });

  it('maps network failures and 5xx to form-level messages', () => {
    expect(applyApiError<Form>({ kind: 'network' }, { setError: vi.fn(), fields: FIELDS }, tr).formMessage).toBe('NETWORK');
    expect(applyApiError<Form>(http(503, undefined), { setError: vi.fn(), fields: FIELDS }, tr).formMessage).toBe('SERVER');
  });

  // R41: an aborted request (caller-cancelled, e.g. a superseded submit) is not a failure the user
  // should see — never a form message, never a field error.
  it('treats an aborted request as a no-op', () => {
    const setError = vi.fn();
    const result = applyApiError<Form>({ kind: 'aborted' }, { setError, fields: FIELDS }, tr);
    expect(result).toEqual({ formMessage: null, appliedFields: [], code: null });
    expect(setError).not.toHaveBeenCalled();
  });
});

describe('parseEnvelope', () => {
  it('keeps only string values from fields and fieldCodes', () => {
    expect(parseEnvelope({ error: { code: 'X', message: 'm', fields: { a: 'x', b: 3 }, fieldCodes: { a: 'A', b: null } } })).toEqual({
      code: 'X',
      message: 'm',
      fields: { a: 'x' },
      fieldCodes: { a: 'A' },
    });
  });
});
