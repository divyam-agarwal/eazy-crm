import { expect, type Page } from '@playwright/test';
import { expectAccessible } from '../fixtures';
import type { Account } from './api';

export const signedInText = (slug: string, email: string, roleLabel = 'Owner') => `Signed in to ${slug} as ${email} (${roleLabel})`;

export async function signUpThroughUi(page: Page, account: Account): Promise<void> {
  await page.goto('/signup');
  await expect(page.getByRole('heading', { name: 'Create your EasyCRM workspace' })).toBeVisible();
  await expectAccessible(page);
  await page.getByLabel('Business name', { exact: true }).fill(account.businessName);
  await page.getByLabel('Workspace name', { exact: true }).fill(account.slug);
  await page.getByLabel('State', { exact: true }).selectOption('27');
  await page.getByLabel('Email', { exact: true }).fill(account.email);
  await page.getByLabel('Password', { exact: true }).fill(account.password);
  await page.getByRole('button', { name: 'Create workspace' }).click();
  await expect(page.getByText(signedInText(account.slug, account.email))).toBeVisible();
}

export async function signInThroughUi(page: Page, account: Account, roleLabel = 'Owner'): Promise<void> {
  await page.goto('/login');
  await expect(page.getByRole('heading', { name: 'Sign in to EasyCRM' })).toBeVisible();
  await page.getByLabel('Workspace', { exact: true }).fill(account.slug);
  await page.getByLabel('Email', { exact: true }).fill(account.email);
  await page.getByLabel('Password', { exact: true }).fill(account.password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByText(signedInText(account.slug, account.email, roleLabel))).toBeVisible();
}

export async function signOutThroughUi(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('heading', { name: 'Sign in to EasyCRM' })).toBeVisible();
}
