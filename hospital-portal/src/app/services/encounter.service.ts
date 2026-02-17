import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

export type EncounterStatus = 'OPEN' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED' | 'DISCHARGED';
export type EncounterType =
  | 'OUTPATIENT'
  | 'INPATIENT'
  | 'EMERGENCY'
  | 'TELEMEDICINE'
  | 'HOME_VISIT';

export interface EncounterNoteResponse {
  id: string;
  encounterId: string;
  content: string;
  authorName: string;
  createdAt: string;
}

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
  createdAt: string;
  updatedAt: string;
  createdBy: string;
  updatedBy: string;
  note: EncounterNoteResponse;
  patientFullName: string;
  staffFullName: string;
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
  content: string;
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
}
