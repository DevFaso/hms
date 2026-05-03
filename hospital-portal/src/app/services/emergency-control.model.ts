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
