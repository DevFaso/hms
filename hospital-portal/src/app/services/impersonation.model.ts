export interface ImpersonationStartRequest {
  targetUserId: string;
  reason: string;
}

export interface ImpersonationStartResponse {
  accessToken: string;
  expiresAt: string;
  impersonatorUserId: string;
  impersonatorUsername: string;
  targetUserId: string;
  targetUsername: string;
}

export interface ImpersonationActiveResponse {
  impersonating: boolean;
  impersonatorUserId?: string | null;
  impersonatorUsername?: string | null;
  targetUserId?: string | null;
  targetUsername?: string | null;
}
