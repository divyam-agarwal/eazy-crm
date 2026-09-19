// Compile-time proof that openapiHttp is typed. If typing silently degraded to `any`, the
// expect-error directives below would become unused and `tsc` would fail. Never executed.
import { http } from './openapiHttp';

// @ts-expect-error — not a path in the contract
http.get('/api/v1/not-a-route', ({ response }) => response(200).empty());

http.post('/api/v1/auth/refresh', ({ response }) =>
  // @ts-expect-error — accessToken must be a string
  response(200).json({ accessToken: 42, userId: 'u', tenantId: 't', tenantSlug: 's', email: 'e', role: 'OWNER' }),
);
