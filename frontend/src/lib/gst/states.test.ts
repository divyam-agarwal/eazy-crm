import { describe, expect, it } from 'vitest';
import { GST_STATES } from './states';

describe('GST_STATES', () => {
  it('matches the backend exactly: 01–38, 97 and 99 (platform-primitives StateCode)', () => {
    const expected = [...Array.from({ length: 38 }, (_, i) => String(i + 1).padStart(2, '0')), '97', '99'];
    expect(GST_STATES.map((s) => s.code)).toEqual(expected);
    expect(GST_STATES.every((s) => s.name.length > 0)).toBe(true);
  });
});
