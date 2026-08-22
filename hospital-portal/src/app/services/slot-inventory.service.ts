import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

/**
 * Slot inventory (P2 #11): visit types → session templates → generated slots.
 *
 * The foundation (PR #459) shipped all five /slots endpoints with no portal
 * caller and no way to populate the two parent tables — generate() could only
 * ever answer slotsCreated=0. This service is the first caller of all of it.
 */

export type SlotStatus = 'OPEN' | 'HELD' | 'BOOKED' | 'BLOCKED';

export interface VisitTypeRequest {
  departmentId?: string;
  code: string;
  name: string;
  description?: string;
  durationMinutes: number;
  patientBookable?: boolean;
}

export interface VisitTypeResponse {
  id: string;
  departmentId?: string;
  departmentName?: string;
  code: string;
  name: string;
  description?: string;
  durationMinutes: number;
  patientBookable: boolean;
  active: boolean;
}

export interface SessionTemplateRequest {
  staffId: string;
  departmentId: string;
  visitTypeId?: string;
  /** ISO-8601: 1 = Monday .. 7 = Sunday. */
  dayOfWeek: number;
  /** "HH:mm" — serialised as LocalTime. */
  startTime: string;
  endTime: string;
  slotMinutes: number;
  effectiveFrom: string;
  effectiveTo?: string;
  notes?: string;
}

export interface SessionTemplateResponse {
  id: string;
  staffId: string;
  staffName?: string;
  departmentId: string;
  departmentName?: string;
  visitTypeId?: string;
  visitTypeName?: string;
  dayOfWeek: number;
  startTime: string;
  endTime: string;
  slotMinutes: number;
  effectiveFrom: string;
  effectiveTo?: string;
  active: boolean;
  notes?: string;
}

export interface AppointmentSlotResponse {
  id: string;
  staffId?: string;
  staffName?: string;
  departmentId?: string;
  departmentName?: string;
  visitTypeId?: string;
  visitTypeName?: string;
  durationMinutes?: number;
  slotDate: string;
  startAt: string;
  endAt: string;
  status: SlotStatus;
  appointmentId?: string;
}

export interface SlotGenerationResult {
  from: string;
  to: string;
  templatesApplied: number;
  slotsCreated: number;
  skippedExisting: number;
}

export interface DepartmentOption {
  id: string;
  name: string;
}

@Injectable({ providedIn: 'root' })
export class SlotInventoryService {
  private readonly http = inject(HttpClient);

  // ── Visit types ────────────────────────────────────────────

  listVisitTypes(includeInactive = false): Observable<VisitTypeResponse[]> {
    const params = new HttpParams().set('includeInactive', includeInactive);
    return this.http.get<VisitTypeResponse[]>('/visit-types', { params });
  }

  createVisitType(request: VisitTypeRequest): Observable<VisitTypeResponse> {
    return this.http.post<VisitTypeResponse>('/visit-types', request);
  }

  updateVisitType(id: string, request: VisitTypeRequest): Observable<VisitTypeResponse> {
    return this.http.put<VisitTypeResponse>(`/visit-types/${id}`, request);
  }

  deactivateVisitType(id: string): Observable<VisitTypeResponse> {
    return this.http.put<VisitTypeResponse>(`/visit-types/${id}/deactivate`, {});
  }

  reactivateVisitType(id: string): Observable<VisitTypeResponse> {
    return this.http.put<VisitTypeResponse>(`/visit-types/${id}/reactivate`, {});
  }

  // ── Session templates ──────────────────────────────────────

  listTemplates(includeInactive = false): Observable<SessionTemplateResponse[]> {
    const params = new HttpParams().set('includeInactive', includeInactive);
    return this.http.get<SessionTemplateResponse[]>('/session-templates', { params });
  }

  createTemplate(request: SessionTemplateRequest): Observable<SessionTemplateResponse> {
    return this.http.post<SessionTemplateResponse>('/session-templates', request);
  }

  updateTemplate(id: string, request: SessionTemplateRequest): Observable<SessionTemplateResponse> {
    return this.http.put<SessionTemplateResponse>(`/session-templates/${id}`, request);
  }

  deactivateTemplate(id: string): Observable<SessionTemplateResponse> {
    return this.http.put<SessionTemplateResponse>(`/session-templates/${id}/deactivate`, {});
  }

  reactivateTemplate(id: string): Observable<SessionTemplateResponse> {
    return this.http.put<SessionTemplateResponse>(`/session-templates/${id}/reactivate`, {});
  }

  // ── Slots ──────────────────────────────────────────────────

  generate(from?: string, to?: string): Observable<SlotGenerationResult> {
    let params = new HttpParams();
    if (from) {
      params = params.set('from', from);
    }
    if (to) {
      params = params.set('to', to);
    }
    return this.http.post<SlotGenerationResult>('/slots/generate', null, { params });
  }

  searchOpen(filter: {
    departmentId?: string;
    staffId?: string;
    visitTypeId?: string;
    from?: string;
    to?: string;
    limit?: number;
  }): Observable<AppointmentSlotResponse[]> {
    let params = new HttpParams();
    for (const [key, value] of Object.entries(filter)) {
      if (value !== undefined && value !== null && value !== '') {
        params = params.set(key, value);
      }
    }
    return this.http.get<AppointmentSlotResponse[]>('/slots/search', { params });
  }

  block(slotId: string, reason?: string): Observable<AppointmentSlotResponse> {
    let params = new HttpParams();
    if (reason) {
      params = params.set('reason', reason);
    }
    return this.http.post<AppointmentSlotResponse>(`/slots/${slotId}/block`, null, { params });
  }

  release(slotId: string): Observable<AppointmentSlotResponse> {
    return this.http.post<AppointmentSlotResponse>(`/slots/${slotId}/release`, null, {});
  }

  // ── Options ────────────────────────────────────────────────

  listDepartments(): Observable<DepartmentOption[]> {
    return this.http
      .get<{ content: DepartmentOption[] }>('/departments')
      .pipe(map((page) => page.content ?? []));
  }
}
