import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

/* ── Interfaces ─────────────────────────────────────── */

export interface PatientTrackerItem {
  patientId: string;
  patientName: string;
  mrn: string | null;
  appointmentId: string | null;
  encounterId: string;
  currentStatus: string;
  roomAssignment: string | null;
  assignedProvider: string | null;
  departmentName: string | null;
  arrivalTimestamp: string | null;
  triageTimestamp: string | null;
  currentWaitMinutes: number;
  acuityLevel: string;
  preCheckedIn: boolean | null;
}

export interface PatientTrackerBoard {
  arrived: PatientTrackerItem[];
  triage: PatientTrackerItem[];
  waitingForPhysician: PatientTrackerItem[];
  inProgress: PatientTrackerItem[];
  awaitingResults: PatientTrackerItem[];
  readyForDischarge: PatientTrackerItem[];
  totalPatients: number;
  averageWaitMinutes: number;
}

/* ── Service ────────────────────────────────────────── */

@Injectable({ providedIn: 'root' })
export class PatientTrackerService {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/patient-tracker';

  getTrackerBoard(
    hospitalId: string,
    departmentId?: string,
    date?: string,
  ): Observable<PatientTrackerBoard> {
    let params = new HttpParams().set('hospitalId', hospitalId);
    if (departmentId) {
      params = params.set('departmentId', departmentId);
    }
    if (date) {
      params = params.set('date', date);
    }
    return this.http
      .get<{ data: PatientTrackerBoard }>(this.base, { params })
      .pipe(map((res) => res.data));
  }
}
