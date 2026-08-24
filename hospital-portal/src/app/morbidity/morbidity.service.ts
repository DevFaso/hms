import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/**
 * Morbidity surveillance: which diagnoses were recorded most in a month.
 * Mirrors MorbidityDashboardDTO field-for-field.
 *
 * Aggregate-only — counts per ICD code, never patient rows.
 */

/** NETWORK spans every hospital (unscoped super-admin); HOSPITAL is one facility. */
export type MorbidityScope = 'HOSPITAL' | 'NETWORK';

export interface DiagnosisSlice {
  /** ICD code as recorded; null when the diagnosis was free-text. */
  code: string | null;
  display: string;
  count: number;
}

export interface HospitalBreakdown {
  hospitalId: string;
  hospitalName: string;
  top: DiagnosisSlice[];
  /** Every diagnosis at this hospital, including those below the chart cut-off. */
  totalRecorded: number;
}

export interface MorbidityDashboard {
  /** The month these counts cover, as yyyy-MM. */
  month: string;
  scope: MorbidityScope;
  /** Name of the single hospital in scope; null for NETWORK. */
  hospitalName: string | null;
  overall: DiagnosisSlice[];
  /** Empty unless the caller is an unscoped super-admin. */
  byHospital: HospitalBreakdown[];
}

@Injectable({ providedIn: 'root' })
export class MorbidityService {
  private readonly http = inject(HttpClient);
  private readonly base = '/morbidity';

  /**
   * Scope is decided by the backend from the caller's own authorities —
   * there is deliberately no hospitalId parameter to pass.
   */
  topDiagnoses(month: string, limit?: number): Observable<MorbidityDashboard> {
    let params = new HttpParams().set('month', month);
    if (limit != null) params = params.set('limit', String(limit));
    return this.http.get<MorbidityDashboard>(`${this.base}/top-diagnoses`, { params });
  }
}
