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
  page?: number;
  size?: number;
}
