import { describe, expect, it } from 'vitest';
import { suggestSlug } from './slug';

describe('suggestSlug', () => {
  it.each([
    ['Ravi Traders & Sons', 'ravi-traders-sons'],
    ['  A.B.C.  ', 'a-b-c'],
    ['Café Müller', 'cafe-muller'],
    ['Sharma शर्मा Traders', 'sharma-traders'],
    ['शर्मा ट्रेडर्स', ''],
    ['AB', ''],
    ['', ''],
  ])('%s → %s', (input, expected) => {
    expect(suggestSlug(input)).toBe(expected);
  });

  it('caps at 64 characters without a trailing hyphen, matching [a-z0-9-]{3,64}', () => {
    const slug = suggestSlug(`${'a'.repeat(63)} b ${'c'.repeat(10)}`);
    expect(slug.length).toBeLessThanOrEqual(64);
    expect(slug).toMatch(/^[a-z0-9-]{3,64}$/);
    expect(slug.endsWith('-')).toBe(false);
  });
});
