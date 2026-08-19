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
  /**
   * MVP-4b — backend ISO-8601 expiry timestamp for the active
   * impersonation token. Optional because the legacy /active endpoint
   * returns a boolean-only payload before MVP-4b backend change lands;
   * the frontend keeps the start-time expiry locally if this is null.
   */
  expiresAt?: string | null;
}
