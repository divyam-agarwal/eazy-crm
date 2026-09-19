// Module-scoped, never persisted (spec §4.4, F0-13): nothing survives in storage for XSS to read later.
let token: string | null = null;

export function getAccessToken(): string | null {
  return token;
}

export function setAccessToken(value: string): void {
  token = value;
}

export function clearAccessToken(): void {
  token = null;
}
