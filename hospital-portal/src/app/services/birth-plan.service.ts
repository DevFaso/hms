import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { PageResponse } from './maternity.service';

/* All categorical birth-plan fields are free-text strings on the backend. */

export interface BirthPlanIntroduction {
  patientName?: string;
  expectedDueDate?: string;
  placeOfBirth?: string;
  healthcareProvider?: string;
  medicalConditions?: string;
}

export interface BirthPlanDeliveryPreferences {
  preferredDeliveryMethod?: string;
  backupDeliveryMethod?: string;
  deliveryMethodNotes?: string;
}

export interface BirthPlanPainManagement {
  preferredApproach?: string;
  unmedicatedTechniques?: string[];
  medicatedOptions?: string[];
  painManagementNotes?: string;
}

export interface BirthPlanEnvironment {
  supportPersons?: string[];
  lightingPreference?: string;
  musicPreference?: string;
  fetalMonitoringStyle?: string;
  comfortItems?: string[];
  movementDuringLabor?: boolean;
  environmentNotes?: string;
}

export interface BirthPlanPostpartumPreferences {
  delayedCordClamping?: boolean;
  cordClampingDuration?: number;
  whoCutsCord?: string;
  skinToSkinContact?: boolean;
  vitaminKShot?: string;
  eyeOintment?: string;
  hepatitisBVaccine?: string;
  firstBathTiming?: string;
  feedingMethod?: string;
  postpartumNotes?: string;
}

export interface BirthPlanRequest {
  patientId?: string;
  hospitalId?: string;
  introduction: BirthPlanIntroduction;
  deliveryPreferences?: BirthPlanDeliveryPreferences;
  painManagement?: BirthPlanPainManagement;
  deliveryRoomEnvironment?: BirthPlanEnvironment;
  postpartumPreferences?: BirthPlanPostpartumPreferences;
  additionalWishes?: string;
  flexibilityAcknowledgment: boolean;
  discussedWithProvider?: boolean;
}

export interface BirthPlanResponse {
  id: string;
  patientId?: string;
  hospitalId?: string;
  introduction?: BirthPlanIntroduction;
  deliveryPreferences?: BirthPlanDeliveryPreferences;
  painManagement?: BirthPlanPainManagement;
  deliveryRoomEnvironment?: BirthPlanEnvironment;
  postpartumPreferences?: BirthPlanPostpartumPreferences;
  additionalWishes?: string;
  flexibilityAcknowledgment?: boolean;
  discussedWithProvider?: boolean;
  providerReviewRequired?: boolean;
  providerReviewed?: boolean;
  providerSignature?: string | null;
  providerSignatureDate?: string | null;
  providerComments?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface BirthPlanSearchParams {
  hospitalId?: string;
  patientId?: string;
  providerReviewed?: boolean;
  dueDateFrom?: string;
  dueDateTo?: string;
  page?: number;
  size?: number;
}

/**
 * Birth plans — /birth-plans (bare DTOs, Page on /search + /pending-review).
 * Effective roles: create/update incl. NURSE; review = DOCTOR/MIDWIFE/
 * SUPER_ADMIN; delete + pending-review exclude NURSE.
 */
@Injectable({ providedIn: 'root' })
export class BirthPlanService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/birth-plans';

  create(req: BirthPlanRequest): Observable<BirthPlanResponse> {
    return this.http.post<BirthPlanResponse>(this.baseUrl, req);
  }

  update(id: string, req: BirthPlanRequest): Observable<BirthPlanResponse> {
    return this.http.put<BirthPlanResponse>(`${this.baseUrl}/${id}`, req);
  }

  getById(id: string): Observable<BirthPlanResponse> {
    return this.http.get<BirthPlanResponse>(`${this.baseUrl}/${id}`);
  }

  byPatient(patientId: string): Observable<BirthPlanResponse[]> {
    return this.http.get<BirthPlanResponse[]>(`${this.baseUrl}/patient/${patientId}`);
  }

  search(params: BirthPlanSearchParams): Observable<PageResponse<BirthPlanResponse>> {
    let httpParams = new HttpParams();
    if (params.hospitalId) httpParams = httpParams.set('hospitalId', params.hospitalId);
    if (params.patientId) httpParams = httpParams.set('patientId', params.patientId);
    if (params.providerReviewed !== undefined)
      httpParams = httpParams.set('providerReviewed', params.providerReviewed);
    if (params.dueDateFrom) httpParams = httpParams.set('dueDateFrom', params.dueDateFrom);
    if (params.dueDateTo) httpParams = httpParams.set('dueDateTo', params.dueDateTo);
    httpParams = httpParams.set('page', params.page ?? 0).set('size', params.size ?? 20);
    return this.http.get<PageResponse<BirthPlanResponse>>(`${this.baseUrl}/search`, {
      params: httpParams,
    });
  }

  /** Excludes NURSE on the backend. */
  pendingReview(
    hospitalId: string | undefined,
    page = 0,
    size = 20,
  ): Observable<PageResponse<BirthPlanResponse>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (hospitalId) params = params.set('hospitalId', hospitalId);
    return this.http.get<PageResponse<BirthPlanResponse>>(`${this.baseUrl}/pending-review`, {
      params,
    });
  }

  /** DOCTOR/MIDWIFE/SUPER_ADMIN only. */
  review(
    id: string,
    reviewed: boolean,
    signature: string,
    comments?: string,
  ): Observable<BirthPlanResponse> {
    return this.http.post<BirthPlanResponse>(`${this.baseUrl}/${id}/review`, {
      reviewed,
      signature,
      comments,
    });
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }
}
