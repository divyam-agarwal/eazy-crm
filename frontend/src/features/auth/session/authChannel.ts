export type AuthMessage =
  | { type: 'login'; userId: string; tenantId: string }
  /** The server confirmed 204: the cookie is really gone. */
  | { type: 'logout' }
  /**
   * P14/Security-1: sign-out was requested but the server has NOT confirmed it. Other tabs must
   * show the blocking screen, NOT the login page — the refresh cookie is still live, so a tab that
   * believed "logged out" would sign the previous user back in on the next reload.
   */
  | { type: 'signing-out' };

export interface AuthChannel {
  post(message: AuthMessage): void;
  subscribe(listener: (message: AuthMessage) => void): () => void;
}

export const AUTH_CHANNEL = 'easycrm-auth';

export function createAuthChannel(): AuthChannel {
  if (typeof BroadcastChannel === 'undefined') return createNoopChannel();
  const channel = new BroadcastChannel(AUTH_CHANNEL);
  return {
    post: (message) => channel.postMessage(message),
    subscribe: (listener) => {
      const handler = (event: MessageEvent<AuthMessage>) => listener(event.data);
      channel.addEventListener('message', handler);
      return () => channel.removeEventListener('message', handler);
    },
  };
}

export function createNoopChannel(): AuthChannel {
  return { post: () => {}, subscribe: () => () => {} };
}
