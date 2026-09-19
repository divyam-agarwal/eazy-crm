import { useQuery } from '@tanstack/react-query';
import { api } from '@/api/client';
import { unwrap } from '@/api/errors';
import { authKeys } from './authKeys';

export function useInvitationPreview(token: string) {
  return useQuery({
    queryKey: authKeys.invitation(token),
    queryFn: async ({ signal }) =>
      unwrap(await api.GET('/api/v1/auth/invitations/{token}', { params: { path: { token } }, signal })),
    enabled: token !== '',
  });
}
