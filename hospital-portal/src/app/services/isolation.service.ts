import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/**
 * Transmission-based precautions. Standard precautions apply to everyone and
 * are deliberately not modelled — recording "standard" against one patient
 * would imply the others are exempt.
 */
export type IsolationPrecautionType = 'CONTACT' | 'DROPLET' | 'AIRBORNE' | 'PROTECTIVE';

export interface IsolationPrecautionResponse {
  id: string;
  patientId: string;
  patientName: string | null;
  admissionId: string | null;
  precautionType: IsolationPrecautionType;
  reason: string;
  suspectedOrganism: string | null;
  startedAt: string;
  orderedByName: string | null;
  endedAt: string | null;
  discontinuedByName: string | null;
  discontinuationReason: string | null;
  /** Derived server-side: endedAt === null. */
  active: boolean;
  /** Only AIRBORNE constrains where the patient may lie. */
  requiresIsolationWard: boolean;
  notes: string | null;
}

export interface IsolationPrecautionRequest {
  patientId: string;
  /** Optional — precautions legitimately start before there is an admission. */
  admissionId?: string;
  precautionType: IsolationPrecautionType;
  reason: string;
  suspectedOrganism?: string;
  orderedByStaffId?: string;
  notes?: string;
}

/** Lifting isolation is a clinical decision, so the reason is required. */
export interface DiscontinuePrecautionRequest {
  discontinuationReason: string;
  discontinuedByStaffId?: string;
}

@Injectable({ providedIn: 'root' })
export class IsolationService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/isolation';

  startPrecaution(req: IsolationPrecautionRequest): Observable<IsolationPrecautionResponse> {
    return this.http.post<IsolationPrecautionResponse>(`${this.baseUrl}/precautions`, req);
  }

  discontinuePrecaution(
    precautionId: string,
    req: DiscontinuePrecautionRequest,
  ): Observable<IsolationPrecautionResponse> {
    return this.http.post<IsolationPrecautionResponse>(
      `${this.baseUrl}/precautions/${precautionId}/discontinue`,
      req,
    );
  }

  getActiveForPatient(patientId: string): Observable<IsolationPrecautionResponse[]> {
    return this.http.get<IsolationPrecautionResponse[]>(
      `${this.baseUrl}/precautions/patient/${patientId}`,
    );
  }

  getHistoryForPatient(patientId: string): Observable<IsolationPrecautionResponse[]> {
    return this.http.get<IsolationPrecautionResponse[]>(
      `${this.baseUrl}/precautions/patient/${patientId}/history`,
    );
  }
}
