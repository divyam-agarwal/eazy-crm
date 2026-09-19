// Security-1: `signing-out` is in-memory, but the credential it guards — the easycrm_rt cookie —
// is durable and shared by every tab. If the tab that started the sign-out is closed or discarded
// by Android, the cookie stays live for 30 days while the UI says "signed out". This marker is the
// part that survives: boot finishes the logout BEFORE it tries to refresh.
const KEY = 'easycrm.logoutPending';

/**
 * Fix round 2 (rulings.md R59, reversed): who the pending logout is FOR. This is a deliberate,
 * narrow exception to the global constraint that the marker is "a flag, never tenant data" — see
 * the Task 6 report for the record. A user id and tenant id are not the tenant/business data that
 * constraint was written about: they are transient, cleared the instant the marker itself is, and
 * exist only so a boot-resumed logout can tell a genuine `login` for a DIFFERENT person from a
 * forged claim while the same stale cookie is still live.
 *
 * Without this, a logout resumed by `boot.ts`'s `finishPendingLogout()` on a freshly loaded tab has
 * no local `me` to compare against, so `logout.ts`'s `onLoginBroadcast` cannot classify a verified
 * 200 either way and stays pending — which reads as the safe default but isn't: the shared-phone
 * handoff this whole feature targets (staff A signs out, tab discarded mid-retry, staff B logs in
 * for real next) then lets the zombie retry's next tick POST logout with B's freshly-issued cookie,
 * ending an unrelated, legitimate, just-authenticated session. See Challenge #90's amendment.
 */
const PRINCIPAL_KEY = 'easycrm.logoutPendingPrincipal';

export function markLogoutPending(principal?: { userId: string; tenantId: string } | null): void {
  try {
    localStorage.setItem(KEY, '1');
    // A missing/`null` principal — a boot-resumed retry has no local session to read one from —
    // must NOT clobber a real principal an EARLIER call over this same marker already persisted.
    // markLogoutPending() runs again every time logout() runs, including on every resumed retry.
    if (principal) localStorage.setItem(PRINCIPAL_KEY, JSON.stringify(principal));
  } catch {
    // private mode / storage disabled: fall back to in-memory `signing-out` only. Documented,
    // accepted, non-blocking degradation — see Challenge #89's fix-round-1 amendment.
  }
}

export function clearLogoutPending(): void {
  try {
    localStorage.removeItem(KEY);
    localStorage.removeItem(PRINCIPAL_KEY);
  } catch {
    // ignore — see Challenge #89
  }
}

export function isLogoutPending(): boolean {
  try {
    return localStorage.getItem(KEY) === '1';
  } catch {
    // reading fails the same way writing does: boot proceeds straight to a refresh, exactly as it
    // did before P14 existed — see Challenge #89.
    return false;
  }
}

/**
 * The principal `markLogoutPending` persisted, if any and if it still parses as one. Never throws;
 * a corrupt or missing value reads the same as "unknown owner" (logout.ts's `onLoginBroadcast`
 * already has a documented, conservative fallback for that case).
 */
export function logoutPendingPrincipal(): { userId: string; tenantId: string } | null {
  try {
    const raw = localStorage.getItem(PRINCIPAL_KEY);
    if (!raw) return null;
    const parsed: unknown = JSON.parse(raw);
    const userId = (parsed as { userId?: unknown } | null)?.userId;
    const tenantId = (parsed as { tenantId?: unknown } | null)?.tenantId;
    if (typeof userId === 'string' && typeof tenantId === 'string') return { userId, tenantId };
    return null;
  } catch {
    return null;
  }
}
