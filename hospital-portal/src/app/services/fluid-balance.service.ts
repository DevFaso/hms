import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type IntakeOutputCategory = 'INTAKE' | 'OUTPUT';

/** Mirrors the backend IntakeOutputRoute enum — the route determines the category server-side. */
export type IntakeOutputRoute =
  | 'ORAL'
  | 'IV'
  | 'ENTERAL'
  | 'BLOOD_PRODUCT'
  | 'OTHER_INTAKE'
  | 'URINE'
  | 'EMESIS'
  | 'STOOL'
  | 'DRAIN'
  | 'BLOOD_LOSS'
  | 'OTHER_OUTPUT';

export const INTAKE_ROUTES: IntakeOutputRoute[] = [
  'ORAL',
  'IV',
  'ENTERAL',
  'BLOOD_PRODUCT',
  'OTHER_INTAKE',
];

export const OUTPUT_ROUTES: IntakeOutputRoute[] = [
  'URINE',
  'EMESIS',
  'STOOL',
  'DRAIN',
  'BLOOD_LOSS',
  'OTHER_OUTPUT',
];

export interface IntakeOutputEntryRequest {
  route: IntakeOutputRoute;
  volumeMl: number;
  /** Local date-time string (no zone) — the backend field is a LocalDateTime. */
  observationTime?: string;
  lateEntry?: boolean;
  notes?: string;
}

export interface IntakeOutputEntry {
  id: string;
  observationTime: string;
  documentedAt: string;
  lateEntry: boolean;
  category: IntakeOutputCategory;
  route: IntakeOutputRoute;
  volumeMl: number;
  notes: string | null;
  recordedByName: string | null;
}

export interface IntakeOutputSummary {
  patientId: string;
  windowFrom: string;
  windowTo: string;
  totalIntakeMl: number;
  totalOutputMl: number;
  balanceMl: number;
  entries: IntakeOutputEntry[];
}

@Injectable({ providedIn: 'root' })
export class FluidBalanceService {
  private readonly http = inject(HttpClient);

  record(patientId: string, req: IntakeOutputEntryRequest): Observable<IntakeOutputEntry> {
    return this.http.post<IntakeOutputEntry>(`/patients/${patientId}/intake-output`, req);
  }

  getSummary(
    patientId: string,
    params?: { from?: string; to?: string },
  ): Observable<IntakeOutputSummary> {
    let httpParams = new HttpParams();
    if (params?.from) httpParams = httpParams.set('from', params.from);
    if (params?.to) httpParams = httpParams.set('to', params.to);
    return this.http.get<IntakeOutputSummary>(`/patients/${patientId}/intake-output`, {
      params: httpParams,
    });
  }
}
