import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map, catchError, of } from 'rxjs';

/* ── DTO interfaces matching backend exactly ── */

export interface SuperAdminSummary {
  totalUsers: number;
  activeUsers: number;
  inactiveUsers: number;
  totalHospitals: number;
  activeHospitals: number;
  inactiveHospitals: number;
  totalOrganizations: number;
  activeOrganizations: number;
  totalDepartments: number;
  totalPatients: number;
  totalRoles: number;
  totalAssignments: number;
  activeAssignments: number;
  inactiveAssignments: number;
  globalAssignments: number;
  activeGlobalAssignments: number;
  todayAppointmentsCount: number;
  generatedAt: string;
  recentAuditEvents: RecentAuditEvent[];
}

export interface RecentAuditEvent {
  id: string;
  eventType: string;
  status: string;
  entityType: string;
  resourceId: string;
  resourceName: string;
  userName: string;
  roleName: string;
  hospitalName: string;
  eventTimestamp: string;
  eventDescription: string;
}

export interface ClinicalDashboard {
  kpis: DashboardKPI[];
  alerts: ClinicalAlert[];
  inboxCounts: InboxCounts;
  onCallStatus: OnCallStatus;
  roomedPatients: RoomedPatient[];
  specialization?: string;
  departmentName?: string;
}

export interface DashboardKPI {
  key: string;
  label: string;
  value: number;
  unit: string;
  deltaNum: number;
  trend: 'up' | 'down' | 'stable';
}

export interface ClinicalAlert {
  id: string;
  severity: 'CRITICAL' | 'URGENT' | 'WARNING' | 'INFO';
  type: string;
  title: string;
  message: string;
  patientId: string;
  patientName: string;
  timestamp: string;
  actionRequired: boolean;
  acknowledged: boolean;
  icon: string;
}

export interface InboxCounts {
  unreadMessages: number;
  pendingRefills: number;
  pendingResults: number;
  tasksToComplete: number;
  documentsToSign: number;
}

export interface OnCallStatus {
  isOnCall: boolean;
  shiftStart: string;
  shiftEnd: string;
  coveringFor: string[];
  backupProvider: string;
}

export interface RoomedPatient {
  id: string;
  encounterId: string;
  patientName: string;
  age: number;
  sex: string;
  mrn: string;
  room: string;
  triageStatus: string;
  chiefComplaint: string;
  waitTimeMinutes: number;
  arrivalTime: string;
  vitals: Record<string, unknown>;
  flags: string[];
  prepStatus: { labsDrawn: boolean; imagingOrdered: boolean; consentSigned: boolean };
}

/* ── Physician Cockpit DTOs ── */

export interface CriticalStrip {
  criticalLabsCount: number;
  waitingLongCount: number;
  pendingConsultsCount: number;
  unsignedNotesCount: number;
  pendingOrderReviewCount: number;
  activeSafetyAlertsCount: number;
}

export interface DoctorWorklistItem {
  patientId: string;
  encounterId: string;
  patientName: string;
  mrn: string;
  age: number;
  sex: string;
  room?: string;
  bed?: string;
  location?: string;
  chiefComplaint?: string;
  urgency: string;
  encounterStatus: string;
  waitMinutes?: number;
  latestVitalsSummary?: string;
  alerts?: string[];
  updatedAt: string;
}

export interface PatientFlowItem {
  patientId: string;
  encounterId: string;
  patientName: string;
  room?: string;
  elapsedMinutes: number;
  nurseAssigned?: string;
  blockerTag?: string;
  urgency: string;
  state: string;
}

export interface ClinicalInboxItem {
  id: string;
  category: string;
  source: string;
  patientName?: string;
  patientId?: string;
  subject: string;
  urgency: string;
  timestamp: string;
  actionType: string;
}

export interface DoctorResultQueueItem {
  id: string;
  patientName: string;
  patientId: string;
  testName: string;
  resultValue: string;
  abnormalFlag: string;
  resultedAt: string;
  orderingContext?: string;
}

export interface PatientSnapshot {
  patientId: string;
  name: string;
  age: number;
  sex: string;
  mrn: string;
  allergies: string[];
  codeStatus?: string;
  activeDiagnoses: string[];
  activeMedications: { name: string; dose: string; frequency: string }[];
  recentVitals: { type: string; value: string; timestamp: string }[];
  latestLabs: { test: string; value: string; flag: string; date: string }[];
  pendingOrders: { type: string; description: string; orderedAt: string }[];
  recentNotes: { author: string; type: string; date: string; snippet: string }[];
  careTeam: { role: string; name: string }[];
}

