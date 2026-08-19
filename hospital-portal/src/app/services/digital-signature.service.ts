import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/*
 * Digital signatures — /signatures. Bare DTOs, no wrapper, no paging.
 * Method @PreAuthorize is the only gate (no SecurityConfig matcher):
 *  - sign: DOCTOR/NURSE/MIDWIFE/LAB_SCIENTIST/PHARMACIST
 *  - verify + report reads + get-by-id: those five + HOSPITAL_ADMIN + RECEPTIONIST
 *  - revoke + audit-trail: DOCTOR/HOSPITAL_ADMIN/SUPER_ADMIN
 *  - /all: HOSPITAL_ADMIN/SUPER_ADMIN (unscoped findAll — cross-hospital)
 * Signing twice for the same (report, staff) is a 400 ("revoke first").
 * Verify requires reportType+reportId even when signatureId is given, matches
 * by SHA-256 of the exact original signatureValue, and returns 200 with
 * isValid=false (not 404) when no match.
 */

export type SignatureType =
  | 'DISCHARGE_SUMMARY'
  | 'LAB_RESULT'
  | 'IMAGING_REPORT'
  | 'OPERATIVE_NOTE'
  | 'CONSULTATION_NOTE'
  | 'PROGRESS_NOTE'
  | 'PROCEDURE_REPORT'
  | 'PATHOLOGY_REPORT'
  | 'ED_NOTE'
  | 'MEDICATION_ORDER'
  | 'CARE_PLAN'
  | 'GENERIC_REPORT';
export const SIGNATURE_TYPES: SignatureType[] = [
  'DISCHARGE_SUMMARY',
  'LAB_RESULT',
  'IMAGING_REPORT',
  'OPERATIVE_NOTE',
  'CONSULTATION_NOTE',
  'PROGRESS_NOTE',
  'PROCEDURE_REPORT',
  'PATHOLOGY_REPORT',
  'ED_NOTE',
  'MEDICATION_ORDER',
  'CARE_PLAN',
  'GENERIC_REPORT',
];

export type SignatureStatus = 'PENDING' | 'SIGNED' | 'REVOKED' | 'EXPIRED' | 'INVALID';

export interface SignatureResponse {
  id: string;
  reportType: SignatureType;
  reportId: string;
  signedByStaffId: string;
  signedByName: string;
  hospitalId: string;
  hospitalName: string;
  signatureValue: string;
  signatureDateTime: string;
  status: SignatureStatus;
  signatureHash?: string;
  signatureNotes?: string;
  revocationReason?: string;
  revokedAt?: string;
  revokedByDisplay?: string;
  expiresAt?: string;
  verificationCount?: number;
  lastVerifiedAt?: string;
  isValid?: boolean;
  createdAt?: string;
}

export interface SignatureRequest {
  reportType: SignatureType;
  reportId: string;
  signedByStaffId: string;
  hospitalId: string;
  signatureValue: string;
  signatureNotes?: string;
  expiresAt?: string;
}

export interface SignatureVerificationRequest {
  /** Required by the backend even when signatureId is provided. */
  reportType: SignatureType;
  reportId: string;
  signatureValue: string;
  signatureId?: string;
}

export interface SignatureVerificationResponse {
  isValid: boolean;
  signatureId?: string;
  message?: string;
  signedByName?: string;
  signatureDateTime?: string;
  status?: string;
  invalidReason?: string;
  verificationCount?: number;
  verifiedAt?: string;
}

export interface SignatureAuditEntry {
  action: string;
  performedByUserId?: string;
  performedByDisplay?: string;
  performedAt: string;
  details?: string;
  ipAddress?: string;
}

@Injectable({ providedIn: 'root' })
export class DigitalSignatureService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/signatures';

  sign(req: SignatureRequest): Observable<SignatureResponse> {
    return this.http.post<SignatureResponse>(`${this.baseUrl}/sign`, req);
  }

  verify(req: SignatureVerificationRequest): Observable<SignatureVerificationResponse> {
    return this.http.post<SignatureVerificationResponse>(`${this.baseUrl}/verify`, req);
  }

  revoke(signatureId: string, revocationReason: string): Observable<SignatureResponse> {
    return this.http.post<SignatureResponse>(`${this.baseUrl}/${signatureId}/revoke`, {
      revocationReason,
    });
  }

  listAll(): Observable<SignatureResponse[]> {
    return this.http.get<SignatureResponse[]>(`${this.baseUrl}/all`);
  }

  listByProvider(staffId: string): Observable<SignatureResponse[]> {
    return this.http.get<SignatureResponse[]>(`${this.baseUrl}/provider/${staffId}`);
  }

  listByReport(reportType: SignatureType, reportId: string): Observable<SignatureResponse[]> {
    return this.http.get<SignatureResponse[]>(`${this.baseUrl}/report/${reportType}/${reportId}`);
  }

  isReportSigned(reportType: SignatureType, reportId: string): Observable<boolean> {
    return this.http.get<boolean>(`${this.baseUrl}/report/${reportType}/${reportId}/is-signed`);
  }

  auditTrail(signatureId: string): Observable<SignatureAuditEntry[]> {
    return this.http.get<SignatureAuditEntry[]>(`${this.baseUrl}/${signatureId}/audit-trail`);
  }
}
