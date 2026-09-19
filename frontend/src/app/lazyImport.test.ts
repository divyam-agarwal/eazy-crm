import { describe, expect, it, vi } from 'vitest';
import { withImportRetry } from './lazyImport';

describe('withImportRetry (R47(b))', () => {
  it('resolves on the first successful attempt without waiting', async () => {
    const load = vi.fn(async () => 'chunk');
    await expect(withImportRetry(load, { attempts: 3, delayMs: 1 })).resolves.toBe('chunk');
    expect(load).toHaveBeenCalledTimes(1);
  });

  it('retries a rejected dynamic import() and resolves once a later attempt succeeds', async () => {
    let calls = 0;
    const load = vi.fn(async () => {
      calls += 1;
      if (calls < 3) throw new TypeError('Failed to fetch dynamically imported module');
      return 'chunk';
    });

    await expect(withImportRetry(load, { attempts: 3, delayMs: 1 })).resolves.toBe('chunk');
    expect(load).toHaveBeenCalledTimes(3);
  });

  it('gives up and rejects with the last error once every attempt is exhausted', async () => {
    const failure = new TypeError('Failed to fetch dynamically imported module');
    const load = vi.fn(async () => {
      throw failure;
    });

    await expect(withImportRetry(load, { attempts: 3, delayMs: 1 })).rejects.toBe(failure);
    expect(load).toHaveBeenCalledTimes(3);
  });
});
