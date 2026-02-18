import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface NurseVitalTask {
  id: string;
  patientId: string;
  patientName: string;
  type: string;
  dueTime: string;
  overdue: boolean;
}

export interface NurseMedicationTask {
  id: string;
  patientId: string;
  patientName: string;
  medication: string;
  dose: string;
  route: string;
  dueTime: string;
  status: string;
}

export interface NurseOrderTask {
  id: string;
  patientId: string;
  patientName: string;
  orderType: string;
  priority: string;
  dueTime: string;
}

export interface NurseHandoff {
  id: string;
  patientId: string;
  patientName: string;
  direction: string;
  updatedAt: string;
  note: string;
}

export interface NurseAnnouncement {
  id: string;
  text: string;
  createdAt: string;
  startsAt: string;
  expiresAt: string;
  category: string;
}

@Injectable({ providedIn: 'root' })
export class NurseTaskService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/nurse';

  getVitalsDue(params?: {
    hospitalId?: string;
    window?: string;
    assignee?: string;
  }): Observable<NurseVitalTask[]> {
    let httpParams = new HttpParams();
    if (params?.hospitalId) httpParams = httpParams.set('hospitalId', params.hospitalId);
    if (params?.window) httpParams = httpParams.set('window', params.window);
    if (params?.assignee) httpParams = httpParams.set('assignee', params.assignee);
    return this.http.get<NurseVitalTask[]>(`${this.baseUrl}/vitals/due`, { params: httpParams });
  }

  getMedicationMAR(params?: {
    hospitalId?: string;
    assignee?: string;
    status?: string;
  }): Observable<NurseMedicationTask[]> {
    let httpParams = new HttpParams();
    if (params?.hospitalId) httpParams = httpParams.set('hospitalId', params.hospitalId);
    if (params?.assignee) httpParams = httpParams.set('assignee', params.assignee);
    if (params?.status) httpParams = httpParams.set('status', params.status);
    return this.http.get<NurseMedicationTask[]>(`${this.baseUrl}/medications/mar`, {
      params: httpParams,
    });
  }

  administerMedication(
    taskId: string,
    data: { status: string; note?: string },
  ): Observable<NurseMedicationTask> {
    return this.http.put<NurseMedicationTask>(
      `${this.baseUrl}/medications/mar/${taskId}/administer`,
      data,
    );
  }

  getOrders(params?: {
    hospitalId?: string;
    assignee?: string;
    status?: string;
    limit?: number;
  }): Observable<NurseOrderTask[]> {
    let httpParams = new HttpParams();
    if (params?.hospitalId) httpParams = httpParams.set('hospitalId', params.hospitalId);
    if (params?.assignee) httpParams = httpParams.set('assignee', params.assignee);
    if (params?.status) httpParams = httpParams.set('status', params.status);
    if (params?.limit != null) httpParams = httpParams.set('limit', params.limit);
    return this.http.get<NurseOrderTask[]>(`${this.baseUrl}/orders`, { params: httpParams });
  }

  getHandoffs(params?: {
    hospitalId?: string;
    assignee?: string;
    limit?: number;
  }): Observable<NurseHandoff[]> {
    let httpParams = new HttpParams();
    if (params?.hospitalId) httpParams = httpParams.set('hospitalId', params.hospitalId);
    if (params?.assignee) httpParams = httpParams.set('assignee', params.assignee);
    if (params?.limit != null) httpParams = httpParams.set('limit', params.limit);
    return this.http.get<NurseHandoff[]>(`${this.baseUrl}/handoffs`, { params: httpParams });
  }

  completeHandoff(handoffId: string): Observable<void> {
    return this.http.put<void>(`${this.baseUrl}/handoffs/${handoffId}/complete`, {});
  }

  updateHandoffTask(handoffId: string, taskId: string, completed: boolean): Observable<unknown> {
    return this.http.patch(`${this.baseUrl}/handoffs/${handoffId}/tasks/${taskId}`, { completed });
  }

  getAnnouncements(params?: { hospitalId?: string }): Observable<NurseAnnouncement[]> {
    let httpParams = new HttpParams();
    if (params?.hospitalId) httpParams = httpParams.set('hospitalId', params.hospitalId);
    return this.http.get<NurseAnnouncement[]>(`${this.baseUrl}/announcements`, {
      params: httpParams,
    });
  }
}
