import { describe, expect, it, vi } from 'vitest';
import { IMPORT_RETRY_SCHEDULE_MS, withImportRetry } from './lazyImport';

describe('withImportRetry (R47(b))', () => {
  it('resolves on the first successful attempt without waiting', async () => {
    const load = vi.fn(async () => 'chunk');
    await expect(withImportRetry(load, { schedule: [1, 1, 1] })).resolves.toBe('chunk');
    expect(load).toHaveBeenCalledTimes(1);
  });

  it('retries a rejected dynamic import() and resolves once a later attempt succeeds', async () => {
    let calls = 0;
    const load = vi.fn(async () => {
      calls += 1;
      if (calls < 3) throw new TypeError('Failed to fetch dynamically imported module');
      return 'chunk';
    });

    await expect(withImportRetry(load, { schedule: [1, 1, 1] })).resolves.toBe('chunk');
    expect(load).toHaveBeenCalledTimes(3);
  });

  it('gives up and rejects with the last error once the schedule is exhausted', async () => {
    const failure = new TypeError('Failed to fetch dynamically imported module');
    const load = vi.fn(async () => {
      throw failure;
    });

    await expect(withImportRetry(load, { schedule: [1, 1, 1] })).rejects.toBe(failure);
    expect(load).toHaveBeenCalledTimes(4); // the initial attempt plus one per scheduled delay
  });

  // Fix round 1, item 3: the schedule must actually back off toward spec §4.4's own ~5 s boot-retry
  // benchmark, not give up in ~1 s. Pinned so a future edit can't silently shrink it back down.
  it('backs off toward the spec §4.4 ~5 s benchmark instead of giving up in ~1 s', () => {
    expect(IMPORT_RETRY_SCHEDULE_MS).toEqual([500, 1_500, 3_000]);
    const total = IMPORT_RETRY_SCHEDULE_MS.reduce((sum, ms) => sum + ms, 0);
    expect(total).toBe(5_000);
  });
});
