import createClient from 'openapi-fetch';
import { createAuthFetch, createBareFetch, type FetchLike } from './authFetch';
import type { paths } from './schema';

// An absolute base: openapi-fetch builds `new Request(url)`, which needs one outside a browser.
const origin = (): string => globalThis.location?.origin ?? 'http://localhost';

/** THE client every feature uses (spec §4.3). Nothing outside src/api may call fetch directly. */
export function createApiClient(fetchImpl?: FetchLike) {
  return createClient<paths>({ baseUrl: origin(), fetch: createAuthFetch({ fetchImpl }) });
}

/** Refresh and logout only — see createBareFetch. */
export function createBareClient(fetchImpl?: FetchLike) {
  return createClient<paths>({ baseUrl: origin(), fetch: createBareFetch({ fetchImpl }) });
}

export type ApiClient = ReturnType<typeof createApiClient>;

export const api = createApiClient();
export const bareApi = createBareClient();
