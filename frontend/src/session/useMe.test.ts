import { renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ownerSession } from '@/test/fixtures';
import { resetSessionStoreForTests, useSessionStore } from './sessionStore';
import type { Me } from './types';
import { useMe, useSessionStatus } from './useMe';

const ownerMe: Me = {
  userId: ownerSession.userId,
  tenantId: ownerSession.tenantId,
  tenantSlug: ownerSession.tenantSlug,
  email: ownerSession.email,
  role: 'OWNER',
};

describe('useMe / useSessionStatus', () => {
  it('track the store when authenticated', () => {
    useSessionStore.setState({ status: 'authenticated', me: ownerMe });

    const me = renderHook(() => useMe());
    const status = renderHook(() => useSessionStatus());

    expect(me.result.current).toEqual(ownerMe);
    expect(status.result.current).toBe('authenticated');
  });

  it('return null / booting after resetSessionStoreForTests()', () => {
    useSessionStore.setState({ status: 'authenticated', me: ownerMe });

    resetSessionStoreForTests();

    const me = renderHook(() => useMe());
    const status = renderHook(() => useSessionStatus());

    expect(me.result.current).toBeNull();
    expect(status.result.current).toBe('booting');
  });
});
