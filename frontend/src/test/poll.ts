import { expect } from 'vitest';

/**
 * Final fix wave, item 2: a `setTimeout(N); expect(getValue()).toBe(expected)` pattern for a
 * NEGATIVE assertion ("this never happens") is a guess at "long enough" — a genuinely broken guard
 * that lands its violation at N+1ms passes, and a loaded CI runner can turn any fixed N into a false
 * pass. That is exactly the failure mode the two Criticals this fix wave closed were about (a
 * duplicated write nobody's test could catch), so the tests written to prove they stay closed must
 * not lean on the same kind of guess.
 *
 * Polls `getValue()` at `intervalMs` steps across a real-time window of `forMs`, asserting it still
 * equals `expected` at every sample — so a violation is caught at whichever tick it actually occurs,
 * not only if it happens to land before one arbitrarily chosen instant. A slower environment needs a
 * bigger `forMs`, not a lucky delay. Structural (repeated checks) rather than empirical (one
 * fixed-margin check) — the stronger of the two options available here (see Challenge #106/P11 for
 * the case where only the empirical option existed).
 */
export async function assertStaysAt<T>(
  getValue: () => T,
  expected: T,
  opts: { forMs?: number; intervalMs?: number } = {},
): Promise<void> {
  const { forMs = 300, intervalMs = 10 } = opts;
  const deadline = Date.now() + forMs;
  for (;;) {
    expect(getValue()).toBe(expected);
    if (Date.now() >= deadline) return;
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
}
