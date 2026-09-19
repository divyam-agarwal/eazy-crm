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
  // Fix round 1, item 6: this used to await a bare `setTimeout(…, 0)` racing real BroadcastChannel
  // delivery — a genuine race (delivery is asynchronous, but nothing bounds it below one macrotask),
  // and it flaked about 1 in 8 full-suite runs. Replaced with a deterministic latch: the receiver's
  // own handler resolves a promise, and the test awaits exactly that, however long it actually takes.
  it('delivers a message posted on one instance to a listener on another', async () => {
    const sender = createAuthChannel();
    const receiver = createAuthChannel();
    const received: AuthMessage[] = [];
    let notifyDelivered!: () => void;
    const delivered = new Promise<void>((resolve) => (notifyDelivered = resolve));
    receiver.subscribe((message) => {
      received.push(message);
      notifyDelivered();
    });

    sender.post({ type: 'logout' });
    await delivered;

    expect(received).toEqual<AuthMessage[]>([{ type: 'logout' }]);
  });

  it('stops delivering messages once the returned unsubscribe function is called', async () => {
    const sender = createAuthChannel();
    const receiver = createAuthChannel();
    const received: AuthMessage[] = [];
    const unsubscribe = receiver.subscribe((message) => received.push(message));
    unsubscribe();

    // Deterministic sync point for a NEGATIVE assertion: a second, still-subscribed listener on the
    // SAME receiver observes a sentinel message posted after the real one. BroadcastChannel preserves
    // delivery order for a given channel pair, so once the sentinel has arrived, any delivery to the
    // unsubscribed listener (same channel, same ordering) has already been attempted too.
    let notifySentinel!: () => void;
    const sentinelDelivered = new Promise<void>((resolve) => (notifySentinel = resolve));
    receiver.subscribe((message) => {
      if (message.type === 'login' && message.userId === 'sentinel') notifySentinel();
    });

    sender.post({ type: 'login', userId: 'u1', tenantId: 't1' });
    sender.post({ type: 'login', userId: 'sentinel', tenantId: 't1' });
    await sentinelDelivered;

    expect(received).toEqual([]);
  });
});
