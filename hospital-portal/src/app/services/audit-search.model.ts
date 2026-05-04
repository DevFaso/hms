/**
 * MVP-8: Cross-tenant audit search response and filter models.
 * Mirrors the backend AuditEventLogResponseDTO and AuditSearchPageDTO.
 */
export interface AuditSearchRow {
  id: string;
  userName: string | null;
  hospitalName: string | null;
  roleName: string | null;
  eventType: string | null;
  eventDescription: string | null;
  details: string | null;
  eventTimestamp: string | null;
  ipAddress: string | null;
  status: string | null;
  resourceId: string | null;
  resourceName: string | null;
  entityType: string | null;
  actorType: string | null;
  actorLabel: string | null;
  impersonatorUserId: string | null;
  impersonatorUsername: string | null;
}

export interface AuditSearchPage {
  content: AuditSearchRow[];
  pageNumber: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
}

/**
 * MVP-8c — discriminator for cross-source aggregation. Mirrors the
 * backend `AuditSource` enum.
 */
export type AuditSource = 'SUPPORT' | 'FRONTEND' | 'PERMISSION_MATRIX';

/**
 * MVP-8c — common shape across the three audit sources. Mirrors
 * `AggregatedAuditEventDTO` on the backend.
 */
export interface AggregatedAuditEvent {
  source: AuditSource;
  id: string;
  eventType: string | null;
  actor: string | null;
  hospitalId: string | null;
  organizationId: string | null;
  status: string | null;
  timestamp: string | null;
  summary: string | null;
}

export interface AggregatedAuditPage {
  content: AggregatedAuditEvent[];
  pageNumber: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
}

export interface AggregatedAuditFilter {
  sources?: AuditSource[];
  fromDate?: string;
  toDate?: string;
  page?: number;
  size?: number;
}

export interface AuditSearchFilter {
  userId?: string;
  userName?: string;
  eventTypes?: string[];
  status?: string;
  hospitalId?: string;
  organizationId?: string;
  impersonatorUserId?: string;
  entityType?: string;
  resourceId?: string;
  fromDate?: string;
  toDate?: string;
  /**
   * MVP-9b: optional regulatory region scope. Joins through
   * assignment.hospital.organization.region on the backend so the
   * search is narrowed to events whose tenant carries the given
   * `OrganizationRegion` enum value (e.g. "EU", "BF", "ML_OAPI").
   */
  tenantRegion?: string;
  page?: number;
  size?: number;
}
