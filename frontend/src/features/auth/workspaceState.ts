/** A11y-5: why the user landed back on /login, when RootLayout sent them (`{ reason: 'expired' }`). */
export function readEndReasonState(state: unknown): 'expired' | null {
  if (typeof state !== 'object' || state === null || !('reason' in state)) return null;
  return state.reason === 'expired' ? 'expired' : null;
}

/** Router state a page may pass to /login to pre-fill the workspace (signup's lost-response hint). */
export function readWorkspaceState(state: unknown): string | null {
  if (typeof state !== 'object' || state === null || !('workspace' in state)) return null;
  return typeof state.workspace === 'string' ? state.workspace : null;
}
