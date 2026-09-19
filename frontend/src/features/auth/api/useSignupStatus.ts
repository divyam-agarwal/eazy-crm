import { useQuery } from '@tanstack/react-query';
import { api } from '@/api/client';
import { unwrap } from '@/api/errors';
import { authKeys } from './authKeys';

export function useSignupStatus() {
  return useQuery({
    queryKey: authKeys.signupStatus(),
    // Architecture-4: forward the query's AbortSignal, so a superseded or unmounted query really
    // stops. This is the pattern F1-F3 copy for every list and detail fetch.
    queryFn: async ({ signal }) => unwrap(await api.GET('/api/v1/auth/signup/status', { signal })),
  });
}
