import type { components } from './schema';

type Schemas = components['schemas'];

export type AuthResponse = Schemas['AuthResponse'];
export type MeResponse = Schemas['MeResponse'];
export type LoginRequest = Schemas['LoginRequest'];
export type SignupRequest = Schemas['SignupRequest'];
export type SignupStatusResponse = Schemas['SignupStatusResponse'];
export type InvitationPreviewResponse = Schemas['InvitationPreviewResponse'];
export type AcceptInvitationRequest = Schemas['AcceptInvitationRequest'];
export type ApiErrorResponse = Schemas['ApiErrorResponse'];
