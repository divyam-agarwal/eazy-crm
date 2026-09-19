import { afterEach, describe, expect, it, vi } from 'vitest';
import { clearLogoutPending, isLogoutPending, markLogoutPending } from './logoutPending';

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
  });
});
