import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

// ── Shift ──
export type StaffShiftType = 'MORNING' | 'AFTERNOON' | 'EVENING' | 'NIGHT' | 'FLEX';
export type StaffShiftStatus = 'SCHEDULED' | 'COMPLETED' | 'CANCELLED';

export interface StaffShiftRequest {
  staffId: string;
  hospitalId: string;
  departmentId?: string;
  shiftDate: string;
  startTime: string;
  endTime: string;
  shiftType: StaffShiftType;
  notes?: string;
}

export interface StaffShiftResponse {
  id: string;
  staffId: string;
  staffName: string;
  staffRole: string;
  hospitalId: string;
  hospitalName: string;
  departmentId?: string;
  departmentName?: string;
  shiftDate: string;
  startTime: string;
  endTime: string;
  shiftType: StaffShiftType;
  status: StaffShiftStatus;
  published: boolean;
  notes?: string;
  cancellationReason?: string;
  scheduledByUserId: string;
  scheduledByName: string;
  lastModifiedByUserId?: string;
  lastModifiedByName?: string;
  statusChangedAt?: string;
  createdAt: string;
  updatedAt?: string;
}

export interface StaffShiftStatusUpdate {
  status: StaffShiftStatus;
  cancellationReason?: string;
}

// ── Leave ──
export type StaffLeaveType = 'VACATION' | 'SICK' | 'EMERGENCY' | 'TRAINING' | 'UNPAID' | 'OTHER';
export type StaffLeaveStatus = 'PENDING' | 'APPROVED' | 'DENIED' | 'CANCELLED';

export interface StaffLeaveRequest {
  staffId: string;
  hospitalId: string;
  departmentId?: string;
  startDate: string;
  endDate: string;
  startTime?: string;
  endTime?: string;
  leaveType: StaffLeaveType;
  requiresCoverage: boolean;
  reason?: string;
}

export interface StaffLeaveResponse {
  id: string;
  staffId: string;
  staffName: string;
  staffRole: string;
  hospitalId: string;
  hospitalName: string;
  departmentId?: string;
  departmentName?: string;
  leaveType: StaffLeaveType;
  status: StaffLeaveStatus;
  startDate: string;
  endDate: string;
  startTime?: string;
  endTime?: string;
  requiresCoverage: boolean;
  reason?: string;
  managerNote?: string;
  requestedByUserId: string;
  requestedByName: string;
  reviewedByUserId?: string;
  reviewedByName?: string;
  reviewedAt?: string;
  createdAt: string;
  updatedAt?: string;
}

export interface StaffLeaveDecision {
  status: StaffLeaveStatus;
  managerNote?: string;
}

// ── Availability ──
export interface StaffAvailabilityRequest {
  staffId: string;
  hospitalId: string;
  departmentId: string;
  date: string;
  availableFrom?: string;
  availableTo?: string;
  dayOff: boolean;
  note?: string;
}

export interface StaffAvailabilityResponse {
  id: string;
  staffId: string;
  staffName: string;
  staffLicenseNumber?: string;
  hospitalId: string;
  hospitalName: string;
  departmentId?: string;
  departmentName?: string;
  departmentTranslationName?: string;
  date: string;
  availableFrom?: string;
  availableTo?: string;
  dayOff: boolean;
  note?: string;
}

@Injectable({ providedIn: 'root' })
export class StaffSchedulingService {
  private readonly http = inject(HttpClient);

  // ── Shifts ──
  listShifts(filters?: {
    hospitalId?: string;
    departmentId?: string;
    staffId?: string;
    startDate?: string;
    endDate?: string;
  }): Observable<StaffShiftResponse[]> {
    let params = new HttpParams();
    if (filters?.hospitalId) params = params.set('hospitalId', filters.hospitalId);
    if (filters?.departmentId) params = params.set('departmentId', filters.departmentId);
    if (filters?.staffId) params = params.set('staffId', filters.staffId);
    if (filters?.startDate) params = params.set('startDate', filters.startDate);
    if (filters?.endDate) params = params.set('endDate', filters.endDate);
    return this.http.get<StaffShiftResponse[]>('/staff/scheduling/shifts', { params });
  }

  createShift(req: StaffShiftRequest): Observable<StaffShiftResponse> {
    return this.http.post<StaffShiftResponse>('/staff/scheduling/shifts', req);
  }

  updateShift(shiftId: string, req: StaffShiftRequest): Observable<StaffShiftResponse> {
    return this.http.put<StaffShiftResponse>(`/staff/scheduling/shifts/${shiftId}`, req);
  }

  updateShiftStatus(shiftId: string, req: StaffShiftStatusUpdate): Observable<StaffShiftResponse> {
    return this.http.patch<StaffShiftResponse>(`/staff/scheduling/shifts/${shiftId}/status`, req);
  }

  // ── Leaves ──
  listLeaves(filters?: {
    hospitalId?: string;
    departmentId?: string;
    staffId?: string;
    startDate?: string;
    endDate?: string;
  }): Observable<StaffLeaveResponse[]> {
    let params = new HttpParams();
    if (filters?.hospitalId) params = params.set('hospitalId', filters.hospitalId);
    if (filters?.departmentId) params = params.set('departmentId', filters.departmentId);
    if (filters?.staffId) params = params.set('staffId', filters.staffId);
    if (filters?.startDate) params = params.set('startDate', filters.startDate);
    if (filters?.endDate) params = params.set('endDate', filters.endDate);
    return this.http.get<StaffLeaveResponse[]>('/staff/scheduling/leaves', { params });
  }

  requestLeave(req: StaffLeaveRequest): Observable<StaffLeaveResponse> {
    return this.http.post<StaffLeaveResponse>('/staff/scheduling/leaves', req);
  }

  decideLeave(leaveId: string, decision: StaffLeaveDecision): Observable<StaffLeaveResponse> {
    return this.http.patch<StaffLeaveResponse>(
      `/staff/scheduling/leaves/${leaveId}/decision`,
      decision,
    );
  }

  cancelLeave(leaveId: string): Observable<StaffLeaveResponse> {
    return this.http.patch<StaffLeaveResponse>(`/staff/scheduling/leaves/${leaveId}/cancel`, {});
  }

  // ── Availability ──
  checkAvailability(staffId: string, datetime: string): Observable<boolean> {
    const params = new HttpParams().set('staffId', staffId).set('datetime', datetime);
    return this.http.get<boolean>('/availability/check', { params });
  }

  createAvailability(req: StaffAvailabilityRequest): Observable<StaffAvailabilityResponse> {
    return this.http.post<StaffAvailabilityResponse>('/availability', req);
  }
}
