import { expect, expectAccessible, test } from '../fixtures';

// The only real-backend check that an unknown token renders the single invalid state (challenge #55).
test('an invalid invite token shows the invalid state', async ({ page }) => {
  await page.goto('/invite/not-a-real-token');
  await expect(page.getByText('This invitation link is invalid or has expired.')).toBeVisible();
  await expectAccessible(page);
});
