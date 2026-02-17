import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

export type TreatmentPlanStatus =
  | 'DRAFT'
  | 'PENDING_REVIEW'
  | 'APPROVED'
  | 'ACTIVE'
  | 'COMPLETED'
  | 'REJECTED';

export interface TreatmentPlanFollowUp {
  id: string;
  label: string;
  instructions: string;
  dueOn: string;
  assignedStaffId: string;
  assignedStaffName: string;
  status: string;
  completedAt: string;
  createdAt: string;
  updatedAt: string;
}

export interface TreatmentPlanReview {
  id: string;
  reviewerStaffId: string;
  reviewerName: string;
  action: string;
  comment: string;
  createdAt: string;
}

export interface TreatmentPlanResponse {
  id: string;
  patientId: string;
  patientName: string;
  hospitalId: string;
  hospitalName: string;
  encounterId: string;
  assignmentId: string;
  authorStaffId: string;
  authorStaffName: string;
  supervisingStaffId: string;
  supervisingStaffName: string;
  signOffStaffId: string;
  signOffStaffName: string;
  status: TreatmentPlanStatus;
  problemStatement: string;
  therapeuticGoals: string[];
  medicationPlan: string[];
  lifestylePlan: string[];
  referralPlan: string[];
  responsibleParties: string[];
  timelineSummary: string;
  followUpSummary: string;
  timelineStartDate: string;
  timelineReviewDate: string;
  patientVisibility: boolean;
  followUps: TreatmentPlanFollowUp[];
  reviews: TreatmentPlanReview[];
  createdAt: string;
  updatedAt: string;
}

export interface TreatmentPlanRequest {
  patientId: string;
  hospitalId: string;
  encounterId?: string;
  assignmentId: string;
  authorStaffId: string;
  supervisingStaffId?: string;
  problemStatement: string;
  therapeuticGoals?: string[];
  medicationPlan?: string[];
  lifestylePlan?: string[];
  referralPlan?: string[];
  responsibleParties?: string[];
  timelineSummary?: string;
  followUpSummary?: string;
  timelineStartDate?: string;
  timelineReviewDate?: string;
  patientVisibility?: boolean;
  followUps?: { label: string; instructions?: string; dueOn?: string; assignedStaffId?: string }[];
}

export interface TreatmentPlanReviewRequest {
  reviewerStaffId: string;
  action: string;
  comment?: string;
}

@Injectable({ providedIn: 'root' })
export class TreatmentPlanService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/treatment-plans';

  create(req: TreatmentPlanRequest): Observable<TreatmentPlanResponse> {
    return this.http.post<TreatmentPlanResponse>(this.baseUrl, req);
  }

  update(id: string, req: TreatmentPlanRequest): Observable<TreatmentPlanResponse> {
    return this.http.put<TreatmentPlanResponse>(`${this.baseUrl}/${id}`, req);
  }

  getById(id: string): Observable<TreatmentPlanResponse> {
    return this.http.get<TreatmentPlanResponse>(`${this.baseUrl}/${id}`);
  }

  getByPatient(patientId: string): Observable<TreatmentPlanResponse[]> {
    return this.http
      .get<{ content: TreatmentPlanResponse[] }>(`${this.baseUrl}/by-patient/${patientId}`)
      .pipe(map((res) => res?.content ?? []));
  }

  getByHospital(
    hospitalId: string,
    params?: { page?: number; size?: number },
  ): Observable<TreatmentPlanResponse[]> {
    let httpParams = new HttpParams();
    if (params?.page != null) httpParams = httpParams.set('page', params.page);
    if (params?.size) httpParams = httpParams.set('size', params.size);
    return this.http
      .get<{
        content: TreatmentPlanResponse[];
      }>(`${this.baseUrl}/by-hospital/${hospitalId}`, { params: httpParams })
      .pipe(map((res) => res?.content ?? []));
  }

  getAll(params?: { page?: number; size?: number }): Observable<TreatmentPlanResponse[]> {
    let httpParams = new HttpParams();
    if (params?.page != null) httpParams = httpParams.set('page', params.page);
    if (params?.size) httpParams = httpParams.set('size', params.size);
    return this.http
      .get<{ content: TreatmentPlanResponse[] }>(this.baseUrl, { params: httpParams })
      .pipe(map((res) => res?.content ?? []));
  }

  addFollowUp(
    id: string,
    followUp: { label: string; instructions?: string; dueOn?: string; assignedStaffId?: string },
  ): Observable<TreatmentPlanFollowUp> {
    return this.http.post<TreatmentPlanFollowUp>(`${this.baseUrl}/${id}/follow-ups`, followUp);
  }

  updateFollowUp(
    id: string,
    followUpId: string,
    data: Record<string, unknown>,
  ): Observable<TreatmentPlanFollowUp> {
    return this.http.put<TreatmentPlanFollowUp>(
      `${this.baseUrl}/${id}/follow-ups/${followUpId}`,
      data,
    );
  }

  addReview(id: string, review: TreatmentPlanReviewRequest): Observable<TreatmentPlanReview> {
    return this.http.post<TreatmentPlanReview>(`${this.baseUrl}/${id}/reviews`, review);
  }
}
