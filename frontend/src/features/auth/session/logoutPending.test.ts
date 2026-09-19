import { afterEach, describe, expect, it, vi } from 'vitest';
import { clearLogoutPending, isLogoutPending, logoutPendingPrincipal, markLogoutPending } from './logoutPending';

describe('logout pending marker', () => {
  afterEach(() => localStorage.clear());

  it('round-trips through localStorage under easycrm.logoutPending', () => {
    expect(isLogoutPending()).toBe(false);

    markLogoutPending();

    expect(localStorage.getItem('easycrm.logoutPending')).toBe('1');
    expect(isLogoutPending()).toBe(true);
  });

  it('is false after clearLogoutPending()', () => {
    markLogoutPending();

    clearLogoutPending();

    expect(isLogoutPending()).toBe(false);
    expect(localStorage.getItem('easycrm.logoutPending')).toBeNull();
  });

  it('does not throw when storage is unavailable — a private-mode browser must still sign out', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });

    expect(() => markLogoutPending()).not.toThrow();
    expect(() => clearLogoutPending()).not.toThrow();
    expect(isLogoutPending()).toBe(false);
    expect(logoutPendingPrincipal()).toBeNull();
  });
});

// Fix round 2: persisted alongside the marker so a boot-resumed logout (no local `me`) can still
// tell a genuine login for a different person from a forged claim — see the module doc comment and
// the Task 6 report for why this is a deliberate exception to "the marker is a flag, never data".
describe('logout pending principal', () => {
  afterEach(() => localStorage.clear());

  it('is null when nothing has been persisted', () => {
    expect(logoutPendingPrincipal()).toBeNull();
  });

  it('round-trips a principal given to markLogoutPending', () => {
    markLogoutPending({ userId: 'u1', tenantId: 't1' });

    expect(logoutPendingPrincipal()).toEqual({ userId: 'u1', tenantId: 't1' });
  });

  it('clearLogoutPending() clears the persisted principal too', () => {
    markLogoutPending({ userId: 'u1', tenantId: 't1' });

    clearLogoutPending();

    expect(logoutPendingPrincipal()).toBeNull();
  });

  // The specific bug this exists to prevent: logout() calls markLogoutPending() again on every
  // resumed retry, including boot's finishPendingLogout() on a fresh tab with no local session —
  // that call passes no principal, and it must not erase the real one an earlier call recorded.
  it('marking again with no principal does not clobber an already-persisted one', () => {
    markLogoutPending({ userId: 'u1', tenantId: 't1' });

    markLogoutPending(); // e.g. a boot-resumed retry with no local session
    markLogoutPending(null);

    expect(logoutPendingPrincipal()).toEqual({ userId: 'u1', tenantId: 't1' });
  });

  it('ignores a corrupt stored value rather than throwing', () => {
    localStorage.setItem('easycrm.logoutPendingPrincipal', '{not json');

    expect(logoutPendingPrincipal()).toBeNull();
  });
});
