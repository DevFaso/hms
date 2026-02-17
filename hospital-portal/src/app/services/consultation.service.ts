import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type ConsultationStatus =
  | 'REQUESTED'
  | 'ACKNOWLEDGED'
  | 'SCHEDULED'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'CANCELLED';
export type ConsultationUrgency = 'ROUTINE' | 'URGENT' | 'EMERGENT' | 'STAT';
export type ConsultationType = 'FORMAL' | 'CURBSIDE' | 'TRANSFER_OF_CARE' | 'SECOND_OPINION';

export interface ConsultationResponse {
  id: string;
  patientId: string;
  patientName: string;
  patientMrn: string;
  hospitalId: string;
  hospitalName: string;
  requestingProviderId: string;
  requestingProviderName: string;
  consultantId: string;
  consultantName: string;
  encounterId: string;
  consultationType: ConsultationType;
  specialtyRequested: string;
  reasonForConsult: string;
  clinicalQuestion: string;
  relevantHistory: string;
  currentMedications: string;
  urgency: ConsultationUrgency;
  status: ConsultationStatus;
  requestedAt: string;
  acknowledgedAt: string;
  scheduledAt: string;
  completedAt: string;
  cancelledAt: string;
  cancellationReason: string;
  consultantNote: string;
  recommendations: string;
  followUpRequired: boolean;
  followUpInstructions: string;
  slaDueBy: string;
  isCurbside: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface ConsultationRequest {
  patientId: string;
  hospitalId: string;
  encounterId?: string;
  consultationType: ConsultationType;
  specialtyRequested: string;
  reasonForConsult: string;
  clinicalQuestion?: string;
  relevantHistory?: string;
  currentMedications?: string;
  urgency: ConsultationUrgency;
  preferredConsultantId?: string;
  preferredDateTime?: string;
  isCurbside?: boolean;
}

export interface ConsultationUpdateRequest {
  consultantId: string;
  scheduledAt?: string;
  consultantNote?: string;
  recommendations?: string;
  followUpRequired?: boolean;
  followUpInstructions?: string;
}

@Injectable({ providedIn: 'root' })
export class ConsultationService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/consultations';

  create(req: ConsultationRequest): Observable<ConsultationResponse> {
    return this.http.post<ConsultationResponse>(this.baseUrl, req);
  }

  getById(id: string): Observable<ConsultationResponse> {
    return this.http.get<ConsultationResponse>(`${this.baseUrl}/${id}`);
  }

  getByPatient(patientId: string): Observable<ConsultationResponse[]> {
    return this.http.get<ConsultationResponse[]>(`${this.baseUrl}/patient/${patientId}`);
  }

  getByHospital(
    hospitalId: string,
    _params?: { page?: number; size?: number },
  ): Observable<ConsultationResponse[]> {
    return this.http.get<ConsultationResponse[]>(`${this.baseUrl}/hospital/${hospitalId}`);
  }

  getAll(): Observable<ConsultationResponse[]> {
    return this.http.get<ConsultationResponse[]>(this.baseUrl);
  }

  getPending(hospitalId: string): Observable<ConsultationResponse[]> {
    return this.http.get<ConsultationResponse[]>(`${this.baseUrl}/hospital/${hospitalId}/pending`);
  }

  getRequestedBy(providerId: string): Observable<ConsultationResponse[]> {
    return this.http.get<ConsultationResponse[]>(`${this.baseUrl}/requested-by/${providerId}`);
  }

  getAssignedTo(consultantId: string): Observable<ConsultationResponse[]> {
    return this.http.get<ConsultationResponse[]>(`${this.baseUrl}/assigned-to/${consultantId}`);
  }

  acknowledge(id: string, consultantId: string): Observable<ConsultationResponse> {
    return this.http.post<ConsultationResponse>(`${this.baseUrl}/${id}/acknowledge`, {
      consultantId,
    });
  }

  update(id: string, req: ConsultationUpdateRequest): Observable<ConsultationResponse> {
    return this.http.put<ConsultationResponse>(`${this.baseUrl}/${id}`, req);
  }

  complete(id: string, data: ConsultationUpdateRequest): Observable<ConsultationResponse> {
    return this.http.post<ConsultationResponse>(`${this.baseUrl}/${id}/complete`, data);
  }

  cancel(id: string, reason: string): Observable<ConsultationResponse> {
    return this.http.post<ConsultationResponse>(`${this.baseUrl}/${id}/cancel`, {
      cancellationReason: reason,
    });
  }
}
