import { expect, type APIRequestContext } from '@playwright/test';

export interface Account {
  slug: string;
  businessName: string;
  email: string;
  password: string;
}

export function newAccount(label: string): Account {
  const id = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
  return {
    slug: `e2e-${label}-${id}`.slice(0, 64),
    businessName: `E2E ${label} ${id}`,
    email: `${label}-${id}@e2e.test`,
    password: 'correct-horse-9',
  };
}

/** Signs up through the API (its own cookie jar, separate from any page). Returns the access token. */
export async function signupViaApi(request: APIRequestContext, account: Account): Promise<string> {
  const response = await request.post('/api/v1/auth/signup', {
    data: { slug: account.slug, businessName: account.businessName, stateCode: '27', email: account.email, password: account.password },
  });
  expect(response.status(), await response.text()).toBe(201);
  return (await response.json()).accessToken as string;
}

/** Returns the backend-minted acceptUrl. */
export async function inviteViaApi(request: APIRequestContext, ownerToken: string, email: string, role = 'SALES_EXEC'): Promise<string> {
  const response = await request.post('/api/v1/invitations', {
    headers: { Authorization: `Bearer ${ownerToken}` },
    data: { email, role },
  });
  expect(response.ok(), await response.text()).toBe(true);
  return (await response.json()).acceptUrl as string;
}
