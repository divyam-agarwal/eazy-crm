import { describe, expect, it, vi } from 'vitest';
import { createBareClient } from '@/api/client';
import { ownerSession } from '@/test/fixtures';
import { callRefresh, type RefreshCallResult } from './refreshCall';

function clientAnswering(response: () => Response) {
  const seen: Request[] = [];
  const client = createBareClient(async (request) => {
    seen.push(request);
    return response();
  });
  return { client, seen };
}

const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', ...headers } });

describe('callRefresh', () => {
  it('sends X-EasyCRM-Client: web from the bare client', async () => {
    const { client, seen } = clientAnswering(() => json(200, ownerSession));

    await callRefresh(client);

    expect(seen[0]?.method).toBe('POST');
    expect(new URL(seen[0]?.url ?? '').pathname).toBe('/api/v1/auth/refresh');
    expect(seen[0]?.headers.get('X-EasyCRM-Client')).toBe('web');
    expect(seen[0]?.headers.has('Authorization')).toBe(false);
  });

  it.each<[string, () => Response, RefreshCallResult]>([
    ['200', () => json(200, ownerSession), { kind: 'ok', body: ownerSession }],
    ['401', () => json(401, { error: { code: 'UNAUTHORIZED', message: 'x' } }), { kind: 'unauthorized' }],
    ['403', () => json(403, { error: { code: 'FORBIDDEN', message: 'x' } }), { kind: 'forbidden' }],
    [
      '429',
      () => json(429, { error: { code: 'RATE_LIMITED', message: 'x' } }, { 'Retry-After': '7' }),
      { kind: 'unavailable', retryAfterSeconds: 7 },
    ],
    ['503', () => new Response(null, { status: 503 }), { kind: 'unavailable', retryAfterSeconds: undefined }],
  ])('maps %s', async (_label, response, expected) => {
    const { client } = clientAnswering(response);
    expect(await callRefresh(client)).toEqual(expected);
  });

  it('maps a network failure to unavailable', async () => {
    const client = createBareClient(vi.fn(async () => Promise.reject(new TypeError('Failed to fetch'))));
    expect(await callRefresh(client)).toEqual({ kind: 'unavailable' });
  });
});
