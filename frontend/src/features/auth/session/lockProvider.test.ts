// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { createInMemoryLocks, noopLocks } from './lockProvider';

describe('lock providers', () => {
  it('in-memory locks run holders one at a time, even when a holder throws', async () => {
    const locks = createInMemoryLocks();
    const order: string[] = [];
    const a = locks.withLock('x', async () => {
      order.push('a-start');
      await new Promise((r) => setTimeout(r, 5));
      order.push('a-end');
      throw new Error('a failed');
    });
    const b = locks.withLock('x', async () => {
      order.push('b');
      return 'b-result';
    });

    await expect(a).rejects.toThrow('a failed');
    expect(await b).toBe('b-result');
    expect(order).toEqual(['a-start', 'a-end', 'b']);
  });

  it('the no-op provider really is not exclusive (it exists only for the recorded red run)', async () => {
    const order: string[] = [];
    await Promise.all([
      noopLocks.withLock('x', async () => {
        order.push('a-start');
        await new Promise((r) => setTimeout(r, 5));
        order.push('a-end');
      }),
      noopLocks.withLock('x', async () => {
        order.push('b');
      }),
    ]);
    expect(order).toEqual(['a-start', 'b', 'a-end']);
  });
});
