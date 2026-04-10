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
  encounterId?: string;
  admissionId?: string;
  patientName: string;
  room?: string;
  elapsedMinutes: number;
  nurseAssigned?: string;
  blockerTag?: string;
  urgency: string;
  state: string;
  flowSource: 'ENCOUNTER' | 'ADMISSION';
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

/* ── Hospital Admin Summary DTOs ── */

export interface HospitalAdminSummary {
  hospitalId: string;
  asOfDate: string;
  appointments: HospitalAdminAppointmentMetrics;
  admissions: HospitalAdminAdmissionMetrics;
  consultations: HospitalAdminConsultationMetrics;
  staffing: HospitalAdminStaffingMetrics;
  billing: HospitalAdminBillingMetrics;
  recentAuditEvents: HospitalAdminAuditSnippet[];
  staffingByDepartment?: DepartmentStaffingRow[];
  consultBacklog?: ConsultBacklogItem[];
  auditTrend?: AuditDayCount[];
  invoiceAging?: InvoiceAgingBuckets;
  integrations?: IntegrationStatusRow[];
  paymentCollectionRate?: number;
  licenseAlerts?: LicenseExpiryAlert[];
  leave?: LeaveMetrics;
  paymentTrend?: PaymentTrendPoint[];
  paymentMethodBreakdown?: PaymentMethodBreakdown[];
  writeOffs?: WriteOffSummary;
  bedOccupancy?: BedOccupancy;
  wardOccupancy?: WardOccupancyRow[];
}

export interface HospitalAdminAppointmentMetrics {
  todayTotal: number;
  completed: number;
  noShows: number;
  cancelled: number;
  pending: number;
  inProgress: number;
}

export interface HospitalAdminAdmissionMetrics {
  active: number;
  admittedToday: number;
  dischargedToday: number;
  awaitingDischarge: number;
}

export interface HospitalAdminConsultationMetrics {
  requested: number;
  acknowledged: number;
  inProgress: number;
  overdue: number;
}

export interface HospitalAdminStaffingMetrics {
  activeStaff: number;
  onShiftToday: number;
  staffOnLeaveToday: number;
  upcomingLeave: number;
}

export interface HospitalAdminBillingMetrics {
  overdueInvoices: number;
  openBalanceTotal: number;
}

export interface HospitalAdminAuditSnippet {
  id: string;
  eventType: string;
  status: string;
  entityType: string;
  resourceName: string;
  userName: string;
  eventTimestamp: string;
}

export interface DepartmentStaffingRow {
  departmentId: string;
  departmentName: string;
  scheduledShifts: number;
  cancelledShifts: number;
  activeStaff: number;
}

export interface ConsultBacklogItem {
  consultationId: string;
  patientName: string;
  specialtyRequested: string;
  urgency: string;
  status: string;
  requestedAt: string;
  slaDueBy: string;
  overdue: boolean;
}

export interface AuditDayCount {
  date: string;
  count: number;
}

export interface InvoiceAgingBucket {
  label: string;
  count: number;
  total: number;
}

export interface InvoiceAgingBuckets {
  current: InvoiceAgingBucket;
  days1to30: InvoiceAgingBucket;
  days31to60: InvoiceAgingBucket;
  days61to90: InvoiceAgingBucket;
  over90: InvoiceAgingBucket;
  grandTotal: number;
}

export interface IntegrationStatusRow {
  serviceType: string;
  provider: string;
  status: string;
  enabled: boolean;
  baseUrl: string;
}

export interface LicenseExpiryAlert {
  staffId: string;
  staffName: string;
  jobTitle: string;
  departmentName: string;
  licenseNumber: string;
  licenseExpiryDate: string;
  severity: 'EXPIRED' | 'CRITICAL' | 'WARNING';
  daysUntilExpiry: number;
}

export interface LeaveMetrics {
  onLeaveToday: number;
  upcomingLeaveNext7Days: number;
}

export interface PaymentTrendPoint {
  date: string;
  amount: number;
}

export interface PaymentMethodBreakdown {
  method: string;
  count: number;
  total: number;
}

export interface WriteOffSummary {
  cancelledCount: number;
  cancelledTotal: number;
}

export interface BedOccupancy {
  totalBeds: number;
  occupiedBeds: number;
  availableBeds: number;
  reservedBeds: number;
  maintenanceBeds: number;
  occupancyRate: number;
}

export interface WardOccupancyRow {
  wardId: string;
  wardName: string;
  wardType: string;
  totalBeds: number;
  occupiedBeds: number;
  availableBeds: number;
  occupancyRate: number;
}

/* ── Lab Director Dashboard DTOs ── */