interface ApiWrapper<T> {
  data: T;
  success: boolean;
}

@Injectable({ providedIn: 'root' })
export class DashboardService {
  private readonly http = inject(HttpClient);

  /* ── Super Admin endpoints ── */

  getSummary(auditLimit = 10): Observable<SuperAdminSummary> {
    const params = new HttpParams().set('auditLimit', auditLimit);
    return this.http.get<SuperAdminSummary>('/api/super-admin/summary', { params });
  }

  /* ── Clinical Dashboard (doctor / physician / surgeon) ── */

  getClinicalDashboard(): Observable<ClinicalDashboard> {
    return this.http
      .get<ApiWrapper<ClinicalDashboard>>('/api/me/clinical-dashboard')
      .pipe(map((res) => res.data));
  }

  getCriticalAlerts(hours = 24): Observable<ClinicalAlert[]> {
    const params = new HttpParams().set('hours', hours);
    return this.http.get<ApiWrapper<ClinicalAlert[]>>('/api/me/critical-alerts', { params }).pipe(
      map((res) => res.data ?? []),
      catchError(() => of([])),
    );
  }

  acknowledgeAlert(alertId: string): Observable<void> {
    return this.http.post<void>(`/api/me/alerts/${alertId}/acknowledge`, {});
  }

  getInboxCounts(): Observable<InboxCounts> {
    return this.http
      .get<ApiWrapper<InboxCounts>>('/api/me/inbox-counts')
      .pipe(map((res) => res.data));
  }

  getRoomedPatients(): Observable<RoomedPatient[]> {
    return this.http.get<ApiWrapper<RoomedPatient[]>>('/api/me/roomed-patients').pipe(
      map((res) => res.data ?? []),
      catchError(() => of([])),
    );
  }

  getRecentPatients(): Observable<import('../services/patient.service').PatientResponse[]> {
    return this.http
      .get<
        ApiWrapper<import('../services/patient.service').PatientResponse[]>
      >('/api/me/recent-patients')
      .pipe(
        map((res) => res.data ?? []),
        catchError(() => of([])),
      );
  }

  getOnCallStatus(): Observable<OnCallStatus> {
    return this.http
      .get<ApiWrapper<OnCallStatus>>('/api/me/on-call-status')
      .pipe(map((res) => res.data));
  }

  /* ── Physician Cockpit endpoints ── */

  getCriticalStrip(): Observable<CriticalStrip> {
    return this.http
      .get<ApiWrapper<CriticalStrip>>('/api/me/critical-strip')
      .pipe(map((res) => res.data));
  }

  getWorklist(status?: string, urgency?: string, date?: string): Observable<DoctorWorklistItem[]> {
    let params = new HttpParams();
    if (status) params = params.set('status', status);
    if (urgency) params = params.set('urgency', urgency);
    if (date) params = params.set('date', date);
    return this.http.get<ApiWrapper<DoctorWorklistItem[]>>('/api/me/worklist', { params }).pipe(
      map((res) => res.data ?? []),
      catchError(() => of([])),
    );
  }

  getPatientFlow(): Observable<Record<string, PatientFlowItem[]>> {
    return this.http
      .get<ApiWrapper<Record<string, PatientFlowItem[]>>>('/api/me/patient-flow')
      .pipe(
        map((res) => res.data ?? {}),
        catchError(() => of({})),
      );
  }

  getInbox(): Observable<ClinicalInboxItem[]> {
    return this.http.get<ApiWrapper<ClinicalInboxItem[]>>('/api/me/inbox').pipe(
      map((res) => res.data ?? []),
      catchError(() => of([])),
    );
  }

  getResultReviewQueue(): Observable<DoctorResultQueueItem[]> {
    return this.http.get<ApiWrapper<DoctorResultQueueItem[]>>('/api/me/results/review-queue').pipe(
      map((res) => res.data ?? []),
      catchError(() => of([])),
    );
  }

  getPatientSnapshot(patientId: string): Observable<PatientSnapshot> {
    return this.http
      .get<ApiWrapper<PatientSnapshot>>(`/api/me/patients/${patientId}/snapshot`)
      .pipe(map((res) => res.data));
  }
}
