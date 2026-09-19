/** TanStack Query key factory for the auth feature (Tasks 10-12). */
export const authKeys = {
  all: ['auth'] as const,
  signupStatus: () => [...authKeys.all, 'signup-status'] as const,
  invitation: (token: string) => [...authKeys.all, 'invitation', token] as const,
};
