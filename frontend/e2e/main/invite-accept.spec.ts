import { expect, expectAccessible, test } from '../fixtures';
import { inviteViaApi, newAccount, signupViaApi } from '../support/api';
import { signedInText } from '../support/ui';

test('an invitee opens the backend-minted accept link and joins', async ({ page, request, baseURL }) => {
  const owner = newAccount('owner');
  const invitee = newAccount('invitee');
  const ownerToken = await signupViaApi(request, owner);
  const acceptUrl = await inviteViaApi(request, ownerToken, invitee.email);
  expect(new URL(acceptUrl).origin).toBe(new URL(String(baseURL)).origin);

  await page.goto(acceptUrl);
  await expect(page.getByText(`Join ${owner.businessName} as Sales executive`)).toBeVisible();
  await expectAccessible(page);
  await expect(page.getByLabel('Email', { exact: true })).toHaveValue(invitee.email);

  await page.getByLabel('Choose a password', { exact: true }).fill(invitee.password);
  await page.getByRole('button', { name: 'Join workspace' }).click();
  await expect(page.getByText(signedInText(owner.slug, invitee.email, 'Sales executive'))).toBeVisible();

  await page.goBack();
  expect(page.url()).not.toContain('/invite/');
});
