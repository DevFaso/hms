import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

export type EncounterStatus =
  | 'SCHEDULED'
  | 'ARRIVED'
  | 'TRIAGE'
  | 'WAITING_FOR_PHYSICIAN'
  | 'IN_PROGRESS'
  | 'AWAITING_RESULTS'
  | 'READY_FOR_DISCHARGE'
  | 'COMPLETED'
  | 'CANCELLED';
export type EncounterType =
  | 'CONSULTATION'
  | 'FOLLOW_UP'
  | 'EMERGENCY'
  | 'SURGERY'
  | 'LAB'
  | 'OUTPATIENT'
  | 'INPATIENT';

export interface EncounterNoteResponse {
  id: string;
  encounterId: string;
  content: string;
  authorName: string;
  createdAt: string;
}

export type EncounterUrgency = 'EMERGENT' | 'URGENT' | 'ROUTINE' | 'LOW';

export interface EncounterResponse {
  id: string;
  patientId: string;
  patientName: string;
  patientEmail: string;
  patientPhoneNumber: string;
  staffId: string;
  staffName: string;
  staffEmail: string;
  departmentId: string;
  departmentName: string;
  hospitalId: string;
  hospitalName: string;
  appointmentId: string;
  appointmentReason: string;
  appointmentNotes: string;
  appointmentStatus: string;
  appointmentType: string;
  appointmentDate: string;
  encounterType: EncounterType;
  status: EncounterStatus;
  encounterDate: string;
  notes: string;
  arrivalTimestamp: string | null;
  chiefComplaint: string;
  esiScore: number | null;
  roomAssignment: string;
  triageTimestamp: string | null;
  roomedTimestamp: string | null;
  urgency: EncounterUrgency | null;
  createdAt: string;
  updatedAt: string;
  createdBy: string;
  updatedBy: string;
  note: EncounterNoteResponse;
  patientFullName: string;
  staffFullName: string;
  nursingIntakeTimestamp: string | null;
}

export interface TriageSubmissionRequest {
  temperatureCelsius?: number;
  heartRateBpm?: number;
  respiratoryRateBpm?: number;
  systolicBpMmHg?: number;
  diastolicBpMmHg?: number;
  spo2Percent?: number;
  weightKg?: number;
  heightCm?: number;
  painScale?: number;
  chiefComplaint?: string;
  esiScore: number;
  fallRisk?: boolean;
  fallRiskScore?: number;
  roomAssignment?: string;
}

export interface TriageSubmissionResponse {
  encounterId: string;
  encounterStatus: EncounterStatus;
  esiScore: number;
  urgency: EncounterUrgency;
  roomAssignment: string;
  triageTimestamp: string;
  roomedTimestamp: string;
  chiefComplaint: string;
  vitalSignId: string;
}

export interface MedicationReconciliationEntry {
  medicationName?: string;
  dosage?: string;
  frequency?: string;
  route?: string;
  stillTaking?: boolean;
  notes?: string;
}

export interface NursingIntakeRequest {
  allergies?: AllergyEntry[];
  medications?: MedicationReconciliationEntry[];
  nursingAssessmentNotes?: string;
  chiefComplaint?: string;
  painAssessment?: string;
  fallRiskDetail?: string;
}

export interface AllergyEntry {
  allergenDisplay: string;
  allergenCode?: string;
  category?: string;
  severity?: string;
  reaction?: string;
  reactionNotes?: string;
}

export interface NursingIntakeResponse {
  encounterId: string;
  encounterStatus: EncounterStatus;
  intakeTimestamp: string;
  allergyCount: number;
  medicationCount: number;
  nursingNoteRecorded: boolean;
}

/* ── MVP 6: Check-Out & AVS ─────────────────────────────────────── */

export interface FollowUpAppointmentRequest {
  reason: string;
  preferredDate?: string;
  notes?: string;
}

export interface CheckOutRequest {
  followUpInstructions?: string;
  dischargeDiagnoses?: string[];
  prescriptionSummary?: string;
  referralSummary?: string;
  patientEducationMaterials?: string;
  followUpAppointment?: FollowUpAppointmentRequest;
}

