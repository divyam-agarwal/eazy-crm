import { expect, expectAccessible, test } from '../fixtures';
import { inviteViaApi, newAccount, signupViaApi } from '../support/api';
import { signedInText, signInThroughUi } from '../support/ui';

test('a signed-in owner opening an invite link can sign out and accept as the invitee', async ({ page, request }) => {
  const owner = newAccount('owner');
  const invitee = newAccount('invitee');
  const ownerToken = await signupViaApi(request, owner);
  const acceptUrl = await inviteViaApi(request, ownerToken, invitee.email);
  await signInThroughUi(page, owner);

  await page.goto(acceptUrl);
  await expect(
    page.getByText(`This invitation is for ${invitee.email} to join ${owner.businessName}. You're signed in as ${owner.email}.`),
  ).toBeVisible();
  await expectAccessible(page);

  await page.getByRole('button', { name: 'Sign out and accept' }).click();
  await page.getByLabel('Choose a password', { exact: true }).fill(invitee.password);
  await page.getByRole('button', { name: 'Join workspace' }).click();

  await expect(page.getByText(signedInText(owner.slug, invitee.email, 'Sales executive'))).toBeVisible();
});
