import { useMutation } from '@tanstack/react-query';
import { api } from '@/api/client';
import { unwrap } from '@/api/errors';
import type { SignupRequest } from '@/api/types';
import { sessionControls } from '../session/start';

export function useSignup() {
  return useMutation({
    // P15: signup writes the refresh cookie and revokes the incoming one — same lock as refresh.
    // See useLogin.ts for the full explanation; useSignup and useAcceptInvitation (Task 12) share it.
    mutationFn: async (body: SignupRequest) =>
      sessionControls().withCookieLock(async () => unwrap(await api.POST('/api/v1/auth/signup', { body }))),
  });
}
