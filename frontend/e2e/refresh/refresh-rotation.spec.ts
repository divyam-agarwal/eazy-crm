import { expect, expectAccessible, test, watchCsp } from '../fixtures';
import { newAccount } from '../support/api';
import { signedInText, signUpThroughUi } from '../support/ui';

// This project runs against a backend started with --easycrm.jwt.access-ttl-seconds=5 (see
// ../playwright.config.ts), so a genuine access-token expiry — not a manufactured one — is only a
// few seconds away. RefreshTokenService.GRACE (30s) is unaffected by that flag: it is a separate,
// backend-side constant that governs how long a just-rotated (pre-rotation) refresh cookie is still
// honoured, which is what test 2 below exercises.

// Testing-4/#86: refreshCoordinator.ts documents that Web Locks serialization is load-bearing —
// "two concurrent refreshes of one cookie both succeed... whichever Set-Cookie lands last-but-not-
// latest leaves every tab holding a dead cookie" — and start.ts wires BOTH boot() and the 401 path
// through the SAME navigator.locks name ('easycrm-refresh'). Per the Web Locks spec that lock
// manager is shared across every same-origin tab, not just within one JS realm, but that claim is
// exactly what a unit test (a fake channel, an in-memory lock) cannot prove — only a real browser
// with two real tabs can. P11: we prove serialization by asserting at most one refresh is ever IN
// FLIGHT at once, not by controlling which Set-Cookie lands last (Playwright's route.fetch() shares
// the cookie jar with the page, so landing order cannot be forced).
test('two tabs booting at once never send two /auth/refresh requests concurrently', async ({ context, page }) => {
  const account = newAccount('racer');
  await signUpThroughUi(page, account);
  await expect(page.getByText(signedInText(account.slug, account.email))).toBeVisible();

  let inFlight = 0;
  let maxInFlight = 0;
  let totalCalls = 0;
  await context.route('**/api/v1/auth/refresh', async (route) => {
    totalCalls += 1;
    inFlight += 1;
    maxInFlight = Math.max(maxInFlight, inFlight);
    const response = await route.fetch();
    inFlight -= 1;
    await route.fulfill({ response });
  });

  // Both tabs start a fresh boot at the same time: `page` via reload (its in-memory access token is
  // gone, so boot() re-enters 'booting' and unconditionally refreshes), `other` via first navigation
  // (same boot path). Both acquire the SAME named Web Lock before calling the network.
  const other = await context.newPage();
  // `other` is a manually created page, not the `page` fixture, so the auto `cspGuard` fixture (which
  // only wraps `page`) never watches it — without this it would be silently unchecked.
  const otherCsp = watchCsp(other);
  await Promise.all([page.reload(), other.goto('/')]);

  await expect(page.getByText(signedInText(account.slug, account.email))).toBeVisible();
  // Task 16 (F0b, carried from Task 15): `other` is a brand-new browser context — its first paint
  // pays a cold V8-compile/JIT cost the `page` fixture's page never sees again after signup. One
  // run of this exact assertion took 6.1s against the 5000ms default; every later local run (8+)
  // was 1.2-1.5s. CI's first run of the day is always a cold start, so this is likelier to recur
  // there than it ever did locally — an explicit, generous timeout on just this one assertion
  // absorbs it without loosening the suite's default elsewhere.
  await expect(other.getByText(signedInText(account.slug, account.email))).toBeVisible({ timeout: 15_000 });
  await expectAccessible(page);
  await expectAccessible(other);
  expect(otherCsp).toEqual([]);

  // Both tabs really did attempt to refresh (boot() has no same-tab short-circuit, unlike the 401
  // coordinator) — otherwise maxInFlight <= 1 would be true for the uninteresting reason that only
  // one request ever happened at all.
  expect(totalCalls).toBeGreaterThanOrEqual(2);
  expect(maxInFlight).toBeLessThanOrEqual(1);
});

// P11: test 7 (this test) restores the pre-rotation cookie EXPLICITLY, rather than hoping two racing
// requests land in the right order, so the 30s lost-ACK grace path (RefreshTokenService.rotate,
// spec §3.3) is genuinely exercised: the presented cookie must be the one already revoked by an
// earlier, successful rotation, with its (unused) successor still live.
test('a tab that never saw the last Set-Cookie is still granted a session through the 30s grace window', async ({
  context,
  page,
}) => {
  const account = newAccount('grace');
  await signUpThroughUi(page, account);
  await expect(page.getByText(signedInText(account.slug, account.email))).toBeVisible();

  const cookiesAtSignup = await context.cookies();
  const preRotation = cookiesAtSignup.find((c) => c.name === 'easycrm_rt');
  if (!preRotation) throw new Error('signup did not set the easycrm_rt cookie');

  // First rotation: a real page-level fetch, so the browser applies the Set-Cookie to the context's
  // jar exactly as it would for any other authenticated request. This revokes preRotation and mints a
  // live, still-unused successor.
  const firstStatus = await page.evaluate(async () => {
    const res = await fetch('/api/v1/auth/refresh', { method: 'POST', headers: { 'X-EasyCRM-Client': 'web' } });
    return res.status;
  });
  expect(firstStatus).toBe(200);

  // Simulate the lost-ACK case: this tab never actually saw that Set-Cookie (a dropped response,
  // a killed tab before the header was applied — spec §3.3) and is still holding the old cookie.
  await context.addCookies([preRotation]);

  // Known stopping point (task-14 brief): it is NOT established that Playwright exposes the
  // automatically-attached Cookie header via allHeaders(). If it is missing here, this test must
  // fail loudly rather than have its assertion removed or weakened.
  let graceRequestCookie: string | undefined;
  await context.route('**/api/v1/auth/refresh', async (route) => {
    const headers = await route.request().allHeaders();
    graceRequestCookie = headers['cookie'];
    await route.continue();
  });

  const secondStatus = await page.evaluate(async () => {
    const res = await fetch('/api/v1/auth/refresh', { method: 'POST', headers: { 'X-EasyCRM-Client': 'web' } });
    return res.status;
  });

  expect(graceRequestCookie, 'allHeaders() did not expose the Cookie header on the intercepted request').toBeDefined();
  expect(graceRequestCookie).toContain(`easycrm_rt=${preRotation.value}`);
  expect(secondStatus, 'the 30s grace window must accept a genuinely stale, pre-rotation cookie once').toBe(200);
});
