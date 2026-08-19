import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { PageResponse } from './maternity.service';

export type ObgynReferralStatus =
  | 'SUBMITTED'
  | 'ACKNOWLEDGED'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'CANCELLED';
export type ObgynReferralUrgency = 'ROUTINE' | 'PRIORITY' | 'URGENT';
export type ObgynReferralCareContext = 'ANTENATAL' | 'INTRAPARTUM' | 'POSTPARTUM';
export type ObgynTransferType = 'CONSULTATION' | 'SHARED_CARE' | 'TRANSFER_OF_CARE';
export type ReferralAttachmentCategory = 'LAB_RESULT' | 'ULTRASOUND' | 'NOTE' | 'CONSENT' | 'OTHER';

export interface ReferralPatientSummary {
  id: string;
  mrn?: string;
  firstName?: string;
  lastName?: string;
  dateOfBirth?: string;
}

export interface ReferralHospitalSummary {
  id: string;
  name?: string;
  code?: string;
}

export interface ReferralClinicianSummary {
  userId: string;
  username?: string;
  displayName?: string;
  primaryRole?: string;
}

export interface ReferralAttachment {
  id: string;
  storageKey?: string;
  displayName?: string;
  category?: ReferralAttachmentCategory;
  contentType?: string;
  sizeBytes?: number;
  uploadedBy?: string;
  uploadedByDisplayName?: string;
  uploadedAt?: string;
}

export interface ObgynReferralMessage {
  id: string;
  senderUserId: string;
  senderDisplayName?: string;
  body: string;
  read: boolean;
  sentAt: string;
  attachments?: {
    storageKey?: string;
    displayName?: string;
    contentType?: string;
    sizeBytes?: number;
  }[];
}

export interface ObgynReferralResponse {
  id: string;
  patient?: ReferralPatientSummary;
  hospital?: ReferralHospitalSummary;
  midwife?: ReferralClinicianSummary;
  obgyn?: ReferralClinicianSummary | null;
  gestationalAgeWeeks?: number | null;
  careContext?: ObgynReferralCareContext;
  referralReason?: string;
  clinicalIndication?: string;
  urgency?: ObgynReferralUrgency;
  historySummary?: string;
  ongoingMidwiferyCare?: boolean;
  transferType?: ObgynTransferType;
  attachmentsPresent?: boolean;
  acknowledgementTimestamp?: string | null;
  planSummary?: string | null;
  completionTimestamp?: string | null;
  cancelledTimestamp?: string | null;
  cancellationReason?: string | null;
  status: ObgynReferralStatus;
  slaDueAt?: string | null;
  careTeamUpdatedAt?: string | null;
  letterStoragePath?: string | null;
  letterGeneratedAt?: string | null;
  createdAt?: string;
  updatedAt?: string;
  attachments?: ReferralAttachment[];
  messages?: ObgynReferralMessage[];
}

export interface ObgynReferralCreateRequest {
  patientId: string;
  hospitalId: string;
  gestationalAgeWeeks?: number;
  careContext: ObgynReferralCareContext;
  referralReason: string;
  clinicalIndication?: string;
  urgency: ObgynReferralUrgency;
  historySummary?: string;
  ongoingMidwiferyCare?: boolean;
  transferType: ObgynTransferType;
  generateLetter?: boolean;
}

export interface ReferralStatusSummary {
  submitted: number;
  acknowledged: number;
  inProgress: number;
  completed: number;
  cancelled: number;
  overdue: number;
}

/**
 * OB/GYN referral API — /referrals/obgyn (distinct lifecycle from the general
 * /referrals controller: create → acknowledge → start → complete, cancel as
 * the abort path; no decline/reject). Responses are bare DTOs (unwrapped).
 * Effective backend roles: create/message = MIDWIFE/DOCTOR/SUPER_ADMIN;
 * acknowledge/start/complete = DOCTOR/SUPER_ADMIN; cancel = MIDWIFE/
 * SUPER_ADMIN; hospital list = HOSPITAL_ADMIN/SUPER_ADMIN only.
 */
@Injectable({ providedIn: 'root' })
export class ObgynReferralService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/referrals/obgyn';

  create(req: ObgynReferralCreateRequest): Observable<ObgynReferralResponse> {
    return this.http.post<ObgynReferralResponse>(this.baseUrl, req);
  }

  getById(id: string): Observable<ObgynReferralResponse> {
    return this.http.get<ObgynReferralResponse>(`${this.baseUrl}/${id}`);
  }

  byPatient(
    patientId: string,
    page = 0,
    size = 20,
  ): Observable<PageResponse<ObgynReferralResponse>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<PageResponse<ObgynReferralResponse>>(
      `${this.baseUrl}/patient/${patientId}`,
      { params },
    );
  }

  /** HOSPITAL_ADMIN/SUPER_ADMIN only on the backend. */
  byHospital(
    hospitalId: string,
    page = 0,
    size = 20,
  ): Observable<PageResponse<ObgynReferralResponse>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<PageResponse<ObgynReferralResponse>>(
      `${this.baseUrl}/hospital/${hospitalId}`,
      { params },
    );
  }

  /** DOCTOR/SUPER_ADMIN only. `obgynUserId` is a USER id, not a staff id. */
  assignedTo(
    obgynUserId: string,
    page = 0,
    size = 20,
  ): Observable<PageResponse<ObgynReferralResponse>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<PageResponse<ObgynReferralResponse>>(
      `${this.baseUrl}/assigned/${obgynUserId}`,
      { params },
    );
  }

  acknowledge(
    id: string,
    obgynUserId: string,
    planSummary: string,
  ): Observable<ObgynReferralResponse> {
    return this.http.post<ObgynReferralResponse>(`${this.baseUrl}/${id}/acknowledge`, {
      obgynUserId,
      planSummary,
    });
  }

  start(id: string): Observable<ObgynReferralResponse> {
    return this.http.post<ObgynReferralResponse>(`${this.baseUrl}/${id}/start`, null);
  }

  complete(
    id: string,
    planSummary: string,
    updateCareTeam: boolean,
  ): Observable<ObgynReferralResponse> {
    return this.http.post<ObgynReferralResponse>(`${this.baseUrl}/${id}/complete`, {
      planSummary,
      updateCareTeam,
    });
  }

  cancel(id: string, reason: string): Observable<ObgynReferralResponse> {
    return this.http.post<ObgynReferralResponse>(`${this.baseUrl}/${id}/cancel`, { reason });
  }

  messages(id: string): Observable<ObgynReferralMessage[]> {
    return this.http.get<ObgynReferralMessage[]>(`${this.baseUrl}/${id}/messages`);
  }

  postMessage(id: string, body: string): Observable<ObgynReferralMessage> {
    return this.http.post<ObgynReferralMessage>(`${this.baseUrl}/${id}/messages`, {
      body,
      attachments: [],
    });
  }

  summary(): Observable<ReferralStatusSummary> {
    return this.http.get<ReferralStatusSummary>(`${this.baseUrl}/reports/summary`);
  }
}
