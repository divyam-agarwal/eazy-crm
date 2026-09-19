import { bareApi } from '@/api/client';
import type * as Client from '@/api/client';
import { parseRetryAfter } from '@/api/errors';
import type { AuthResponse } from '@/api/types';

export type RefreshCallResult =
  | { kind: 'ok'; body: AuthResponse }
  | { kind: 'unauthorized' }
  | { kind: 'forbidden' }
  | { kind: 'unavailable'; retryAfterSeconds?: number };

export const REFRESH_FORBIDDEN_MESSAGE =
  'POST /api/v1/auth/refresh answered 403: the X-EasyCRM-Client header was not sent. This is a client bug, not a signed-out user.';

export async function callRefresh(
  client: ReturnType<typeof Client.createBareClient> = bareApi,
): Promise<RefreshCallResult> {
  try {
    // The contract marks the header required, so this call does not compile without it (plan P3).
    const { data, response } = await client.POST('/api/v1/auth/refresh', {
      params: { header: { 'X-EasyCRM-Client': 'web' } },
    });
    if (response.status === 200 && data) return { kind: 'ok', body: data };
    if (response.status === 401) return { kind: 'unauthorized' };
    if (response.status === 403) return { kind: 'forbidden' };
    return { kind: 'unavailable', retryAfterSeconds: parseRetryAfter(response.headers.get('Retry-After')) };
  } catch {
    return { kind: 'unavailable' };
  }
}
