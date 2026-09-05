/**
 * MVP-7: Emergency global control request / response shapes.
 */
export interface EmergencyForceLogoutRequest {
  reason: string;
}

export interface EmergencyKillFeatureRequest {
  flagKey: string;
  reason: string;
}

export interface EmergencyForceMfaRequest {
  userIds?: string[];
  /** Narrow the reset to users with an active assignment at this hospital. */
  hospitalId?: string;
  /** Required by the backend when userIds is empty: an explicit platform-wide reset. */
  resetAll?: boolean;
  reason: string;
}

export interface EmergencyBroadcastRequest {
  message: string;
  severity?: 'INFO' | 'WARN' | 'CRITICAL';
}

export interface EmergencyActionResponse {
  action: string;
  takenAt: string;
  actorUsername: string | null;
  affectedRows: number;
  message: string;
}
