export class ApiHttpError extends Error {
  readonly status: number;
  readonly body: unknown;
  readonly headers: Headers;

  constructor(status: number, body: unknown, headers: Headers) {
    super(`API request failed with status ${status}`);
    this.name = 'ApiHttpError';
    this.status = status;
    this.body = body;
    this.headers = headers;
  }
}

interface FetchResult<T> {
  data?: T;
  error?: unknown;
  response: Response;
}

/** For hooks: a non-2xx (or a 2xx without a body where one is expected) becomes a thrown ApiHttpError. */
export function unwrap<T>(result: FetchResult<T>): T {
  if (result.response.ok && result.data !== undefined) return result.data;
  // A 2xx status reaching this throw means `data` was undefined on an ok response — i.e. unwrap()
  // was called on a body-less success (e.g. 204). That's the wrong helper for this case; ensureOk()
  // is. The resulting ApiHttpError carries a 2xx status, which reads as nonsense in a generic
  // logger — that mismatch IS the signal that the caller reached for the wrong helper, not that the
  // API misbehaved.
  throw new ApiHttpError(result.response.status, result.error, result.response.headers);
}

/** For body-less successes such as 204. */
export function ensureOk(result: { error?: unknown; response: Response }): void {
  if (!result.response.ok) {
    throw new ApiHttpError(result.response.status, result.error, result.response.headers);
  }
}

export type ApiFailure =
  | { kind: 'http'; status: number; body: unknown; headers: Headers }
  | { kind: 'aborted' }
  | { kind: 'network' };

// AbortController.abort() (no reason given) and the caller-signal path in authFetch.ts both throw a
// value named 'AbortError' — as either a DOMException or a plain Error-like object, depending on
// where it originated, so check `.name` rather than `instanceof`. AbortSignal.timeout()'s own
// abort reason is named 'TimeoutError', not 'AbortError', so a 15 s request timeout falls through
// to 'network' below without any special-casing here — that's deliberate (see errors.test.ts).
function isAbortError(error: unknown): boolean {
  return (
    typeof error === 'object' &&
    error !== null &&
    'name' in error &&
    (error as { name: unknown }).name === 'AbortError'
  );
}

export function toApiFailure(error: unknown): ApiFailure {
  if (error instanceof ApiHttpError) {
    return { kind: 'http', status: error.status, body: error.body, headers: error.headers };
  }
  if (isAbortError(error)) return { kind: 'aborted' };
  return { kind: 'network' };
}

/** Whole seconds, as RateLimitFilter emits them. Anything else is ignored. */
export function parseRetryAfter(value: string | null): number | undefined {
  if (value === null) return undefined;
  const trimmed = value.trim();
  if (!/^\d+$/.test(trimmed)) return undefined;
  return Number(trimmed);
}
