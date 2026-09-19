import { expect, expectAccessible, test, watchCsp } from '../fixtures';

// Non-vacuity for the two checks every other test relies on.
test('the axe helper fails on a real violation', async ({ page }) => {
  await page.setContent('<main><img src="data:image/gif;base64,R0lGODlhAQABAAAAACw="></main>');
  await expect(expectAccessible(page)).rejects.toThrow();
});

test('vite preview sends the CSP, and the watcher sees an inline-script violation', async ({ browser, baseURL }) => {
  const probe = await browser.newPage({ baseURL: String(baseURL) });
  const violations = watchCsp(probe);
  const response = await probe.goto('/login');
  expect(response?.headers()['content-security-policy']).toContain("script-src 'self'");

  await probe.addScriptTag({ content: 'window.__cspProbe = 1;' }).catch(() => undefined);

  await expect.poll(() => violations.length).toBeGreaterThan(0);
  await probe.close();
});
