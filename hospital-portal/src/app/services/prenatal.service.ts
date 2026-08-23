import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type PrenatalVisitType =
  'INITIAL_INTAKE' | 'ROUTINE_CHECK' | 'ULTRASOUND' | 'LATE_PREGNANCY';

export interface PrenatalScheduleRequest {
  patientId: string;
  hospitalId: string;
  staffId?: string;
  lastMenstrualPeriodDate: string;
  estimatedDueDate?: string;
  highRisk?: boolean;
  supplementalVisitWeeks?: number[];
  notes?: string;
}

export interface PrenatalVisitRecommendation {
  appointmentId?: string | null;
  targetDate?: string;
  windowStart?: string;
  windowEnd?: string;
  suggestedStartTime?: string;
  suggestedEndTime?: string;
  gestationalWeek: number;
  durationMinutes: number;
  visitType: PrenatalVisitType;
  scheduled: boolean;
  recommendation?: string;
  notes?: string;
}

export interface PrenatalAppointmentSummary {
  appointmentId: string;
  staffId?: string;
  departmentId?: string;
  appointmentDate: string;
  startTime?: string;
  endTime?: string;
  status?: string;
  reason?: string;
  gestationalWeek: number;
}

export interface PrenatalScheduleResponse {
  patientId: string;
  hospitalId: string;
  staffId?: string | null;
  estimatedDueDate?: string;
  currentGestationalWeek: number;
  highRisk: boolean;
  recommendations: PrenatalVisitRecommendation[];
  existingAppointments: PrenatalAppointmentSummary[];
  alerts: string[];
}

export interface PrenatalRescheduleRequest {
  appointmentId: string;
  newAppointmentDate: string;
  newStartTime: string;
  durationMinutes?: number;
  newStaffId?: string;
  notes?: string;
}

/**
 * Prenatal scheduling — /prenatal (bare DTOs). Effective roles:
 * DOCTOR/NURSE/MIDWIFE/RECEPTIONIST/HOSPITAL_ADMIN/SUPER_ADMIN.
 */
@Injectable({ providedIn: 'root' })
export class PrenatalService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/prenatal';

  /** Generates the visit cadence for a pregnancy (POST but returns 200). */
  schedule(req: PrenatalScheduleRequest): Observable<PrenatalScheduleResponse> {
    return this.http.post<PrenatalScheduleResponse>(`${this.baseUrl}/schedule`, req);
  }

  reschedule(req: PrenatalRescheduleRequest): Observable<unknown> {
    return this.http.put(`${this.baseUrl}/appointments/reschedule`, req);
  }

  /** Queues a reminder (202 Accepted, empty body). */
  sendReminder(
    appointmentId: string,
    daysBefore: number,
    customMessage?: string,
  ): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/reminders`, {
      appointmentId,
      daysBefore,
      customMessage,
    });
  }
}
