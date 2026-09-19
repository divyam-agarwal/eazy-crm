// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import {
  getAuthBridge,
  resetAuthBridge,
  setAuthBridge,
  type AuthBridge,
  type RefreshOutcome,
} from './authBridge';

describe('the inert default AuthBridge', () => {
  it('is fail-safe: no token, ends any refresh, and sessionExpired is a no-op', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const bridge = getAuthBridge();

    expect(bridge.getAccessToken()).toBeNull();
    await expect(bridge.refresh('token-1')).resolves.toBe('ended');
    expect(() => bridge.sessionExpired()).not.toThrow();
  });

  it('warns exactly once across multiple inert calls, not once per call (fail-safe, not fail-silent)', async () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    const bridge = getAuthBridge();

    bridge.getAccessToken();
    await bridge.refresh(null);
    bridge.sessionExpired();
    bridge.getAccessToken();

    expect(spy).toHaveBeenCalledTimes(1);
    expect(spy.mock.calls[0]?.[0]).toContain('setAuthBridge() was never invoked');
  });
});

describe('setAuthBridge / getAuthBridge', () => {
  it('installs a real bridge, replacing the inert default, without ever warning', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    const real: AuthBridge = {
      getAccessToken: () => 'token-1',
      refresh: vi.fn(async (): Promise<RefreshOutcome> => 'refreshed'),
      sessionExpired: vi.fn(),
    };

    setAuthBridge(real);

    expect(getAuthBridge()).toBe(real);
    expect(getAuthBridge().getAccessToken()).toBe('token-1');
    expect(spy).not.toHaveBeenCalled();
  });
});

describe('resetAuthBridge', () => {
  it('restores the inert default and re-arms the one-time warning', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    setAuthBridge({
      getAccessToken: () => 'token-1',
      refresh: vi.fn(async (): Promise<RefreshOutcome> => 'refreshed'),
      sessionExpired: vi.fn(),
    });

    resetAuthBridge();

    expect(getAuthBridge().getAccessToken()).toBeNull();
    // Re-armed: the inert bridge warns again on this call, proving reset also cleared `warned`.
    expect(spy).toHaveBeenCalledTimes(1);
  });
});
