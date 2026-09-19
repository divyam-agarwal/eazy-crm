import { useMutation } from '@tanstack/react-query';
import { api } from '@/api/client';
import { unwrap } from '@/api/errors';
import type { AcceptInvitationRequest } from '@/api/types';
import { sessionControls } from '../session/start';

export function useAcceptInvitation(token: string) {
  return useMutation({
    // P15: accept issues a session and revokes the incoming cookie
    // (`PublicInvitationController.accept` → `auth::logout`) — same lock as refresh.
    mutationFn: async (body: AcceptInvitationRequest) =>
      sessionControls().withCookieLock(async () =>
        unwrap(await api.POST('/api/v1/auth/invitations/{token}/accept', { params: { path: { token } }, body })),
      ),
  });
}
