import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { server } from './msw';

describe('test environment', () => {
  // authFetch (Task 3) builds every Request with AbortSignal.timeout. Under jsdom, a DOM AbortSignal
  // handed to Node's Request can be rejected ("Expected signal to be an instance of AbortSignal").
  // This probe fails loudly here instead of inside thirty component tests later.
  it('lets a Request carry an AbortSignal through MSW', async () => {
    server.use(http.get('*/probe', () => HttpResponse.json({ ok: true })));
    const response = await fetch(
      new Request(`${location.origin}/probe`, { signal: AbortSignal.timeout(1_000) }),
    );
    expect(await response.json()).toEqual({ ok: true });
  });

  // Architecture-4: authFetch combines the caller's signal with its own timeout via
  // AbortSignal.any. Node 24 and current browsers have it; jsdom's DOM shim may not, and the
  // failure would otherwise surface as "AbortSignal.any is not a function" inside authFetch.
  it('supports AbortSignal.any, linking a caller signal to a timeout', () => {
    const caller = new AbortController();
    const combined = AbortSignal.any([caller.signal, AbortSignal.timeout(60_000)]);
    expect(combined.aborted).toBe(false);
    caller.abort();
    expect(combined.aborted).toBe(true);
  });
});
