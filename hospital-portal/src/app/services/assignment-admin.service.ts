import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { PageResponse } from './maternity.service';

/*
 * Assignment administration — /assignments.
 * SecurityConfig hard-gates the whole controller to HOSPITAL_ADMIN/SUPER_ADMIN
 * at the URL layer (public GET/POST /assignments/public/** excepted); the
 * method @PreAuthorize lists match, and GET /minimal is SUPER_ADMIN-only.
 * ⚠ The backend applies NO tenant scoping: hospitalId on the list endpoint is
 * a caller-supplied filter, not a security boundary.
 * Responses are @JsonInclude(NON_NULL) — most fields may be absent.
 */

export interface AssignmentResponse {
  id: string;
  assignmentCode?: string;
  userId?: string;
  userEmail?: string;
  userName?: string;
  hospitalId?: string;
  hospitalName?: string;
  hospitalCode?: string;
  roleId?: string;
  roleName?: string;
  roleCode?: string;
  active: boolean;
  assignedAt?: string;
  createdAt?: string;
  updatedAt?: string;
  startDate?: string;
  confirmationSentAt?: string;
  confirmationVerifiedAt?: string;
  confirmationVerified: boolean;
  registeredByUserId?: string;
  registeredByUserName?: string;
  profileCompletionUrl?: string;
  assignerConfirmationUrl?: string;
  profileChecklist?: string[];
}

/** Provide exactly one of roleId/roleName and one of userId/userIdentifier. */
export interface AssignmentRequest {
  userId?: string;
  userIdentifier?: string;
  hospitalId?: string;
  roleId?: string;
  roleName?: string;
  active?: boolean;
  startDate?: string;
}

export interface AssignmentMultiRequest {
  userId?: string;
  userIdentifier?: string;
  roleId?: string;
  roleName?: string;
  hospitalIds?: string[];
  organizationIds?: string[];
  active?: boolean;
  startDate?: string;
  sendNotifications?: boolean;
  skipConflicts?: boolean;
}

export interface AssignmentFailure {
  hospitalId?: string;
  organizationId?: string;
  scopeLabel?: string;
  message?: string;
}

export interface AssignmentBatchResponse {
  requestedAssignments: number;
  createdAssignments: number;
  skippedAssignments: number;
  assignments: AssignmentResponse[];
  failures: AssignmentFailure[];
}

/** CSV goes in as a JSON string field — the endpoint is NOT multipart. */
export interface AssignmentBulkImportRequest {
  csvContent: string;
  delimiter?: string;
  defaultRoleId?: string;
  defaultRoleName?: string;
  defaultHospitalId?: string;
  defaultActive?: boolean;
  sendNotifications?: boolean;
  skipConflicts?: boolean;
}

export interface AssignmentBulkImportResult {
  rowNumber: number;
  identifier?: string;
  success: boolean;
  message?: string;
  assignmentId?: string;
  assignmentCode?: string;
  hospitalId?: string;
  hospitalCode?: string;
  roleCode?: string;
}

export interface AssignmentBulkImportResponse {
  processed: number;
  created: number;
  skipped: number;
  failed: number;
  results: AssignmentBulkImportResult[];
}

export interface AssignmentListFilters {
  hospitalId?: string;
  active?: boolean;
  search?: string;
  assignmentCode?: string;
}

@Injectable({ providedIn: 'root' })
export class AssignmentAdminService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/assignments';

  /** Server-side page; the backend applies no sort, so row order is unstable. */
  list(
    page: number,
    size: number,
    f: AssignmentListFilters,
  ): Observable<PageResponse<AssignmentResponse>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (f.hospitalId) params = params.set('hospitalId', f.hospitalId);
    if (f.active !== undefined) params = params.set('active', f.active);
    if (f.search) params = params.set('search', f.search);
    if (f.assignmentCode) params = params.set('assignmentCode', f.assignmentCode);
    return this.http.get<PageResponse<AssignmentResponse>>(this.baseUrl, { params });
  }

  create(req: AssignmentRequest): Observable<AssignmentResponse> {
    return this.http.post<AssignmentResponse>(this.baseUrl, req);
  }

  createMultiScope(req: AssignmentMultiRequest): Observable<AssignmentBatchResponse> {
    return this.http.post<AssignmentBatchResponse>(`${this.baseUrl}/multi-scope`, req);
  }

  update(id: string, req: AssignmentRequest): Observable<AssignmentResponse> {
    return this.http.put<AssignmentResponse>(`${this.baseUrl}/${id}`, req);
  }

  /** ⚠ Rotates BOTH codes and RESETS confirmationVerifiedAt to null. */
  regenerateCode(id: string, resendNotifications: boolean): Observable<AssignmentResponse> {
    const params = new HttpParams().set('resendNotifications', resendNotifications);
    return this.http.post<AssignmentResponse>(`${this.baseUrl}/${id}/regenerate-code`, null, {
      params,
    });
  }

  resendNotification(id: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/${id}/resend-notification`, null);
  }

  /** Soft path — sets active=false, idempotent. */
  deactivate(id: string): Observable<void> {
    return this.http.patch<void>(`${this.baseUrl}/${id}/deactivate`, null);
  }

  /** Hard delete; 409 when a Staff record still references the assignment. */
  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  bulkImport(req: AssignmentBulkImportRequest): Observable<AssignmentBulkImportResponse> {
    return this.http.post<AssignmentBulkImportResponse>(`${this.baseUrl}/bulk-import`, req);
  }
}
