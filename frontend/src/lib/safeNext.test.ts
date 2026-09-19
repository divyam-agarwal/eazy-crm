import { describe, expect, it } from 'vitest';
import { safeNext } from './safeNext';

const ORIGIN = 'https://app.easycustomerrelationship.site';

describe('safeNext', () => {
  it.each(['/quotes', '/quotes?status=SENT', '/', '/invite/abc#top'])('accepts %s', (path) => {
    expect(safeNext(path, ORIGIN)).toBe(path);
  });

  it.each(['//evil.com', 'https://evil.com', '/\\evil.com', '', 'quotes', '/\t/evil.com', 'javascript:alert(1)', null, undefined])(
    'rejects %s',
    (raw) => {
      expect(safeNext(raw, ORIGIN)).toBeNull();
    },
  );
});
