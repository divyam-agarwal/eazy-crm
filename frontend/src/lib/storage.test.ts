import { afterEach, describe, expect, it, vi } from 'vitest';
import { readLastWorkspace, writeLastWorkspace } from './storage';

describe('remembered workspace', () => {
  afterEach(() => localStorage.clear());

  it('round-trips through localStorage under easycrm.lastWorkspace', () => {
    writeLastWorkspace('ravi-traders');
    expect(localStorage.getItem('easycrm.lastWorkspace')).toBe('ravi-traders');
    expect(readLastWorkspace()).toBe('ravi-traders');
  });

  it('means "nothing remembered" when storage calls throw', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('full', 'QuotaExceededError');
    });
    expect(() => writeLastWorkspace('x')).not.toThrow();
    expect(readLastWorkspace()).toBeNull();
  });

  it('means "nothing remembered" when even reading window.localStorage throws', () => {
    const original = Object.getOwnPropertyDescriptor(window, 'localStorage');
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      get() {
        throw new DOMException('blocked', 'SecurityError');
      },
    });
    try {
      expect(readLastWorkspace()).toBeNull();
      expect(() => writeLastWorkspace('x')).not.toThrow();
    } finally {
      if (original) Object.defineProperty(window, 'localStorage', original);
    }
  });
});
