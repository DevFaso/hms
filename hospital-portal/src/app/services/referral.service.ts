import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type ReferralStatus =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'ACKNOWLEDGED'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'OVERDUE';

export interface ReferralResponse {
  id: string;
  patientId: string;
  patientName: string;
  patientMrn: string;
  hospitalId: string;
  hospitalName: string;
  referringProviderId: string;
  referringProviderName: string;
  receivingProviderId: string;
  receivingProviderName: string;
  targetSpecialty: string;
  targetDepartmentId: string;
  targetDepartmentName: string;
  targetFacilityName: string;
  referralType: string;
  referralReason: string;
  clinicalIndication: string;
  clinicalSummary: string;
  clinicalQuestion: string;
  anticipatedTreatment: string;
  urgency: string;
  status: ReferralStatus;
  submittedAt: string;
  slaDueAt: string;
  acknowledgedAt: string;
  acknowledgementNotes: string;
  scheduledAppointmentAt: string;
  completedAt: string;
  cancelledAt: string;
  createdAt: string;
  updatedAt: string;
}

export interface ReferralRequest {
  patientId: string;
  hospitalId: string;
  targetSpecialty: string;
  referralReason: string;
  clinicalIndication?: string;
  clinicalSummary?: string;
  urgency: string;
  referralType?: string;
  receivingProviderId?: string;
  targetDepartmentId?: string;
  targetFacilityName?: string;
}

@Injectable({ providedIn: 'root' })
export class ReferralService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/referrals';

  create(req: ReferralRequest): Observable<ReferralResponse> {
    return this.http.post<ReferralResponse>(this.baseUrl, req);
  }

  getById(id: string): Observable<ReferralResponse> {
    return this.http.get<ReferralResponse>(`${this.baseUrl}/${id}`);
  }

  submit(id: string): Observable<ReferralResponse> {
    return this.http.post<ReferralResponse>(`${this.baseUrl}/${id}/submit`, {});
  }

  acknowledge(id: string): Observable<ReferralResponse> {
    return this.http.post<ReferralResponse>(`${this.baseUrl}/${id}/acknowledge`, {});
  }

  complete(id: string, data: Record<string, unknown>): Observable<ReferralResponse> {
    return this.http.post<ReferralResponse>(`${this.baseUrl}/${id}/complete`, data);
  }

  cancel(id: string, reason: string): Observable<ReferralResponse> {
    return this.http.post<ReferralResponse>(`${this.baseUrl}/${id}/cancel`, { reason });
  }

  getByPatient(patientId: string): Observable<ReferralResponse[]> {
    return this.http.get<ReferralResponse[]>(`${this.baseUrl}/patient/${patientId}`);
  }

  getReferring(providerId: string): Observable<ReferralResponse[]> {
    return this.http.get<ReferralResponse[]>(`${this.baseUrl}/provider/${providerId}/referring`);
  }

  getReceiving(providerId: string): Observable<ReferralResponse[]> {
    return this.http.get<ReferralResponse[]>(`${this.baseUrl}/provider/${providerId}/receiving`);
  }

  getByHospital(
    hospitalId: string,
    _params?: { page?: number; size?: number },
  ): Observable<ReferralResponse[]> {
    return this.http.get<ReferralResponse[]>(`${this.baseUrl}/hospital/${hospitalId}`);
  }

  getAll(): Observable<ReferralResponse[]> {
    return this.http.get<ReferralResponse[]>(this.baseUrl);
  }

  getOverdue(): Observable<ReferralResponse[]> {
    return this.http.get<ReferralResponse[]>(`${this.baseUrl}/overdue`);
  }
}
