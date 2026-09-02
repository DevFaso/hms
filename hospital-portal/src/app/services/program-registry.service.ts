import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type CareProgram = 'HIV' | 'TB' | 'MALARIA' | 'HYPERTENSION' | 'DIABETES' | 'ANC';

export type ProgramEnrollmentStatus =
  'ACTIVE' | 'COMPLETED' | 'TRANSFERRED_OUT' | 'LOST_TO_FOLLOW_UP' | 'WITHDRAWN' | 'DECEASED';

export interface ProgramEnrollment {
  id: string;
  hospitalId: string;
  patientId: string;
  patientName: string;
  mrn: string | null;
  phoneNumber: string | null;
  program: CareProgram;
  status: ProgramEnrollmentStatus;
  enrolledOn: string;
  enrolledByName: string | null;
  visitCadenceDays: number;
  lastVisitOn: string | null;
  nextExpectedVisit: string;
  /** Server-computed; positive only on an overdue ACTIVE enrolment. */
  overdueDays: number;
  notes: string | null;
  closedOn: string | null;
  closureReason: string | null;
  createdAt: string;
}

export interface EnrollRequest {
  program: CareProgram;
  /** Omit for today; past dates backfill a paper register. */
  enrolledOn?: string;
  /** Typed by the clinician — the server has no per-programme default. */
  visitCadenceDays: number;
  notes?: string;
}

export interface StatusUpdateRequest {
  status: ProgramEnrollmentStatus;
  /** Required for every closed state; refused for a move back to ACTIVE. */
  reason?: string;
}

/** Disease-programme registries (Tier 2 item 35). */
@Injectable({ providedIn: 'root' })
export class ProgramRegistryService {
  private readonly http = inject(HttpClient);

  registry(
    program: CareProgram,
    status?: ProgramEnrollmentStatus,
  ): Observable<ProgramEnrollment[]> {
    let params = new HttpParams();
    if (status) params = params.set('status', status);
    return this.http.get<ProgramEnrollment[]>(`/programs/${program}/registry`, { params });
  }

  counts(program: CareProgram): Observable<Partial<Record<ProgramEnrollmentStatus, number>>> {
    return this.http.get<Partial<Record<ProgramEnrollmentStatus, number>>>(
      `/programs/${program}/registry/counts`,
    );
  }

  enroll(patientId: string, request: EnrollRequest): Observable<ProgramEnrollment> {
    return this.http.post<ProgramEnrollment>(`/patients/${patientId}/programs`, request);
  }

  patientEnrollments(patientId: string): Observable<ProgramEnrollment[]> {
    return this.http.get<ProgramEnrollment[]>(`/patients/${patientId}/programs`);
  }

  updateStatus(
    patientId: string,
    enrollmentId: string,
    request: StatusUpdateRequest,
  ): Observable<ProgramEnrollment> {
    return this.http.put<ProgramEnrollment>(
      `/patients/${patientId}/programs/${enrollmentId}/status`,
      request,
    );
  }

  recordVisit(
    patientId: string,
    enrollmentId: string,
    visitDate?: string,
  ): Observable<ProgramEnrollment> {
    return this.http.post<ProgramEnrollment>(
      `/patients/${patientId}/programs/${enrollmentId}/visit`,
      visitDate ? { visitDate } : {},
    );
  }
}
