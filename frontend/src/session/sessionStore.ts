import { create } from 'zustand';
import type { Me, SessionStatus } from './types';

interface SessionState {
  status: SessionStatus;
  /** Changes ONLY through establishSession and endSession. */
  me: Me | null;
}

const initial: SessionState = { status: 'booting', me: null };

export const useSessionStore = create<SessionState>()(() => initial);

// Named -ForTests (fix round 1, item 5): it always returns to 'booting', not 'anonymous', which
// would be the wrong contract for a real "sign out and switch user" flow — the old, unqualified
// name invited exactly that future misuse.
export function resetSessionStoreForTests(): void {
  useSessionStore.setState(initial, true);
}
