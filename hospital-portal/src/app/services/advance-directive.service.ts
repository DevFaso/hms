import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/**
 * Advance directives (P2 #13).
 *
 * Backend CRUD shipped in PR #456 (tenancy hardened in #463) with zero portal
 * callers — the storyboard banner and record-sharing viewer could READ
 * directives, but no clinician could record or revoke one from any UI.
 */

export type AdvanceDirectiveType =
  | 'LIVING_WILL'
  | 'DURABLE_POWER_OF_ATTORNEY'
  | 'DO_NOT_RESUSCITATE'
  | 'PHYSICIAN_ORDERS_FOR_LIFE_SUSTAINING_TREATMENT'
  | 'OTHER';

export type AdvanceDirectiveStatus = 'ACTIVE' | 'REVOKED' | 'EXPIRED' | 'PENDING';

export interface AdvanceDirectiveRequest {
  directiveType: AdvanceDirectiveType;
  /** Defaults to ACTIVE on create. REVOKED should go through revoke(), which also stamps lastReviewedAt. */
  status?: AdvanceDirectiveStatus;
  description?: string;
  effectiveDate?: string;
  expirationDate?: string;
  witnessName?: string;
  physicianName?: string;
  documentLocation?: string;
  sourceSystem?: string;
}

export interface AdvanceDirectiveResponse {
  id: string;
  patientId: string;
  hospitalId: string;
  hospitalName?: string;
  directiveType: AdvanceDirectiveType;
  status: AdvanceDirectiveStatus;
  description?: string;
  effectiveDate?: string;
  expirationDate?: string;
  witnessName?: string;
  physicianName?: string;
  documentLocation?: string;
  sourceSystem?: string;
  /** Stamped by revoke — response-only. */
  lastReviewedAt?: string;
}

@Injectable({ providedIn: 'root' })
export class AdvanceDirectiveService {
  private readonly http = inject(HttpClient);

  listForPatient(patientId: string): Observable<AdvanceDirectiveResponse[]> {
    return this.http.get<AdvanceDirectiveResponse[]>(`/advance-directives/patient/${patientId}`);
  }

  create(
    patientId: string,
    request: AdvanceDirectiveRequest,
  ): Observable<AdvanceDirectiveResponse> {
    return this.http.post<AdvanceDirectiveResponse>(
      `/advance-directives/patient/${patientId}`,
      request,
    );
  }

  update(id: string, request: AdvanceDirectiveRequest): Observable<AdvanceDirectiveResponse> {
    return this.http.put<AdvanceDirectiveResponse>(`/advance-directives/${id}`, request);
  }

  /**
   * There is deliberately no delete — a directive that was once in force is
   * part of the record. Revoke sets status = REVOKED and stamps lastReviewedAt.
   */
  revoke(id: string): Observable<AdvanceDirectiveResponse> {
    return this.http.put<AdvanceDirectiveResponse>(`/advance-directives/${id}/revoke`, {});
  }
}
