import { createOpenApiHttp } from 'openapi-msw';
import type { paths } from '@/api/schema';

/**
 * Handlers typed from the generated contract (spec §6.3): a mock whose path, status or body the
 * contract does not allow fails `tsc`. Base `*` matches any origin.
 */
export const http = createOpenApiHttp<paths>({ baseUrl: '*' });
