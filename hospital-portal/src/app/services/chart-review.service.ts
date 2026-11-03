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
  'ENCOUNTER' | 'NOTE' | 'RESULT' | 'MEDICATION' | 'IMAGING' | 'PROCEDURE';

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
  /** QA D1107: the title the author gave the note (a nursing note's). */
  title?: string | null;
  authorName?: string | null;
  authorCredentials?: string | null;
  documentedAt?: string | null;
  signedAt?: string | null;
  signed: boolean;
  lateEntry: boolean;
  /* Co-sign state (P3 #20): pending = requiresCosign && !cosignedAt. */
  requiresCosign?: boolean;
  cosignedAt?: string | null;
  preview?: string | null;
  /** QA D1076: a nurse's note (DAR / SOAPIE) or the visit's clinical note. */
  source?: 'ENCOUNTER' | 'NURSING' | null;
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
  /** QA D1016: the result corrects a released value. */
  corrected?: boolean;
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
  /** QA D1016 (D931 class): a RESULT event whose value replaces a released one. */
  corrected?: boolean;
}

/** QA D1380: the server's count of every row of each section (never a page's length). */
export interface ChartReviewTotals {
  timeline: number;
  encounters: number;
  notes: number;
  results: number;
  medications: number;
  imaging: number;
  procedures: number;
}

/** QA D1380: the tab a page is asked for. */
export type ChartReviewSection =
  'TIMELINE' | 'ENCOUNTERS' | 'NOTES' | 'RESULTS' | 'MEDICATIONS' | 'IMAGING' | 'PROCEDURES';

export interface ChartReview {
  patientId: string;
  hospitalId?: string | null;
  hospitalName?: string | null;
  /** Rows per section page. */
  limit: number;
  /** QA D1380: the 0-based page of the sections returned. */
  page?: number | null;
  /** QA D1380: the one section asked for, or null when every section's page came back. */
  section?: ChartReviewSection | null;
  /** QA D1380: every row of each section, counted by the server. */
  totals?: ChartReviewTotals | null;
  /** QA D1380: the entries a timeline page names, for its labels on any page. */
  timelineRefs?: {
    encounters?: ChartReviewEncounter[] | null;
    notes?: ChartReviewNote[] | null;
    imaging?: ChartReviewImaging[] | null;
  } | null;
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

  /**
   * The first page of every section, with the server's totals; `section` and
   * `page` read a further page of one tab (QA D1380).
   */
  getChartReview(
    patientId: string,
    hospitalId?: string | null,
    limit?: number | null,
    section?: ChartReviewSection | null,
    page?: number | null,
  ): Observable<ChartReview> {
    let params = new HttpParams();
    if (hospitalId) {
      params = params.set('hospitalId', hospitalId);
    }
    if (limit !== null && limit !== undefined && limit > 0) {
      params = params.set('limit', String(limit));
    }
    if (section) {
      params = params.set('section', section);
    }
    if (page !== null && page !== undefined && page > 0) {
      params = params.set('page', String(page));
    }
    return this.http.get<ChartReview>(`/patients/${patientId}/chart-review`, { params });
  }
}
