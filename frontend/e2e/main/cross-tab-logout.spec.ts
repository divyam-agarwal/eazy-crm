import { expect, expectAccessible, test, watchCsp } from '../fixtures';
import { newAccount, signupViaApi } from '../support/api';
import { signedInText, signOutThroughUi, signUpThroughUi } from '../support/ui';

test('signing out in one tab signs the other out, and the next user sees nothing of the last', async ({ context, page }) => {
  const first = newAccount('first');
  const second = newAccount('second');
  await signUpThroughUi(page, first);

  const other = await context.newPage();
  const otherCsp = watchCsp(other);
  await other.goto('/');
  await expect(other.getByText(signedInText(first.slug, first.email))).toBeVisible();
  // R28: every E2E page runs axe (spec §6.4) — the second tab's freshly booted, signed-in state.
  await expectAccessible(other);

  await signOutThroughUi(page);
  await expect(other.getByRole('heading', { name: 'Sign in to EasyCRM' })).toBeVisible();
  await expectAccessible(other);

  await signUpThroughUi(page, second);
  await expect(other.getByText(first.email)).toHaveCount(0);

  await other.reload();
  await expect(other.getByText(signedInText(second.slug, second.email))).toBeVisible();
  for (const tab of [page, other]) await expect(tab.getByText(first.email)).toHaveCount(0);
  await expectAccessible(page);
  await expectAccessible(other);
  expect(otherCsp).toEqual([]);
});

// Testing-4 / challenge #84: the dangerous case is a tab still SHOWING user 1 while the shared
// cookie jar switches to user 2 — no sign-out involved. Today that path is covered only by a unit
// test with a fake channel and a spy on clearQueryCache.
test('a tab showing one user reloads into the other when the cookie jar switches underneath it', async ({ context, page }) => {
  const first = newAccount('first');
  const second = newAccount('second');
  await signUpThroughUi(page, first);
  await expect(page.getByText(signedInText(first.slug, first.email))).toBeVisible();

  // Same cookie jar, no BroadcastChannel message: this is the "another tab/device signed in" case.
  // `context.request`, not the top-level `request` fixture — api.ts's signupViaApi docstring notes
  // the top-level fixture has its OWN cookie jar, separate from any page; only context.request
  // shares the browser context's jar, which is the whole point of this scenario. This also carries
  // `first`'s existing easycrm_rt cookie into the signup request, so the server-side revoke in
  // AuthController.signup (cookie.read(request).ifPresent(auth::logout)) genuinely fires here too —
  // matching a real second signup submitted from a tab that already has a live session.
  await signupViaApi(context.request, second);

  const other = await context.newPage();
  await other.goto('/'); // boots as `second` and broadcasts login
  await expect(other.getByText(signedInText(second.slug, second.email))).toBeVisible();
  await expectAccessible(other);

  // The first tab must notice the principal change and reload rather than keep rendering `first`.
  await expect(page.getByText(signedInText(second.slug, second.email))).toBeVisible();
  await expect(page.getByText(first.email)).toHaveCount(0);
  await expectAccessible(page);
});

// P14/Security-1: the Critical finding, in a real browser. A sign-out whose POST never lands must
// not leave another tab believing it is signed out while the cookie is still live.
test('a failed sign-out blocks the other tab and survives a reload', async ({ context, page }) => {
  const account = newAccount('owner');
  await signUpThroughUi(page, account);

  const other = await context.newPage();
  await other.goto('/');
  await expect(other.getByText(signedInText(account.slug, account.email))).toBeVisible();
  await expectAccessible(other);

  await context.route('**/api/v1/auth/logout', (route) => route.abort('failed'));
  await page.getByRole('button', { name: 'Sign out' }).click();

  // Not the login page: the blocking screen, in BOTH tabs.
  await expect(page.getByRole('heading', { name: /Sign-out did not complete/ })).toBeVisible();
  await expect(other.getByRole('heading', { name: /Sign-out did not complete/ })).toBeVisible();
  await expectAccessible(page);
  await expectAccessible(other);

  // And a reload must not sign the user back in — the marker outlives the tab.
  await other.reload();
  await expect(other.getByText(signedInText(account.slug, account.email))).toHaveCount(0);
  await expectAccessible(other);

  // Once the network returns, the retry completes and the login page finally appears.
  await context.unroute('**/api/v1/auth/logout');
  await expect(page.getByRole('heading', { name: 'Sign in to EasyCRM' })).toBeVisible({ timeout: 15_000 });
  await expectAccessible(page);
});
