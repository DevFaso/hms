import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { CdsCard } from '../shared/cds-card/cds-card.model';

export interface PrescriptionResponse {
  id: string;
  patientId: string;
  patientFullName: string;
  patientEmail: string;
  staffId: string;
  staffFullName: string;
  encounterId: string;
  hospitalId: string;
  /**
   * Display name for the prescription's hospital, populated by the
   * backend mapper. Used by the super-admin cross-tenant list view
   * (docs/super-admin-cross-tenant-design.md). Optional for backwards
   * compatibility with older snapshots that may lack the field.
   */
  hospitalName?: string;
  medicationName: string;
  medicationDisplayName: string;
  dosage: string;
  frequency: string;
  duration: string;
  notes: string;
  status: string;
  createdAt: string;
  updatedAt: string;
  /**
   * CDS rule-engine cards returned by the backend on create / update.
   * Optional because read-only responses (list, get-by-id) do not
   * re-run the rule engine.
   */
  cdsAdvisories?: CdsCard[];
}

export type PrescriptionStatusType =
  | 'DRAFT'
  | 'PENDING_SIGNATURE'
  | 'SIGNED'
  | 'TRANSMITTED'
  | 'TRANSMISSION_FAILED'
  | 'CANCELLED'
  | 'DISCONTINUED';

export interface PrescriptionRequest {
  patientId?: string;
  patientIdentifier?: string;
  staffId?: string;
  encounterId?: string;
  medicationName: string;
  dosage?: string;
  frequency?: string;
  duration?: string;
  notes?: string;
  status?: PrescriptionStatusType;
  forceOverride?: boolean;
}

@Injectable({ providedIn: 'root' })
export class PrescriptionService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/prescriptions';

  list(filters?: {
    patientId?: string;
    staffId?: string;
    hospitalId?: string;
  }): Observable<PrescriptionResponse[]> {
    let params = new HttpParams();
    if (filters) {
      if (filters.patientId) params = params.set('patientId', filters.patientId);
      if (filters.staffId) params = params.set('staffId', filters.staffId);
      if (filters.hospitalId) params = params.set('hospitalId', filters.hospitalId);
    }
    return this.http
      .get<{ content: PrescriptionResponse[] }>(this.baseUrl, { params })
      .pipe(map((res) => res?.content ?? []));
  }

  getById(id: string): Observable<PrescriptionResponse> {
    return this.http.get<PrescriptionResponse>(`${this.baseUrl}/${id}`);
  }

  create(req: PrescriptionRequest): Observable<PrescriptionResponse> {
    return this.http.post<PrescriptionResponse>(this.baseUrl, req);
  }

  update(id: string, req: PrescriptionRequest): Observable<PrescriptionResponse> {
    return this.http.put<PrescriptionResponse>(`${this.baseUrl}/${id}`, req);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  /**
   * The signing ceremony. `SIGNED` is deliberately not settable through
   * `update()` — the backend rejects a client-asserted signature — because this
   * is the only path that records who signed, when, and a digest of what they
   * signed. Only the prescribing clinician can call it.
   */
  sign(id: string): Observable<PrescriptionResponse> {
    return this.http.post<PrescriptionResponse>(`${this.baseUrl}/${id}/sign`, {});
  }

  dispatchSms(
    id: string,
    pharmacyId: string,
    note?: string,
  ): Observable<PrescriptionSmsDispatchResult> {
    return this.http
      .post<{
        data: PrescriptionSmsDispatchResult;
      }>(`${this.baseUrl}/${id}/dispatch-sms`, { pharmacyId, note: note ?? null })
      .pipe(map((r) => r.data));
  }
}

export interface PrescriptionSmsDispatchResult {
  prescriptionId: string;
  transmissionId: string;
  pharmacyId: string;
  pharmacyName: string;
  destinationPhone: string;
  status: string;
  dispatchedAt: string;
}

export interface CommunityPharmacyOption {
  id: string;
  name: string;
  phoneNumber: string;
  pharmacyType: string;
}

@Injectable({ providedIn: 'root' })
export class CommunityPharmacyService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/pharmacies/community';

  list(hospitalId?: string): Observable<CommunityPharmacyOption[]> {
    let params = new HttpParams();
    if (hospitalId) params = params.set('hospitalId', hospitalId);
    return this.http.get<CommunityPharmacyOption[]>(this.baseUrl, { params });
  }
}
