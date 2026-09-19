import { useSessionStore } from './sessionStore';
import type { Me, SessionStatus } from './types';

/** The signed-in principal, or null. Null while booting, anonymous or signing out. */
export function useMe(): Me | null {
  return useSessionStore((s) => s.me);
}

export function useSessionStatus(): SessionStatus {
  return useSessionStore((s) => s.status);
}
