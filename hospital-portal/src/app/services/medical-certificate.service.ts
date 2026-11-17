import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { PatientDocumentResponse } from './patient-portal.service';

export type MedicalCertificateType = 'SICK_LEAVE' | 'ATTENDANCE' | 'GENERAL';

export interface MedicalCertificateRequest {
  type: MedicalCertificateType;
  /** yyyy-MM-dd, the first day off (sick leave); the visit day when absent. */
  startDate?: string;
  days?: number;
  remarks?: string;
  language?: 'fr' | 'en' | 'es';
}

/**
 * QA D961: a doctor issues a medical certificate or sick-leave note from a
 * visit. The server files the signed PDF in the patient's documents (the
 * chart's Documents tab and the patient portal) and returns that document.
 */
@Injectable({ providedIn: 'root' })
export class MedicalCertificateService {
  private readonly http = inject(HttpClient);

  issue(encounterId: string, req: MedicalCertificateRequest): Observable<PatientDocumentResponse> {
    return this.http.post<PatientDocumentResponse>(
      `/encounters/${encounterId}/medical-certificate`,
      req,
    );
  }
}
