import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

/**
 * On-call rota (P2 #13).
 *
 * The backend CRUD shipped in PR #456 with zero portal callers — the table
 * that feeds the dashboard's on-call pill (GET /me/on-call-status) had no
 * writer reachable from any UI, so the pill could only ever say "Off duty".
 */

export interface OnCallScheduleRequest {
  staffId: string;
  /** Null / absent = hospital-wide rota entry, not tied to one department. */
  departmentId?: string;
  /** ISO date-time with offset (backend OffsetDateTime). */
  startTime: string;
  endTime: string;
  notes?: string;
}

export interface OnCallScheduleResponse {
  id: string;
  staffId: string;
  staffName?: string;
  departmentId?: string;
  departmentName?: string;
  startTime: string;
  endTime: string;
  notes?: string;
  /** Computed server-side against read time — not persisted. */
  currentlyOnCall: boolean;
}

/** Minimal department shape for the rota form's scope select. */
export interface DepartmentOption {
  id: string;
  name: string;
}

@Injectable({ providedIn: 'root' })
export class OnCallService {
  private readonly http = inject(HttpClient);

  /** Default window when from/to are omitted: server uses now-1d → now+7d. */
  list(from?: string, to?: string): Observable<OnCallScheduleResponse[]> {
    let params = new HttpParams();
    if (from) {
      params = params.set('from', from);
    }
    if (to) {
      params = params.set('to', to);
    }
    return this.http.get<OnCallScheduleResponse[]>('/on-call', { params });
  }

  listForStaff(staffId: string): Observable<OnCallScheduleResponse[]> {
    return this.http.get<OnCallScheduleResponse[]>(`/on-call/staff/${staffId}`);
  }

  create(request: OnCallScheduleRequest): Observable<OnCallScheduleResponse> {
    return this.http.post<OnCallScheduleResponse>('/on-call', request);
  }

  update(id: string, request: OnCallScheduleRequest): Observable<OnCallScheduleResponse> {
    return this.http.put<OnCallScheduleResponse>(`/on-call/${id}`, request);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`/on-call/${id}`);
  }

  listDepartments(): Observable<DepartmentOption[]> {
    return this.http
      .get<{ content: DepartmentOption[] }>('/departments')
      .pipe(map((page) => page.content ?? []));
  }
}
