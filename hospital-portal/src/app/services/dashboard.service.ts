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
  totalPatients: number;
  totalRoles: number;
  totalAssignments: number;
  activeAssignments: number;
  inactiveAssignments: number;
  globalAssignments: number;
  activeGlobalAssignments: number;
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

  getOnCallStatus(): Observable<OnCallStatus> {
    return this.http
      .get<ApiWrapper<OnCallStatus>>('/api/me/on-call-status')
      .pipe(map((res) => res.data));
  }
}
