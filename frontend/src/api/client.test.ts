// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { http } from '@/test/openapiHttp';
import { ownerSession } from '@/test/fixtures';
import { server } from '@/test/msw';
import { setAuthBridge, type AuthBridge, type RefreshOutcome } from './authBridge';
import type { FetchLike } from './authFetch';
import { bareApi, createApiClient, createBareClient } from './client';

function fakeBridge(token: string | null): AuthBridge {
  return {
    getAccessToken: () => token,
    refresh: vi.fn(async (): Promise<RefreshOutcome> => 'refreshed'),
    sessionExpired: vi.fn(),
  };
}

function recordingFetch(status = 200, body: unknown = {}): { impl: FetchLike; seen: Headers[] } {
  const seen: Headers[] = [];
  const impl: FetchLike = vi.fn(async (request: Request) => {
    seen.push(request.headers);
    return new Response(JSON.stringify(body), {
      status,
      headers: { 'content-type': 'application/json' },
    });
  });
  return { impl, seen };
}

// T-1: the pieces (authFetch, authBridge, errors) are well covered in isolation, but nothing
// exercised the ASSEMBLY in client.ts — createApiClient/createBareClient wiring the right fetch
// factory to openapi-fetch. Swapping createAuthFetch -> createBareFetch inside createApiClient
// left typecheck, every existing test, and the build all green; these tests exist to close that.
describe('createApiClient / createBareClient composition', () => {
  it('createApiClient attaches the bearer token and the client header to a real request', async () => {
    setAuthBridge(fakeBridge('token-1'));
    const { impl, seen } = recordingFetch();
    const client = createApiClient(impl);

    await client.GET('/api/v1/auth/me');

    expect(seen[0]?.get('Authorization')).toBe('Bearer token-1');
    expect(seen[0]?.get('X-EasyCRM-Client')).toBe('web');
  });

  it('createBareClient never attaches Authorization, even with a live bridge holding a token', async () => {
    setAuthBridge(fakeBridge('token-1'));
    const { impl, seen } = recordingFetch(200, ownerSession);
    const client = createBareClient(impl);

    await client.POST('/api/v1/auth/refresh', { params: { header: { 'X-EasyCRM-Client': 'web' } } });

    expect(seen[0]?.has('Authorization')).toBe(false);
    expect(seen[0]?.get('X-EasyCRM-Client')).toBe('web');
  });
});

// T-2: openapiHttp.typecheck.ts proves the *types* of a schema-typed MSW handler line up with the
// contract; it proves nothing about runtime interception. This round-trips a real request through
// the actual openapi-fetch client (the exported `bareApi` singleton, unmocked fetchImpl) and a
// schema-typed openapi-msw handler, to prove `baseUrl: '*'` in openapiHttp.ts actually intercepts
// whatever origin() in client.ts builds (http://localhost here, since this file runs in the node
// environment with no globalThis.location).
describe('runtime interception through the real openapi-fetch client and a schema-typed MSW handler', () => {
  it('bareApi.POST is intercepted by a typed msw handler and returns the shaped body', async () => {
    server.use(http.post('/api/v1/auth/refresh', ({ response }) => response(200).json(ownerSession)));

    const { data, error, response } = await bareApi.POST('/api/v1/auth/refresh', {
      params: { header: { 'X-EasyCRM-Client': 'web' } },
    });

    expect(response.status).toBe(200);
    expect(error).toBeUndefined();
    expect(data).toEqual(ownerSession);
  });
});
