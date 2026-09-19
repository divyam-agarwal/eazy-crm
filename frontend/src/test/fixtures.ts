import type { AuthResponse } from '@/api/types';

export const ownerSession: AuthResponse = {
  accessToken: 'owner-access-1',
  userId: '11111111-1111-4111-8111-111111111111',
  tenantId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  tenantSlug: 'ravi-traders',
  email: 'ravi@shop.in',
  role: 'OWNER',
};

export const inviteeSession: AuthResponse = {
  accessToken: 'invitee-access-1',
  userId: '22222222-2222-4222-8222-222222222222',
  tenantId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  tenantSlug: 'ravi-traders',
  email: 'asha@shop.in',
  role: 'SALES_EXEC',
};

export function errorBody(
  code: string,
  message: string,
  extra: { fields?: Record<string, string>; fieldCodes?: Record<string, string> } = {},
) {
  return { error: { code, message, ...extra } };
}
