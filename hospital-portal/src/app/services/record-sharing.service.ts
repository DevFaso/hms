import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

// ── Types mirroring backend PatientRecordDTO / RecordShareResultDTO ──────────

export interface PatientRecord {
  patientId: string;
  firstName: string;
  lastName: string;
  middleName?: string;
  dateOfBirth?: string;
  gender?: string;
  bloodType?: string;
  medicalHistorySummary?: string;
  allergies?: string;
  address?: string;
  city?: string;
  state?: string;
  zipCode?: string;
  country?: string;
  phoneNumberPrimary?: string;
  phoneNumberSecondary?: string;
  email?: string;
  emergencyContactName?: string;
  emergencyContactPhone?: string;
  emergencyContactRelationship?: string;
  hospitalMRNs?: string[];
  hospitalMrnMap?: Record<string, string>;
  consentId?: string;
  consentTimestamp?: string;
  consentExpiration?: string;
  consentPurpose?: string;
  fromHospitalId?: string;
  fromHospitalName?: string;
  toHospitalId?: string;
  toHospitalName?: string;
  encounters?: unknown[];
  treatments?: unknown[];
  labOrders?: unknown[];
  labResults?: unknown[];
  allergiesDetailed?: unknown[];
  prescriptions?: unknown[];
  insurances?: unknown[];
  problems?: unknown[];
  surgicalHistory?: unknown[];
  advanceDirectives?: unknown[];
  encounterHistory?: unknown[];
}

export type ShareScope = 'SAME_HOSPITAL' | 'INTRA_ORG' | 'CROSS_ORG';

export interface RecordShareResult {
  // Scope / provenance
  shareScope: ShareScope;
  shareScopeLabel: string;
  resolvedFromHospitalId: string;
  resolvedFromHospitalName: string;
  requestingHospitalId: string;
  requestingHospitalName: string;
  organizationName: string | null;
  organizationId: string | null;
  // Consent metadata
  consentId: string | null;
  consentGrantedAt: string | null;
  consentExpiresAt: string | null;
  consentPurpose: string | null;
  consentActive: boolean;
  // Audit
  resolvedAt: string;
  // Full record
  patientRecord: PatientRecord;
}

export interface ConsentGrantRequest {
  patientId: string;
  fromHospitalId: string;
  toHospitalId: string;
  purpose?: string;
  consentExpiration?: string; // ISO-8601 datetime
}

export interface PatientConsentResponse {
  id: string;
  patient: { id: string; firstName: string; lastName: string };
  fromHospital: { id: string; name: string };
  toHospital: { id: string; name: string };
  consentGiven: boolean;
  consentTimestamp: string;
  consentExpiration: string | null;
  purpose: string | null;
}

// ── Service ──────────────────────────────────────────────────────────────────

@Injectable({ providedIn: 'root' })
export class RecordSharingService {
  private readonly http = inject(HttpClient);

  /**
   * Smart resolver: automatically picks the fastest consent path
   * (SAME_HOSPITAL → INTRA_ORG → CROSS_ORG) and returns the full
   * patient record with provenance metadata.
   */
  resolveAndShare(patientId: string, requestingHospitalId: string): Observable<RecordShareResult> {
    const params = new HttpParams()
      .set('patientId', patientId)
      .set('requestingHospitalId', requestingHospitalId);
    return this.http.get<RecordShareResult>('/records/resolve', { params });
  }

  /**
   * Classic explicit share: caller provides both source and target hospitals.
   */
  getPatientRecord(
    patientId: string,
    fromHospitalId: string,
    toHospitalId: string,
  ): Observable<PatientRecord> {
    return this.http.post<PatientRecord>('/records/share', {
      patientId,
      fromHospitalId,
      toHospitalId,
    });
  }

  /**
   * Grant a sharing consent from one hospital to another.
   */
  grantConsent(req: ConsentGrantRequest): Observable<PatientConsentResponse> {
    return this.http.post<PatientConsentResponse>('/patient-consents/grant', req);
  }

  /**
   * Revoke an existing consent.
   */
  revokeConsent(patientId: string, fromHospitalId: string, toHospitalId: string): Observable<void> {
    const params = new HttpParams()
      .set('patientId', patientId)
      .set('fromHospitalId', fromHospitalId)
      .set('toHospitalId', toHospitalId);
    return this.http.post<void>('/patient-consents/revoke', null, { params });
  }

  /**
   * Export patient record as PDF or CSV.
   */
  exportRecord(
    patientId: string,
    fromHospitalId: string,
    toHospitalId: string,
    format: 'pdf' | 'csv',
  ): Observable<Blob> {
    const params = new HttpParams()
      .set('patientId', patientId)
      .set('fromHospitalId', fromHospitalId)
      .set('toHospitalId', toHospitalId)
      .set('format', format);
    return this.http.get('/records/export', { params, responseType: 'blob' });
  }
}
