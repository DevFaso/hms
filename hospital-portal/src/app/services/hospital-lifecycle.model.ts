/**
 * Hospital lifecycle states. Mirrors the backend
 * `HospitalLifecycleState` enum.
 */
export type HospitalLifecycleState =
  'ACTIVE' | 'SUSPENDED' | 'ARCHIVED' | 'PENDING_PURGE' | 'PURGED';

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

/**
 * The backend's TenantLifecycleActionRequestDTO: the date travels as
 * `purgeScheduledFor`, an ISO instant. Any other name is silently ignored and
 * the server falls back to its 30-day default.
 */
export interface HospitalLifecyclePurgeSchedule {
  purgeScheduledFor: string;
  reason: string;
}
