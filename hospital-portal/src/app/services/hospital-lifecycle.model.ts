/**
 * Hospital lifecycle states. Mirrors the backend
 * `HospitalLifecycleState` enum.
 */
export type HospitalLifecycleState =
  | 'ACTIVE'
  | 'SUSPENDED'
  | 'ARCHIVED'
  | 'PURGE_SCHEDULED'
  | 'PURGED';

/**
 * MVP-c batch — lifecycle snapshot for a hospital. Mirrors the
 * backend `HospitalLifecycleResponseDTO`.
 */
export interface HospitalLifecycleResponse {
  hospitalId: string;
  hospitalName: string;
  hospitalCode: string;
  state: HospitalLifecycleState;
  suspendedAt: string | null;
  suspendedBy: string | null;
  suspensionReason: string | null;
  archivedAt: string | null;
  archivedBy: string | null;
  purgeScheduledFor: string | null;
  purgedAt: string | null;
  updatedAt: string;
}

export interface HospitalLifecycleReason {
  reason: string;
}

export interface HospitalLifecyclePurgeSchedule {
  scheduledFor: string;
  reason: string;
}
