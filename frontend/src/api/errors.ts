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
  | { kind: 'network' };

export function toApiFailure(error: unknown): ApiFailure {
  if (error instanceof ApiHttpError) {
    return { kind: 'http', status: error.status, body: error.body, headers: error.headers };
  }
  return { kind: 'network' };
}

/** Whole seconds, as RateLimitFilter emits them. Anything else is ignored. */
export function parseRetryAfter(value: string | null): number | undefined {
  if (value === null) return undefined;
  const trimmed = value.trim();
  if (!/^\d+$/.test(trimmed)) return undefined;
  return Number(trimmed);
}
