export const ROLES = ['OWNER', 'SALES_MANAGER', 'SALES_EXEC'] as const;
export type Role = (typeof ROLES)[number];

export interface Me {
  userId: string;
  tenantId: string;
  tenantSlug: string;
  email: string;
  role: Role;
}

/** Spec §4.4's four statuses plus `signing-out` (plan decision P5). */
export type SessionStatus = 'booting' | 'authenticated' | 'anonymous' | 'unreachable' | 'signing-out';
