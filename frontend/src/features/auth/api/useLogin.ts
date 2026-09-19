import { useMutation } from '@tanstack/react-query';
import { api } from '@/api/client';
import { unwrap } from '@/api/errors';
import type { LoginRequest } from '@/api/types';
import { sessionControls } from '../session/start';

export function useLogin() {
  return useMutation({
    /**
     * P15: login WRITES the refresh cookie, so it serializes with refresh under the same Web Lock.
     * The backend revokes the incoming cookie (`AuthController.login` -> `auth::logout`), and
     * `RefreshTokenService.revoke` revokes the successor of an already-rotated token. Unlocked, a
     * login racing a boot refresh can revoke the refresh's successor while the refresh's
     * `Set-Cookie` lands last -- the jar keeps a dead cookie and the new session dies at its first
     * refresh, ~15 minutes later, with nothing on screen to explain it. `useSignup` (Task 11) and
     * `useAcceptInvitation` (Task 12) take the same lock, for the same reason.
     */
    mutationFn: async (body: LoginRequest) =>
      sessionControls().withCookieLock(async () => unwrap(await api.POST('/api/v1/auth/login', { body }))),
  });
}
