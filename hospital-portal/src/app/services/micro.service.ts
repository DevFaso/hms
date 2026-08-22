import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type MicroCultureStatus = 'PRELIMINARY' | 'FINAL' | 'CORRECTED';
export type MicroGrowthResult = 'GROWTH' | 'NO_GROWTH' | 'MIXED_FLORA' | 'CONTAMINATED';
export type MicroSusceptibilityMethod = 'DISK_DIFFUSION' | 'MIC' | 'ETEST' | 'OTHER';
export type MicroSusceptibilityInterpretation = 'SUSCEPTIBLE' | 'INTERMEDIATE' | 'RESISTANT';

export const GROWTH_RESULTS: MicroGrowthResult[] = [
  'GROWTH',
  'NO_GROWTH',
  'MIXED_FLORA',
  'CONTAMINATED',
];

export const SUSCEPTIBILITY_METHODS: MicroSusceptibilityMethod[] = [
  'DISK_DIFFUSION',
  'MIC',
  'ETEST',
  'OTHER',
];

export const INTERPRETATIONS: MicroSusceptibilityInterpretation[] = [
  'SUSCEPTIBLE',
  'INTERMEDIATE',
  'RESISTANT',
];

/**
 * Mirrors MicroCultureResponseDTO field-for-field. Field names below are the
 * wire contract — do not "clean them up" (the vitals tab shipped broken for
 * exactly that reason).
 */
export interface MicroSusceptibility {
  id: string;
  antibioticName: string;
  antibioticCode: string | null;
  method: MicroSusceptibilityMethod | null;
  micValue: string | null;
  interpretation: MicroSusceptibilityInterpretation;
  notes: string | null;
}

export interface MicroIsolate {
  id: string;
  isolateNumber: number;
  organismName: string;
  organismCode: string | null;
  growthQuantity: string | null;
  notes: string | null;
  susceptibilities: MicroSusceptibility[];
}

export interface MicroCultureResponse {
  id: string;
  labOrderId: string;
  labOrderCode: string | null;
  labTestName: string | null;
  patientId: string;
  patientName: string | null;
  hospitalId: string;
  hospitalName: string | null;
  specimenId: string | null;
  specimenAccessionNumber: string | null;
  specimenSource: string | null;
  collectedAt: string | null;
  status: MicroCultureStatus;
  growthResult: MicroGrowthResult | null;
  gramStain: string | null;
  finalizedAt: string | null;
  finalizedByName: string | null;
  correctedAt: string | null;
  correctionReason: string | null;
  reportedByName: string | null;
  notes: string | null;
  createdAt: string;
  updatedAt: string | null;
  isolates: MicroIsolate[];
}

/** Spring Page envelope, returned bare (no ApiWrapper) by GET /micro-cultures. */
export interface MicroCulturePage {
  content: MicroCultureResponse[];
  totalElements: number;
  totalPages: number;
  number: number;
}

export interface MicroCultureCreateRequest {
  labOrderId: string;
  specimenId?: string;
  specimenSource?: string;
  /** Local date-time string (no zone) — the backend field is a LocalDateTime. */
  collectedAt?: string;
  gramStain?: string;
  growthResult?: MicroGrowthResult;
  notes?: string;
}

export interface MicroCultureUpdateRequest {
  specimenSource?: string;
  collectedAt?: string;
  gramStain?: string;
  growthResult?: MicroGrowthResult;
  notes?: string;
  /** Mandatory once the report is FINAL/CORRECTED. */
  correctionReason?: string;
}

export interface MicroIsolateRequest {
  organismName: string;
  organismCode?: string;
  isolateNumber?: number;
  growthQuantity?: string;
  notes?: string;
  correctionReason?: string;
}

export interface MicroSusceptibilityRequest {
  antibioticName: string;
  antibioticCode?: string;
  method?: MicroSusceptibilityMethod;
  micValue?: string;
  interpretation: MicroSusceptibilityInterpretation;
  notes?: string;
  correctionReason?: string;
}

@Injectable({ providedIn: 'root' })
export class MicroService {
  private readonly http = inject(HttpClient);

  listForPatient(patientId: string): Observable<MicroCultureResponse[]> {
    return this.http.get<MicroCultureResponse[]>(`/patients/${patientId}/micro-cultures`);
  }

  list(params?: {
    status?: MicroCultureStatus;
    page?: number;
    size?: number;
  }): Observable<MicroCulturePage> {
    let httpParams = new HttpParams();
    if (params?.status) httpParams = httpParams.set('status', params.status);
    if (params?.page != null) httpParams = httpParams.set('page', params.page);
    if (params?.size != null) httpParams = httpParams.set('size', params.size);
    return this.http.get<MicroCulturePage>('/micro-cultures', { params: httpParams });
  }

  get(cultureId: string): Observable<MicroCultureResponse> {
    return this.http.get<MicroCultureResponse>(`/micro-cultures/${cultureId}`);
  }

  create(req: MicroCultureCreateRequest): Observable<MicroCultureResponse> {
    return this.http.post<MicroCultureResponse>('/micro-cultures', req);
  }

  update(cultureId: string, req: MicroCultureUpdateRequest): Observable<MicroCultureResponse> {
    return this.http.put<MicroCultureResponse>(`/micro-cultures/${cultureId}`, req);
  }

  finalize(cultureId: string): Observable<MicroCultureResponse> {
    return this.http.post<MicroCultureResponse>(`/micro-cultures/${cultureId}/finalize`, {});
  }

  addIsolate(cultureId: string, req: MicroIsolateRequest): Observable<MicroCultureResponse> {
    return this.http.post<MicroCultureResponse>(`/micro-cultures/${cultureId}/isolates`, req);
  }

  updateIsolate(
    cultureId: string,
    isolateId: string,
    req: MicroIsolateRequest,
  ): Observable<MicroCultureResponse> {
    return this.http.put<MicroCultureResponse>(
      `/micro-cultures/${cultureId}/isolates/${isolateId}`,
      req,
    );
  }

  deleteIsolate(
    cultureId: string,
    isolateId: string,
    correctionReason?: string,
  ): Observable<MicroCultureResponse> {
    let httpParams = new HttpParams();
    if (correctionReason) httpParams = httpParams.set('correctionReason', correctionReason);
    return this.http.delete<MicroCultureResponse>(
      `/micro-cultures/${cultureId}/isolates/${isolateId}`,
      { params: httpParams },
    );
  }

  addSusceptibility(
    cultureId: string,
    isolateId: string,
    req: MicroSusceptibilityRequest,
  ): Observable<MicroCultureResponse> {
    return this.http.post<MicroCultureResponse>(
      `/micro-cultures/${cultureId}/isolates/${isolateId}/susceptibilities`,
      req,
    );
  }

  deleteSusceptibility(
    cultureId: string,
    isolateId: string,
    susceptibilityId: string,
    correctionReason?: string,
  ): Observable<MicroCultureResponse> {
    let httpParams = new HttpParams();
    if (correctionReason) httpParams = httpParams.set('correctionReason', correctionReason);
    return this.http.delete<MicroCultureResponse>(
      `/micro-cultures/${cultureId}/isolates/${isolateId}/susceptibilities/${susceptibilityId}`,
      { params: httpParams },
    );
  }
}
