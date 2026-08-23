import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/**
 * Scheduled reports (P3 #25a): aggregate-only CSVs emailed each closed
 * period. Mirrors the backend /reports DTOs field-for-field.
 */

export type ReportType = 'ENCOUNTER_ACTIVITY' | 'APPOINTMENT_ACTIVITY';
export type ReportPeriod = 'DAILY' | 'WEEKLY' | 'MONTHLY';
export type ReportRunStatus = 'GENERATING' | 'SUCCEEDED' | 'FAILED';

export interface ReportDefinitionRequest {
  name: string;
  reportType: ReportType;
  period: ReportPeriod;
  /** Comma-separated recipient email addresses. */
  recipients: string;
}

export interface ReportDefinitionResponse {
  id: string;
  hospitalId: string;
  name: string;
  reportType: ReportType;
  period: ReportPeriod;
  recipients: string;
  active: boolean;
  createdBy: string | null;
  createdAt: string;
}

export interface ReportRunResponse {
  id: string;
  periodToken: string;
  status: ReportRunStatus;
  rowCount: number | null;
  errorMessage: string | null;
  generatedAt: string | null;
  createdAt: string;
}

@Injectable({ providedIn: 'root' })
export class ReportsService {
  private readonly http = inject(HttpClient);
  private readonly base = '/reports';

  create(request: ReportDefinitionRequest): Observable<ReportDefinitionResponse> {
    return this.http.post<ReportDefinitionResponse>(this.base, request);
  }

  list(): Observable<ReportDefinitionResponse[]> {
    return this.http.get<ReportDefinitionResponse[]>(this.base);
  }

  runs(definitionId: string): Observable<ReportRunResponse[]> {
    return this.http.get<ReportRunResponse[]>(`${this.base}/${definitionId}/runs`);
  }

  runNow(definitionId: string, periodToken?: string): Observable<ReportRunResponse> {
    let params = new HttpParams();
    if (periodToken) params = params.set('periodToken', periodToken);
    return this.http.post<ReportRunResponse>(`${this.base}/${definitionId}/run`, {}, { params });
  }

  deactivate(definitionId: string): Observable<ReportDefinitionResponse> {
    return this.http.post<ReportDefinitionResponse>(`${this.base}/${definitionId}/deactivate`, {});
  }

  reactivate(definitionId: string): Observable<ReportDefinitionResponse> {
    return this.http.post<ReportDefinitionResponse>(`${this.base}/${definitionId}/reactivate`, {});
  }
}
