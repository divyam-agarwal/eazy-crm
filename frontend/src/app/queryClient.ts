import { QueryClient } from '@tanstack/react-query';
import { ApiHttpError } from '@/api/errors';

const MAX_QUERY_RETRIES = 2;

export function shouldRetryQuery(failureCount: number, error: unknown): boolean {
  if (failureCount >= MAX_QUERY_RETRIES) return false;
  if (error instanceof ApiHttpError) return error.status >= 500;
  return true; // network failures, timeouts, RetryableRequestError
}

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: shouldRetryQuery,
        refetchOnWindowFocus: false,
        refetchOnReconnect: true,
        // P17: never pause on `offline` — fail, so the error UI runs. See queryClient.test.ts.
        networkMode: 'always',
        staleTime: 30_000,
      },
      mutations: { retry: 0, networkMode: 'always' },
    },
  });
}