export interface ApprovalAuditSnippet {
  definitionId: string;
  testName: string;
  action: string;
  performedBy: string;
  performedAt: string;
}

export interface LabDirectorDashboard {
  hospitalId: string;
  asOfDate: string;
  pendingDirectorApproval: number;
  pendingQaReview: number;
  draftDefinitions: number;
  activeDefinitions: number;
  validationStudiesPendingApproval: number;
  validationStudiesLast30Days: number;
  ordersToday: number;
  ordersCompletedToday: number;
  ordersInProgress: number;
  ordersCancelledThisWeek: number;
  avgTurnaroundMinutesToday: number | null;
  recentApprovalAudit: ApprovalAuditSnippet[];
}

/* ── Lab Operations Dashboard DTOs ── */

export interface LabOpsSummary {
  hospitalId: string;
  asOfDate: string;
  ordersToday: number;
  completedToday: number;
  cancelledToday: number;
  ordersThisWeek: number;
  completedThisWeek: number;
  ordersThisMonth: number;
  statusOrdered: number;
  statusPending: number;
  statusCollected: number;
  statusReceived: number;
  statusInProgress: number;
  statusResulted: number;
  statusVerified: number;
  priorityRoutine: number;
  priorityUrgent: number;
  priorityStat: number;
  avgTurnaroundMinutesToday: number | null;
  avgTurnaroundMinutesThisWeek: number | null;
  ordersOlderThan24h: number;
}

/* ── Quality Manager Dashboard DTOs ── */

export interface QualityManagerDashboard {
  hospitalId: string;
  asOfDate: string;
  pendingQaReview: number;
  draftDefinitions: number;
  pendingDirectorApproval: number;
  activeDefinitions: number;
  totalValidationStudies: number;
  passedValidationStudies: number;
  failedValidationStudies: number;
  qualityPassRate: number | null;
  validationStudiesLast30Days: number;
  ordersCancelledThisWeek: number;
  ordersToday: number;
}

/* ── Platform Analytics DTOs ── */

export interface PlatformAnalytics {
  totalPatients: number;
  totalEncounters: number;
  totalAppointments: number;
  totalInvoices: number;
  totalLabOrders: number;
  totalPrescriptions: number;
  totalUsers: number;
  activeHospitals: number;
  appointmentTrend: TrendPoint[];
  encounterTrend: TrendPoint[];
  patientRegistrationTrend: TrendPoint[];
  appointmentsByStatus: Record<string, number>;
  encountersByStatus: Record<string, number>;
  invoicesByStatus: Record<string, number>;
  departmentUtilization: DepartmentUtilization[];
  hospitalMetrics: HospitalMetric[];
}

export interface TrendPoint {
  date: string;
  count: number;
}

export interface DepartmentUtilization {
  departmentName: string;
  appointmentCount: number;
  encounterCount: number;
}

export interface HospitalMetric {
  hospitalName: string;
  patientCount: number;
  appointmentCount: number;
  staffCount: number;
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

  /* ── Platform Analytics (Super Admin) ── */

  getAnalytics(trendDays = 14): Observable<PlatformAnalytics> {
    const params = new HttpParams().set('trendDays', trendDays);
    return this.http
      .get<PlatformAnalytics>('/api/super-admin/analytics', { params })
      .pipe(catchError(() => of({} as PlatformAnalytics)));
  }

  /* ── Hospital Admin Summary ── */

  getHospitalAdminSummary(date?: string, auditLimit = 10): Observable<HospitalAdminSummary> {
    let params = new HttpParams().set('auditLimit', auditLimit);
    if (date) params = params.set('date', date);
    return this.http
      .get<HospitalAdminSummary>('/api/dashboard/hospital-admin/summary', { params })
      .pipe(catchError(() => of({} as HospitalAdminSummary)));
  }

  /* ── Lab Director Summary ── */

  getLabDirectorSummary(): Observable<LabDirectorDashboard> {
    return this.http
      .get<LabDirectorDashboard>('/api/dashboard/lab-director/summary')
      .pipe(catchError(() => of({} as LabDirectorDashboard)));
  }

  /* ── Lab Ops Summary ── */

  getLabOpsSummary(): Observable<LabOpsSummary> {
    return this.http.get<LabOpsSummary>('/api/dashboard/lab-ops/summary');
  }

  /* ── Quality Manager Summary ── */

  getQualityManagerSummary(): Observable<QualityManagerDashboard> {
    return this.http
      .get<QualityManagerDashboard>('/api/dashboard/quality-manager/summary')
      .pipe(catchError(() => of({} as QualityManagerDashboard)));
  }
}
