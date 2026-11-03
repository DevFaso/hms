import { unwrapDepartmentList } from './department-lookup.service';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { AllRows, PageSlice, allPages } from '../shared/all-pages';

/** QA D1390: open slots per page of the slot admin list. */
export const SLOT_PAGE_SIZE = 50;

/** QA D1390: the open-slot search filter (the window defaults to two weeks on the server). */
export interface OpenSlotFilter {
  departmentId?: string;
  staffId?: string;
  visitTypeId?: string;
  from?: string;
  to?: string;
}

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

  /**
   * QA D1390 (D1347 class): one page of the open slots of the window WITH the
   * total. `/slots/search` answers a bare list capped at 200 (the slot admin
   * list showed 182 of about 285 and said nothing).
   */
  searchOpenPage(
    filter: OpenSlotFilter,
    page: number,
    size: number = SLOT_PAGE_SIZE,
  ): Observable<PageSlice<AppointmentSlotResponse>> {
    let params = new HttpParams().set('page', page).set('size', size);
    for (const [key, value] of Object.entries(filter)) {
      if (value !== undefined && value !== null && value !== '') {
        params = params.set(key, value);
      }
    }
    return this.http.get<PageSlice<AppointmentSlotResponse>>('/slots/search/page', { params });
  }

  /** QA D1390: every open slot of the window (all pages), for a picker that lists them all. */
  searchAllOpen(filter: OpenSlotFilter): Observable<AllRows<AppointmentSlotResponse>> {
    return allPages((page, size) => this.searchOpenPage(filter, page, size));
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

  /**
   * Book a slot for a patient (P3 #22). Creates a normal appointment and
   * stamps the slot BOOKED — the appointment owns the time from then on.
   */
  book(slotId: string, patientId: string, reason?: string): Observable<AppointmentSlotResponse> {
    return this.http.post<AppointmentSlotResponse>(`/slots/${slotId}/book`, {
      patientId,
      reason: reason ?? null,
    });
  }

  // ── Options ────────────────────────────────────────────────

  listDepartments(): Observable<DepartmentOption[]> {
    return this.http
      .get<DepartmentOption[]>('/departments')
      .pipe(map((res) => unwrapDepartmentList(res)));
  }
}