export interface AfterVisitSummary {
  encounterId: string;
  appointmentId?: string;
  visitDate: string;
  providerName: string;
  departmentName: string;
  hospitalName: string;
  patientId?: string;
  patientName?: string;
  chiefComplaint?: string;
  dischargeDiagnoses: string[];
  prescriptionSummary?: string;
  referralSummary?: string;
  followUpInstructions?: string;
  patientEducationMaterials?: string;
  encounterStatus: string;
  appointmentStatus?: string;
  checkoutTimestamp: string;
  followUpAppointmentId?: string;
  followUpAppointmentDate?: string;
}

export interface EncounterRequest {
  patientId: string;
  staffId: string;
  hospitalId: string;
  departmentId?: string;
  appointmentId?: string;
  encounterType: EncounterType;
  encounterDate: string;
  notes?: string;
}

export interface EncounterNoteRequest {
  template: 'SOAP' | 'SOAPIE';
  summary: string;
}

export interface EncounterFilterRequest {
  patientId?: string;
  staffId?: string;
  hospitalId?: string;
  status?: EncounterStatus;
  fromDate?: string;
  toDate?: string;
}

@Injectable({ providedIn: 'root' })
export class EncounterService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/encounters';

  list(filters?: EncounterFilterRequest): Observable<EncounterResponse[]> {
    let params = new HttpParams();
    if (filters) {
      if (filters.patientId) params = params.set('patientId', filters.patientId);
      if (filters.staffId) params = params.set('staffId', filters.staffId);
      if (filters.hospitalId) params = params.set('hospitalId', filters.hospitalId);
      if (filters.status) params = params.set('status', filters.status);
      if (filters.fromDate) params = params.set('from', filters.fromDate);
      if (filters.toDate) params = params.set('to', filters.toDate);
    }
    return this.http
      .get<{ content: EncounterResponse[] }>(this.baseUrl, { params })
      .pipe(map((res) => res?.content ?? []));
  }

  getById(id: string): Observable<EncounterResponse> {
    return this.http.get<EncounterResponse>(`${this.baseUrl}/${id}`);
  }

  create(req: EncounterRequest): Observable<EncounterResponse> {
    return this.http.post<EncounterResponse>(this.baseUrl, req);
  }

  update(id: string, req: EncounterRequest): Observable<EncounterResponse> {
    return this.http.put<EncounterResponse>(`${this.baseUrl}/${id}`, req);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  getByDoctor(identifier: string): Observable<EncounterResponse[]> {
    return this.http.get<EncounterResponse[]>(`${this.baseUrl}/doctor/${identifier}`);
  }

  addNote(encounterId: string, req: EncounterNoteRequest): Observable<EncounterNoteResponse> {
    return this.http.post<EncounterNoteResponse>(`${this.baseUrl}/${encounterId}/notes`, req);
  }

  addAddendum(encounterId: string, req: EncounterNoteRequest): Observable<EncounterNoteResponse> {
    return this.http.post<EncounterNoteResponse>(
      `${this.baseUrl}/${encounterId}/notes/addendums`,
      req,
    );
  }

  getNoteHistory(encounterId: string): Observable<EncounterNoteResponse[]> {
    return this.http.get<EncounterNoteResponse[]>(`${this.baseUrl}/${encounterId}/notes/history`);
  }

  submitTriage(
    encounterId: string,
    req: TriageSubmissionRequest,
  ): Observable<TriageSubmissionResponse> {
    return this.http.post<TriageSubmissionResponse>(`${this.baseUrl}/${encounterId}/triage`, req);
  }

  submitNursingIntake(
    encounterId: string,
    req: NursingIntakeRequest,
  ): Observable<NursingIntakeResponse> {
    return this.http.post<NursingIntakeResponse>(
      `${this.baseUrl}/${encounterId}/nursing-intake`,
      req,
    );
  }

  checkOut(encounterId: string, req: CheckOutRequest): Observable<AfterVisitSummary> {
    return this.http.post<AfterVisitSummary>(`${this.baseUrl}/${encounterId}/checkout`, req);
  }

  startEncounter(encounterId: string): Observable<EncounterResponse> {
    return this.http.post<EncounterResponse>(`${this.baseUrl}/${encounterId}/start`, {});
  }

  completeTriage(encounterId: string): Observable<EncounterResponse> {
    return this.http.post<EncounterResponse>(`${this.baseUrl}/${encounterId}/complete-triage`, {});
  }
}
