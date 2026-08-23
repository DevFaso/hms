import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';

export type EligibilityScheme =
  'NHIS_GH' | 'NHIA_NG' | 'CNAMGS_GA' | 'MUTUELLE_RW' | 'MUTUELLE_BF' | 'GENERIC';

export type EligibilityCheckType = 'COVERAGE' | 'PRIOR_AUTH';

export type EligibilityStatus = 'ELIGIBLE' | 'NOT_ELIGIBLE' | 'PENDING' | 'UNKNOWN' | 'ERROR';

export interface EligibilityCheckRequest {
  patientId: string;
  hospitalId: string;
  patientInsuranceId?: string;
  scheme: EligibilityScheme;
  checkType: EligibilityCheckType;
  memberId?: string;
  serviceCode?: string;
}

export interface EligibilityResponse {
  id: string;
  patientId: string;
  hospitalId: string;
  patientInsuranceId?: string;
  scheme: EligibilityScheme;
  checkType: EligibilityCheckType;
  memberId?: string;
  serviceCode?: string;
  requestedAt: string;
  completedAt?: string;
  status: EligibilityStatus;
  responseCode?: string;
  payerResponseText?: string;
  copayAmount?: number;
  copayCurrency?: string;
  priorAuthRequired?: boolean;
  priorAuthNumber?: string;
  validUntil?: string;
  errorMessage?: string;
  requestedByUserId?: string;
}

export interface EligibilityPage {
  content: EligibilityResponse[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/**
 * Client wrapper around the /eligibility REST endpoints.
 *
 * The backend defaults to a deterministic stub provider until the real NHIS /
 * CNAMGS / mutuelle connectors land (P1 #12 follow-up #4) — the wire format
 * here is stable across that swap.
 */
@Injectable({ providedIn: 'root' })
export class EligibilityService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/eligibility';

  /** Coverage / member-status check (analogue: X12 270/271). */
  checkCoverage(req: EligibilityCheckRequest): Observable<EligibilityResponse> {
    return this.http.post<EligibilityResponse>(`${this.baseUrl}/check`, {
      ...req,
      checkType: 'COVERAGE',
    });
  }

  /** Prior-authorisation request for a specific service (analogue: X12 278). */
  requestPriorAuth(req: EligibilityCheckRequest): Observable<EligibilityResponse> {
    return this.http.post<EligibilityResponse>(`${this.baseUrl}/prior-auth`, {
      ...req,
      checkType: 'PRIOR_AUTH',
    });
  }

  getById(checkId: string): Observable<EligibilityResponse> {
    return this.http.get<EligibilityResponse>(`${this.baseUrl}/${checkId}`);
  }

  listByPatient(patientId: string, page = 0, size = 20): Observable<EligibilityPage> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<EligibilityPage>(`${this.baseUrl}/patient/${patientId}`, { params });
  }

  /**
   * Returns null when the backend responds 204 No Content (no prior check).
   * Lets callers render the "Run check" CTA without an error branch.
   */
  latestForPatient(
    patientId: string,
    scheme: EligibilityScheme,
    type: EligibilityCheckType = 'COVERAGE',
  ): Observable<EligibilityResponse | null> {
    const params = new HttpParams().set('scheme', scheme).set('type', type);
    return this.http
      .get<EligibilityResponse>(`${this.baseUrl}/patient/${patientId}/latest`, {
        params,
        observe: 'response',
      })
      .pipe(
        map((res) => (res.status === 204 ? null : (res.body ?? null))),
        catchError(() => of(null)),
      );
  }
}
