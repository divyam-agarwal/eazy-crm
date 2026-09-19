import { describe, expect, it } from 'vitest';
import type { AuthMessage } from './authChannel';
import { createAuthChannel } from './authChannel';

// Fix round 1, item 3: every subscribeToAuthChannel test (session.test.ts) uses a synchronous stub
// AuthChannel, so the real BroadcastChannel wiring here — the channel NAME, addEventListener, and
// the returned unsubscribe — is never actually executed by the suite. That's P14's security-critical
// cross-tab path: a typo in AUTH_CHANNEL, a missing listener, or a broken unsubscribe would pass the
// whole test suite and surface in production only as "signing out in one tab doesn't sign out the
// others". This exercises two REAL `createAuthChannel()` instances end to end.
describe('createAuthChannel', () => {
  it('delivers a message posted on one instance to a listener on another', async () => {
    const sender = createAuthChannel();
    const receiver = createAuthChannel();
    const received: AuthMessage[] = [];
    receiver.subscribe((message) => received.push(message));

    sender.post({ type: 'logout' });
    // BroadcastChannel delivery is asynchronous even within one process/tab — let it land.
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(received).toEqual<AuthMessage[]>([{ type: 'logout' }]);
  });

  it('stops delivering messages once the returned unsubscribe function is called', async () => {
    const sender = createAuthChannel();
    const receiver = createAuthChannel();
    const received: AuthMessage[] = [];
    const unsubscribe = receiver.subscribe((message) => received.push(message));

    unsubscribe();
    sender.post({ type: 'login', userId: 'u1', tenantId: 't1' });
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(received).toEqual([]);
  });
});
