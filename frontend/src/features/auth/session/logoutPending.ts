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
    // Fix round 1, item 4 (documented, accepted degradation — do not make this blocking, that
    // would break sign-out entirely in private mode, which is worse). On quota exhaustion, private
    // browsing storage denial, or eviction between this call and the POST, P14 silently degrades to
    // the pre-fix in-memory `signing-out` behaviour: correct as long as the tab survives to see the
    // response, but the exact trigger Challenge #89 names — Android discarding a backgrounded tab —
    // is also the scenario most likely to coincide with storage pressure. See Challenge #89.
  }
}

export function clearLogoutPending(): void {
  try {
    localStorage.removeItem(KEY);
  } catch {
    // Fix round 1, item 4: same degradation as markLogoutPending's catch — if storage rejected the
    // write in the first place, this is a no-op either way. See Challenge #89.
  }
}

export function isLogoutPending(): boolean {
  try {
    return localStorage.getItem(KEY) === '1';
  } catch {
    // Fix round 1, item 4: reading fails the same way writing does. Returning `false` here means
    // boot proceeds straight to a refresh, exactly as it did before P14 existed — see Challenge #89.
    return false;
  }
}
