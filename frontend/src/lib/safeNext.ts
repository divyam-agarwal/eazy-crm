/**
 * Spec §5.1: safe = starts with a single "/", not "//", no "\", and resolves same-origin. The URL
 * parse catches what the string checks cannot (a tab inside "/\t/evil.com" is stripped to "//").
 */
export function safeNext(raw: string | null | undefined, origin: string = globalThis.location.origin): string | null {
  if (!raw || !raw.startsWith('/') || raw.startsWith('//') || raw.includes('\\')) return null;
  try {
    const url = new URL(raw, origin);
    if (url.origin !== origin) return null;
    return url.pathname + url.search + url.hash;
  } catch {
    return null;
  }
}
