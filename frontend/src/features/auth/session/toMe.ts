import type { AuthResponse } from '@/api/types';
import { ROLES, type Me, type Role } from '@/session/types';

export function isRole(value: string): value is Role {
  return (ROLES as readonly string[]).includes(value);
}

/**
 * The one place a session response is narrowed (spec §4.4: no scattered assertions).
 *
 * <p>Architecture-5: this THROWS on a role the client does not know — the contract types `role` as
 * a plain string, and ROADMAP item 4a adds a platform-admin role on the backend. Every caller must
 * catch: an uncaught throw here leaves boot's status at `booting` forever (endless splash) and
 * turns a sign-in into a misleading "Can't reach EasyCRM". See `boot.ts` and the coordinator.
 */
export function toMe(response: AuthResponse): Me {
  if (!isRole(response.role)) throw new Error(`unknown role from server: ${response.role}`);
  return {
    userId: response.userId,
    tenantId: response.tenantId,
    tenantSlug: response.tenantSlug,
    email: response.email,
    role: response.role,
  };
}
