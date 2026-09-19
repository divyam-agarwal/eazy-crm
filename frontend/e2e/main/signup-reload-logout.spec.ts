import { expect, expectAccessible, test } from '../fixtures';
import { newAccount } from '../support/api';
import { signedInText, signOutThroughUi, signUpThroughUi } from '../support/ui';

test('signup, survive a reload, sign out, and sign back in with the remembered workspace', async ({ page }) => {
  const account = newAccount('owner');

  await signUpThroughUi(page, account);
  // A11y-1 (Task 8 Step 4): the splash must be gone once React has rendered.
  await expect(page.locator('#splash')).toBeHidden();
  await expectAccessible(page);

  await page.reload();
  await expect(page.getByText(signedInText(account.slug, account.email))).toBeVisible();

  await signOutThroughUi(page);
  await expect(page.getByLabel('Workspace', { exact: true })).toHaveValue(account.slug);
  await expectAccessible(page);

  // A11y-2: axe has never run on a page in its ERROR state, so the colour-contrast rule has never
  // seen the alert or a field error — the exact colours the review found at ~3.99:1. One wrong
  // password fixes that, and costs one request.
  await page.getByLabel('Email', { exact: true }).fill(account.email);
  await page.getByLabel('Password', { exact: true }).fill('wrong-password');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('alert')).toContainText('incorrect');
  await expect(page.getByRole('alert')).toBeFocused(); // A11y-1: the message is not just announced
  await expectAccessible(page);

  await page.getByLabel('Email', { exact: true }).fill(account.email);
  await page.getByLabel('Password', { exact: true }).fill(account.password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByText(signedInText(account.slug, account.email))).toBeVisible();
});
