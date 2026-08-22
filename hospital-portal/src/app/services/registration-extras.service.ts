import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/* ── P3 #21: patient photo + consent-to-treat + guarantors ─────────────── */

export type TreatmentConsentStatus = 'ACTIVE' | 'REVOKED' | 'EXPIRED';
export type TreatmentConsentMethod = 'ELECTRONIC' | 'VERBAL' | 'PAPER';
export type TreatmentConsentSource = 'CHECK_IN' | 'PRE_CHECK_IN' | 'MANUAL';

/** Mirrors TreatmentConsentResponseDTO field-for-field — the wire contract. */
export interface TreatmentConsent {
  id: string;
  patientId: string;
  hospitalId: string;
  hospitalName: string | null;
  appointmentId: string | null;
  encounterId: string | null;
  status: TreatmentConsentStatus;
  method: TreatmentConsentMethod;
  source: TreatmentConsentSource;
  signedName: string | null;
  signatureHash: string | null;
  consentedAt: string;
  expiresAt: string | null;
  recordedByName: string | null;
  revokedAt: string | null;
  revocationReason: string | null;
  notes: string | null;
  createdAt: string;
}

export interface TreatmentConsentRequest {
  method: TreatmentConsentMethod;
  signedName?: string;
  appointmentId?: string;
  encounterId?: string;
  expiresAt?: string;
  notes?: string;
}

/** Mirrors GuarantorResponseDTO field-for-field. */
export interface Guarantor {
  id: string;
  patientId: string;
  hospitalId: string;
  fullName: string;
  relationship: string | null;
  phone: string | null;
  email: string | null;
  address: string | null;
  primary: boolean;
  active: boolean;
  notes: string | null;
  createdAt: string;
  updatedAt: string | null;
}

export interface GuarantorRequest {
  fullName: string;
  relationship?: string;
  phone?: string;
  email?: string;
  address?: string;
  primary?: boolean;
  notes?: string;
}

@Injectable({ providedIn: 'root' })
export class RegistrationExtrasService {
  private readonly http = inject(HttpClient);

  /* ── Photo. Fetched as a blob because <img src> carries no bearer token —
     the endpoint is authenticated (a patient photo is PHI, deliberately not
     the permitAll /uploads/** path). ─────────────────────────────────── */

  uploadPhoto(
    patientId: string,
    file: Blob,
    filename: string,
  ): Observable<{ photoUpdatedAt: string }> {
    const form = new FormData();
    form.append('file', file, filename);
    return this.http.post<{ photoUpdatedAt: string }>(`/patients/${patientId}/photo`, form);
  }

  getPhotoBlob(patientId: string): Observable<Blob> {
    return this.http.get(`/patients/${patientId}/photo`, { responseType: 'blob' });
  }

  deletePhoto(patientId: string): Observable<void> {
    return this.http.delete<void>(`/patients/${patientId}/photo`);
  }

  /* ── Consent-to-treat ──────────────────────────────────────────────── */

  listConsents(patientId: string): Observable<TreatmentConsent[]> {
    return this.http.get<TreatmentConsent[]>(`/patients/${patientId}/treatment-consents`);
  }

  recordConsent(patientId: string, req: TreatmentConsentRequest): Observable<TreatmentConsent> {
    return this.http.post<TreatmentConsent>(`/patients/${patientId}/treatment-consents`, req);
  }

  revokeConsent(
    patientId: string,
    consentId: string,
    reason: string,
  ): Observable<TreatmentConsent> {
    const params = new HttpParams().set('reason', reason);
    return this.http.post<TreatmentConsent>(
      `/patients/${patientId}/treatment-consents/${consentId}/revoke`,
      {},
      { params },
    );
  }

  /* ── Guarantors ────────────────────────────────────────────────────── */

  listGuarantors(patientId: string): Observable<Guarantor[]> {
    return this.http.get<Guarantor[]>(`/patients/${patientId}/guarantors`);
  }

  addGuarantor(patientId: string, req: GuarantorRequest): Observable<Guarantor> {
    return this.http.post<Guarantor>(`/patients/${patientId}/guarantors`, req);
  }

  updateGuarantor(
    patientId: string,
    guarantorId: string,
    req: GuarantorRequest,
  ): Observable<Guarantor> {
    return this.http.put<Guarantor>(`/patients/${patientId}/guarantors/${guarantorId}`, req);
  }

  deactivateGuarantor(patientId: string, guarantorId: string): Observable<Guarantor> {
    return this.http.post<Guarantor>(
      `/patients/${patientId}/guarantors/${guarantorId}/deactivate`,
      {},
    );
  }

  reactivateGuarantor(patientId: string, guarantorId: string): Observable<Guarantor> {
    return this.http.post<Guarantor>(
      `/patients/${patientId}/guarantors/${guarantorId}/reactivate`,
      {},
    );
  }
}
