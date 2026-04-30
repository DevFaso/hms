import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/**
 * Wire shapes for {@code GET /api/patients/:patientId/chart-review}.
 * The Chart Review tabbed viewer aggregates the six clinical sections
 * (encounters, notes, results, medications, imaging, procedures) plus
 * a unified timeline in one round-trip — same low-bandwidth pattern
 * the Storyboard banner uses.
 */
export type TimelineSection =
  | 'ENCOUNTER'
  | 'NOTE'
  | 'RESULT'
  | 'MEDICATION'
  | 'IMAGING'
  | 'PROCEDURE';

export interface ChartReviewEncounter {
  id: string;
  code?: string | null;
  encounterType?: string | null;
  status?: string | null;
  encounterDate?: string | null;
  departmentName?: string | null;
  staffFullName?: string | null;
  chiefComplaint?: string | null;
  roomAssignment?: string | null;
}

export interface ChartReviewNote {
  id: string;
  encounterId?: string | null;
  encounterCode?: string | null;
  template?: string | null;
  authorName?: string | null;
  authorCredentials?: string | null;
  documentedAt?: string | null;
  signedAt?: string | null;
  signed: boolean;
  lateEntry: boolean;
  preview?: string | null;
}

export interface ChartReviewResult {
  id: string;
  labOrderId?: string | null;
  testName?: string | null;
  testCode?: string | null;
  resultValue?: string | null;
  resultUnit?: string | null;
  abnormalFlag?: string | null;
  resultDate?: string | null;
  orderingStaffName?: string | null;
  acknowledged: boolean;
  released: boolean;
}

export interface ChartReviewMedication {
  id: string;
  medicationName?: string | null;
  medicationCode?: string | null;
  dosage?: string | null;
  route?: string | null;
  frequency?: string | null;
  duration?: string | null;
  status?: string | null;
  createdAt?: string | null;
  prescriberName?: string | null;
  controlledSubstance: boolean;
  inpatientOrder: boolean;
}

export interface ChartReviewImaging {
  id: string;
  modality?: string | null;
  studyType?: string | null;
  bodyRegion?: string | null;
  laterality?: string | null;
  priority?: string | null;
  status?: string | null;
  orderedAt?: string | null;
  scheduledFor?: string | null;
  clinicalQuestion?: string | null;
  reportStatus?: string | null;
  reportImpression?: string | null;
}

export interface ChartReviewProcedure {
  id: string;
  procedureName?: string | null;
  procedureCode?: string | null;
  procedureCategory?: string | null;
  urgency?: string | null;
  status?: string | null;
  orderedAt?: string | null;
  scheduledFor?: string | null;
  orderingProviderName?: string | null;
  indication?: string | null;
  consentObtained: boolean;
}

export interface ChartReviewTimelineEvent {
  id: string;
  section: TimelineSection;
  occurredAt?: string | null;
  title?: string | null;
  summary?: string | null;
  status?: string | null;
}

export interface ChartReview {
  patientId: string;
  hospitalId?: string | null;
  hospitalName?: string | null;
  limit: number;
  encounters: ChartReviewEncounter[];
  notes: ChartReviewNote[];
  results: ChartReviewResult[];
  medications: ChartReviewMedication[];
  imaging: ChartReviewImaging[];
  procedures: ChartReviewProcedure[];
  timeline: ChartReviewTimelineEvent[];
  generatedAt?: string | null;
}

/**
 * Calls {@code GET /api/patients/:patientId/chart-review}. The auth
 * interceptor prepends {@code /api} to relative URLs.
 */
@Injectable({ providedIn: 'root' })
export class ChartReviewService {
  private readonly http = inject(HttpClient);

  getChartReview(
    patientId: string,
    hospitalId?: string | null,
    limit?: number | null,
  ): Observable<ChartReview> {
    let params = new HttpParams();
    if (hospitalId) {
      params = params.set('hospitalId', hospitalId);
    }
    if (limit !== null && limit !== undefined && limit > 0) {
      params = params.set('limit', String(limit));
    }
    return this.http.get<ChartReview>(`/patients/${patientId}/chart-review`, { params });
  }
}
