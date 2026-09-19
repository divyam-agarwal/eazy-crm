import AxeBuilder from '@axe-core/playwright';
import { test as base, expect, type Page } from '@playwright/test';

/** Collects CSP violations Chromium reports on the console for this page. */
export function watchCsp(page: Page): string[] {
  const violations: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error' && /Content Security Policy/i.test(message.text())) violations.push(message.text());
  });
  return violations;
}

/** Spec §6.4: every E2E page runs axe; any violation fails the test. */
export async function expectAccessible(page: Page): Promise<void> {
  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations.map((v) => `${v.id} (${v.nodes.length}): ${v.help}`)).toEqual([]);
}

export const test = base.extend<{ cspGuard: void }>({
  cspGuard: [
    async ({ page }, use) => {
      const violations = watchCsp(page);
      await use();
      expect(violations, 'CSP violations on this page').toEqual([]);
    },
    { auto: true },
  ],
});

export { expect };
