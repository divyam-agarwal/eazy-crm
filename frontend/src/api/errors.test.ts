// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { ApiHttpError, ensureOk, parseRetryAfter, toApiFailure, unwrap } from './errors';

describe('unwrap', () => {
  it('returns data from a successful response', () => {
    expect(unwrap({ data: { open: true }, response: new Response(null, { status: 200 }) })).toEqual({ open: true });
  });

  it('throws ApiHttpError carrying status, body and headers', () => {
    const response = new Response(null, { status: 429, headers: { 'Retry-After': '7' } });
    const body = { error: { code: 'RATE_LIMITED', message: 'slow down' } };
    try {
      unwrap({ error: body, response });
      expect.unreachable();
    } catch (e) {
      expect(e).toBeInstanceOf(ApiHttpError);
      expect(e).toMatchObject({ status: 429, body });
      expect((e as ApiHttpError).headers.get('Retry-After')).toBe('7');
    }
  });
});

describe('ensureOk', () => {
  it('accepts a 204 and rejects a 403', () => {
    expect(() => ensureOk({ response: new Response(null, { status: 204 }) })).not.toThrow();
    expect(() => ensureOk({ response: new Response(null, { status: 403 }) })).toThrow(ApiHttpError);
  });
});

describe('toApiFailure', () => {
  it('maps an ApiHttpError to an http failure and anything else to network', () => {
    const http = new ApiHttpError(422, { error: {} }, new Headers());
    expect(toApiFailure(http)).toMatchObject({ kind: 'http', status: 422 });
    expect(toApiFailure(new TypeError('Failed to fetch'))).toEqual({ kind: 'network' });
  });
});

describe('parseRetryAfter', () => {
  it.each([
    ['7', 7],
    [' 30 ', 30],
    ['0', 0],
    [null, undefined],
    ['soon', undefined],
    ['-1', undefined],
    ['1.5', undefined],
  ])('parses %s as %s', (input, expected) => {
    expect(parseRetryAfter(input)).toBe(expected);
  });
});
