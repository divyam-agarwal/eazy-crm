import type { AuthBridge } from '@/api/authBridge';
import { getAccessToken } from './accessToken';
import type { RefreshCoordinator } from './refreshCoordinator';
import { endSession } from './session';
import { emitSessionExpired } from './sessionEvents';

export function createSessionAuthBridge(coordinator: RefreshCoordinator): AuthBridge {
  return {
    getAccessToken,
    refresh: (tokenAtFailure) => coordinator.refresh(tokenAtFailure),
    sessionExpired: () => {
      endSession('expired');
      emitSessionExpired();
    },
  };
}
