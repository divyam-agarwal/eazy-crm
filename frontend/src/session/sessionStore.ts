import { create } from 'zustand';
import type { Me, SessionStatus } from './types';

interface SessionState {
  status: SessionStatus;
  /** Changes ONLY through establishSession and endSession. */
  me: Me | null;
}

const initial: SessionState = { status: 'booting', me: null };

export const useSessionStore = create<SessionState>()(() => initial);

export function resetSessionStore(): void {
  useSessionStore.setState(initial, true);
}
