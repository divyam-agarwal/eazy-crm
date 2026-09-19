// Security-1: `signing-out` is in-memory, but the credential it guards — the easycrm_rt cookie —
// is durable and shared by every tab. If the tab that started the sign-out is closed or discarded
// by Android, the cookie stays live for 30 days while the UI says "signed out". This marker is the
// part that survives: boot finishes the logout BEFORE it tries to refresh.
// It holds no tenant data — only that a logout is owed.
const KEY = 'easycrm.logoutPending';

export function markLogoutPending(): void {
  try {
    localStorage.setItem(KEY, '1');
  } catch {
    /* private mode / storage disabled: fall back to in-memory `signing-out` only */
  }
}

export function clearLogoutPending(): void {
  try {
    localStorage.removeItem(KEY);
  } catch {
    /* ignore */
  }
}

export function isLogoutPending(): boolean {
  try {
    return localStorage.getItem(KEY) === '1';
  } catch {
    return false;
  }
}
