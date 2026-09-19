import { describe, expect, it } from 'vitest';
import { RetryableRequestError } from '@/api/authFetch';
import { ApiHttpError } from '@/api/errors';
import { createQueryClient, shouldRetryQuery } from './queryClient';

const http = (status: number) => new ApiHttpError(status, undefined, new Headers());

describe('query defaults (spec §4.5)', () => {
  it('retries network errors and 5xx at most twice, and never 4xx', () => {
    expect(shouldRetryQuery(0, http(503))).toBe(true);
    expect(shouldRetryQuery(1, http(500))).toBe(true);
    expect(shouldRetryQuery(2, http(500))).toBe(false);
    expect(shouldRetryQuery(0, new TypeError('Failed to fetch'))).toBe(true);
    expect(shouldRetryQuery(0, new RetryableRequestError())).toBe(true);
    for (const status of [400, 401, 403, 404, 409, 422, 429]) expect(shouldRetryQuery(0, http(status))).toBe(false);
  });

  it('sets mutation retry 0, no refetch on focus, and a 30 s stale time', () => {
    const { queries, mutations } = createQueryClient().getDefaultOptions();
    expect(mutations?.retry).toBe(0);
    expect(queries?.refetchOnWindowFocus).toBe(false);
    expect(queries?.staleTime).toBe(30_000);
  });

  // P17/Performance-1: the library's default is `networkMode: 'online'`, which PAUSES queries and
  // mutations once the browser reports offline. A paused request never rejects, so applyApiError
  // never runs: /invite/:token would show its skeleton forever and Sign in would sit disabled with
  // no message. This app has its own 15 s timeout and error UI; the queue only hides them.
  it('fails offline requests instead of pausing them, and refetches on reconnect', () => {
    const { queries, mutations } = createQueryClient().getDefaultOptions();
    expect(queries?.networkMode).toBe('always');
    expect(mutations?.networkMode).toBe('always');
    expect(queries?.refetchOnReconnect).toBe(true);
  });
});
