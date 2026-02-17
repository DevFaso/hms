import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type AppointmentStatus =
  | 'SCHEDULED'
  | 'CONFIRMED'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'RESCHEDULED'
  | 'NO_SHOW'
  | 'FAILED'
  | 'REQUESTED';

export interface AppointmentResponse {
  id: string;
  patientId: string;
  patientName: string;
  patientEmail: string;
  patientPhone: string | null;
  staffId: string;
  staffName: string;
  staffEmail: string;
  hospitalId: string;
  hospitalName: string;
  hospitalAddress: string | null;
  departmentId?: string | null;
  createdById: string;
  createdByName: string;
  reason: string;
  notes?: string | null;
  appointmentDate: string;
  startTime: string;
  endTime: string;
  status: AppointmentStatus;
  createdAt: string;
  updatedAt: string;
}

export interface AppointmentUpsertRequest {
  appointmentDate: string;
  startTime: string;
  endTime: string;
  status: AppointmentStatus;
  patientId?: string;
  patientEmail?: string;
  staffId?: string;
  staffEmail?: string;
  hospitalId?: string;
  hospitalName?: string;
  departmentId?: string;
  reason?: string;
  notes?: string;
}

export interface AppointmentFilterRequest {
  patientId?: string;
  staffId?: string;
  hospitalId?: string;
  statuses?: AppointmentStatus[];
  fromDate?: string;
  toDate?: string;
  search?: string;
}

@Injectable({ providedIn: 'root' })
export class AppointmentService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/appointments';

  list(filters?: AppointmentFilterRequest): Observable<AppointmentResponse[]> {
    let params = new HttpParams();
    if (filters) {
      if (filters.patientId) params = params.set('patientId', filters.patientId);
      if (filters.staffId) params = params.set('staffId', filters.staffId);
      if (filters.hospitalId) params = params.set('hospitalId', filters.hospitalId);
      if (filters.search) params = params.set('search', filters.search);
      if (filters.fromDate) params = params.set('fromDate', filters.fromDate);
      if (filters.toDate) params = params.set('toDate', filters.toDate);
    }
    return this.http.get<AppointmentResponse[]>(this.baseUrl, { params });
  }

  getById(id: string): Observable<AppointmentResponse> {
    return this.http.get<AppointmentResponse>(`${this.baseUrl}/${id}`);
  }

  create(req: AppointmentUpsertRequest): Observable<AppointmentResponse> {
    return this.http.post<AppointmentResponse>(this.baseUrl, req);
  }

  update(id: string, req: AppointmentUpsertRequest): Observable<AppointmentResponse> {
    return this.http.put<AppointmentResponse>(`${this.baseUrl}/${id}`, req);
  }

  updateStatus(id: string, action: string): Observable<AppointmentResponse> {
    return this.http.patch<AppointmentResponse>(`${this.baseUrl}/${id}/status`, { action });
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }
}
