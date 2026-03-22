import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { CommonModule, DatePipe, TitleCasePipe } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { PermissionService } from '../core/permission.service';
import { AppointmentService, AppointmentResponse } from '../services/appointment.service';
import { PatientService, PatientResponse } from '../services/patient.service';
import {
  DashboardService,
  SuperAdminSummary,
  RecentAuditEvent,
  DashboardKPI,
  ClinicalAlert,
  InboxCounts,
  OnCallStatus,
  RoomedPatient,
  CriticalStrip,
  DoctorWorklistItem,
  PatientFlowItem,
  ClinicalInboxItem,
  DoctorResultQueueItem,
  PatientSnapshot,
  HospitalAdminSummary,
  HospitalAdminAuditSnippet,
  DepartmentStaffingRow,
  ConsultBacklogItem,
  AuditDayCount,
  InvoiceAgingBuckets,
  InvoiceAgingBucket,
  IntegrationStatusRow,
  LicenseExpiryAlert,
  LeaveMetrics,
  PaymentTrendPoint,
  PaymentMethodBreakdown,
  WriteOffSummary,
  BedOccupancy,
  WardOccupancyRow,
} from '../services/dashboard.service';
import {
  PatientPortalService,
  HealthSummaryDTO,
  PortalAppointment,
  MedicationSummary,
  LabResultSummary,
  VitalSignSummary,
  PortalInvoice,
  CareTeamDTO,
} from '../services/patient-portal.service';
import { DoctorWorklistComponent } from './doctor-worklist/doctor-worklist';
import { DoctorPatientFlowComponent } from './doctor-patient-flow/doctor-patient-flow';
import { DoctorResultsPanelComponent } from './doctor-results-panel/doctor-results-panel';
import { PatientSnapshotDrawerComponent } from './patient-snapshot-drawer/patient-snapshot-drawer';

// ── Local interfaces ────────────────────────────────────────────────────────

interface QuickAction {
  icon: string;
  label: string;
  route: string;
  queryParams?: Record<string, string>;
  color: string;
  bgColor: string;
  description: string;
}

interface ScheduleSlot {
  time: string;
  patientName: string;
  reason: string;
  status: AppointmentResponse['status'];
  id: string;
  isNow: boolean;
}

interface StatCard {
  key: string;
  label: string;
  value: number | string;
  icon: string;
  color: string;
  bgColor: string;
  route?: string;
  trend?: 'up' | 'down' | 'stable';
  delta?: number;
  suffix?: string;
}

interface NavTile {
  icon: string;
  label: string;
  route: string;
  color: string;
  bg: string;
  count?: number;
}

// ────────────────────────────────────────────────────────────────────────────

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    DatePipe,
    TitleCasePipe,
    DoctorWorklistComponent,
    DoctorPatientFlowComponent,
    DoctorResultsPanelComponent,
    PatientSnapshotDrawerComponent,
  ],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DashboardComponent implements OnInit, OnDestroy {
  // ── DI ───────────────────────────────────────────────────────
  private readonly auth = inject(AuthService);
  readonly permissions = inject(PermissionService);
  private readonly appointmentService = inject(AppointmentService);
  private readonly patientService = inject(PatientService);
  private readonly router = inject(Router);
  private readonly dashboardService = inject(DashboardService);
  private readonly portalService = inject(PatientPortalService);

  // ── Time & Identity ──────────────────────────────────────────
  greeting = signal('');
  userName = signal('');
  userTitle = signal('');
  currentTime = signal(this.formatTime(new Date()));
  readonly todayLabel = new Date().toLocaleDateString('en-US', {
    weekday: 'long',
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  });
  private clockInterval?: ReturnType<typeof setInterval>;

  // ── Loading States ───────────────────────────────────────────
  loading = signal(true);
  scheduleLoading = signal(true);

  // ── Role flags (signals) ─────────────────────────────────────
  isSuperAdmin = signal(false);
  isHospitalAdmin = signal(false);
  isDoctor = signal(false);
  isNurse = signal(false);
  isMidwife = signal(false);
  isReceptionist = signal(false);
  isLabScientist = signal(false);
  isPharmacist = signal(false);
  isRadiologist = signal(false);
  isPatient = signal(false);

  /** True for DOCTOR / NURSE / MIDWIFE — gets clinical workspace layout */
  isClinician = computed(() => this.isDoctor() || this.isNurse() || this.isMidwife());

  /** True for anyone who has View Appointments permission but is not a clinician */
  isSchedulingRole = computed(
    () => !this.isClinician() && this.permissions.hasPermission('View Appointments'),
  );

  // ── Super-Admin data ─────────────────────────────────────────
  adminSummary = signal<SuperAdminSummary | null>(null);
  recentAuditEvents = signal<RecentAuditEvent[]>([]);

  // ── Hospital-Admin data ────────────────────────────────────
  hospitalAdminSummary = signal<HospitalAdminSummary | null>(null);

  // ── Clinical Dashboard data ───────────────────────────────────
  kpis = signal<DashboardKPI[]>([]);
  alerts = signal<ClinicalAlert[]>([]);
  inboxCounts = signal<InboxCounts | null>(null);
  onCallStatus = signal<OnCallStatus | null>(null);
  roomedPatients = signal<RoomedPatient[]>([]);

  // ── Physician Cockpit data ───────────────────────────────────
  criticalStrip = signal<CriticalStrip | null>(null);
  worklistItems = signal<DoctorWorklistItem[]>([]);
  patientFlowData = signal<Record<string, PatientFlowItem[]>>({});
  inboxItems = signal<ClinicalInboxItem[]>([]);
  resultQueue = signal<DoctorResultQueueItem[]>([]);
  patientSnapshot = signal<PatientSnapshot | null>(null);
  snapshotDrawerOpen = signal(false);
  specialization = signal<string | null>(null);
  departmentName = signal<string | null>(null);

  // ── Inbox accordion collapse state ──────────────────────────
  inboxCollapsedSections = signal<Set<string>>(new Set());

  readonly _inboxCategoryOrder = [
    'MESSAGE',
    'CRITICAL_RESULT',
    'CONSULT_REQUEST',
    'DOCUMENT_TO_SIGN',
    'REFILL_REQUEST',
    'PHARMACY_CLARIFICATION',
    'TASK',
  ];
  readonly _inboxLabels: Record<string, string> = {
    MESSAGE: 'Messages',
    CRITICAL_RESULT: 'Critical Results',
    CONSULT_REQUEST: 'Consult Requests',
    DOCUMENT_TO_SIGN: 'Documents to Sign',
    REFILL_REQUEST: 'Refill Requests',
    PHARMACY_CLARIFICATION: 'Pharmacy Clarifications',
    TASK: 'Tasks',
  };
  readonly _inboxIcons: Record<string, string> = {
    MESSAGE: 'chat',
    CRITICAL_RESULT: 'science',
    CONSULT_REQUEST: 'forum',
    DOCUMENT_TO_SIGN: 'draw',
    REFILL_REQUEST: 'medication',
    PHARMACY_CLARIFICATION: 'local_pharmacy',
    TASK: 'task_alt',
  };

  inboxGrouped = computed(() => {
    const groups = new Map<string, ClinicalInboxItem[]>();
    for (const item of this.inboxItems()) {
      const cat = item.category;
      if (!groups.has(cat)) groups.set(cat, []);
      groups.get(cat)!.push(item);
    }
    return this._inboxCategoryOrder
      .filter((cat) => groups.has(cat))
      .map((cat) => ({
        category: cat,
        label: this._inboxLabels[cat] ?? cat,
        icon: this._inboxIcons[cat] ?? 'assignment',
        items: groups.get(cat)!,
      }));
  });

  // ── Schedule & Patients ───────────────────────────────────────
  todayAppointments = signal<AppointmentResponse[]>([]);
  recentPatients = signal<PatientResponse[]>([]);

  // ── Patient Portal data ──────────────────────────────────────
  healthSummary = signal<HealthSummaryDTO | null>(null);
  myAppointments = signal<PortalAppointment[]>([]);
  myMedications = signal<MedicationSummary[]>([]);
  myLabResults = signal<LabResultSummary[]>([]);
  myVitals = signal<VitalSignSummary[]>([]);
  myInvoices = signal<PortalInvoice[]>([]);
  myCareTeam = signal<CareTeamDTO | null>(null);

  // ────────────────────────────────────────────────────────────
  // COMPUTED — DERIVED STATE
  // ────────────────────────────────────────────────────────────

  scheduleSlots = computed<ScheduleSlot[]>(() => {
    const now = new Date();
    const nowMins = now.getHours() * 60 + now.getMinutes();
    return this.todayAppointments().map((appt) => {
      const [h, m] = appt.startTime.split(':').map(Number);
      const slotMins = h * 60 + (m || 0);
      return {
        id: appt.id,
        time: appt.startTime,
        patientName: appt.patientName,
        reason: appt.reason ?? '',
        status: appt.status,
        isNow: appt.status === 'IN_PROGRESS' || Math.abs(slotMins - nowMins) < 30,
      };
    });
  });

  unacknowledgedAlerts = computed(() => this.alerts().filter((a) => !a.acknowledged));
  criticalAlerts = computed(() =>
    this.unacknowledgedAlerts().filter((a) => a.severity === 'CRITICAL'),
  );

  completedToday = computed(
    () => this.todayAppointments().filter((a) => a.status === 'COMPLETED').length,
  );
  pendingToday = computed(
    () =>
      this.todayAppointments().filter((a) => a.status === 'SCHEDULED' || a.status === 'CONFIRMED')
        .length,
  );
  inProgressNow = computed(
    () => this.todayAppointments().filter((a) => a.status === 'IN_PROGRESS').length,
  );

  totalInboxCount = computed(() => {
    const ic = this.inboxCounts();
    if (!ic) return 0;
    return (
      (ic.unreadMessages ?? 0) +
      (ic.pendingRefills ?? 0) +
      (ic.pendingResults ?? 0) +
      (ic.tasksToComplete ?? 0) +
      (ic.documentsToSign ?? 0)
    );
  });

  scheduleProgress = computed(() => {
    const total = this.todayAppointments().length;
    if (total === 0) return 0;
    return Math.round((this.completedToday() / total) * 100);
  });

  progressOffset = computed(() => {
    const radius = 34;
    const circ = 2 * Math.PI * radius;
    return circ - (this.scheduleProgress() / 100) * circ;
  });

  // ── Hero gradient class (role-specific) ─────────────────────
  heroGradientClass = computed(() => {
    if (this.isPatient()) return 'hero-gradient-patient';
    if (this.isSuperAdmin()) return 'hero-gradient-superadmin';
    if (this.isHospitalAdmin()) return 'hero-gradient-hospital-admin';
    if (this.isDoctor()) return 'hero-gradient-doctor';
    if (this.isNurse() || this.isMidwife()) return 'hero-gradient-nurse';
    if (this.isReceptionist()) return 'hero-gradient-receptionist';
    if (this.isLabScientist()) return 'hero-gradient-lab';
    if (this.isPharmacist()) return 'hero-gradient-pharmacist';
    if (this.isRadiologist()) return 'hero-gradient-radiologist';
    return 'hero-gradient-default';
  });

  /**
   * Single authoritative view selector — prevents multiple dashboard sections
   * rendering simultaneously when a user has more than one role.
   * Priority matches heroGradientClass / roleLabel ordering.
   */
  activeView = computed<
    | 'superadmin'
    | 'hospitaladmin'
    | 'doctor'
    | 'nurse'
    | 'receptionist'
    | 'lab'
    | 'pharmacist'
    | 'radiologist'
    | 'patient'
    | 'fallback'
  >(() => {
    if (this.isPatient()) return 'patient';
    if (this.isSuperAdmin()) return 'superadmin';
    if (this.isHospitalAdmin()) return 'hospitaladmin';
    if (this.isDoctor()) return 'doctor';
    if (this.isNurse() || this.isMidwife()) return 'nurse';
    if (this.isReceptionist()) return 'receptionist';
    if (this.isLabScientist()) return 'lab';
    if (this.isPharmacist()) return 'pharmacist';
    if (this.isRadiologist()) return 'radiologist';
    return 'fallback';
  });

  // ── Role display label ────────────────────────────────────────
  roleLabel = computed(() => {
    if (this.isPatient()) return 'Patient';
    if (this.isSuperAdmin()) return 'Super Administrator';
    if (this.isHospitalAdmin()) return 'Hospital Administrator';
    if (this.isDoctor()) return 'Physician';
    if (this.isMidwife()) return 'Midwife';
    if (this.isNurse()) return 'Nurse';
    if (this.isReceptionist()) return 'Front Desk';
    if (this.isLabScientist()) return 'Lab Scientist';
    if (this.isPharmacist()) return 'Pharmacist';
    if (this.isRadiologist()) return 'Radiologist';
    return 'Staff';
  });

  // ── Critical Strip (doctor — actionable cards) ───────────────
  criticalStripCards = computed<StatCard[]>(() => {
    const cs = this.criticalStrip();
    if (!cs) return [];
    return [
      {
        key: 'critical_labs',
        label: 'Critical Labs',
        value: cs.criticalLabsCount,
        icon: 'science',
        color: '#dc2626',
        bgColor: '#fee2e2',
        route: '/lab',
      },
      {
        key: 'waiting_long',
        label: 'Waiting > 30m',
        value: cs.waitingLongCount,
        icon: 'hourglass_top',
        color: cs.waitingLongCount > 0 ? '#d97706' : '#64748b',
        bgColor: cs.waitingLongCount > 0 ? '#fef3c7' : '#f1f5f9',
      },
      {
        key: 'pending_consults',
        label: 'Pending Consults',
        value: cs.pendingConsultsCount,
        icon: 'forum',
        color: cs.pendingConsultsCount > 0 ? '#7c3aed' : '#64748b',
        bgColor: cs.pendingConsultsCount > 0 ? '#ede9fe' : '#f1f5f9',
        route: '/consultations',
      },
      {
        key: 'unsigned_notes',
        label: 'Unsigned Notes',
        value: cs.unsignedNotesCount,
        icon: 'draw',
        color: cs.unsignedNotesCount > 0 ? '#d97706' : '#64748b',
        bgColor: cs.unsignedNotesCount > 0 ? '#fef3c7' : '#f1f5f9',
      },
      {
        key: 'pending_orders',
        label: 'Orders to Review',
        value: cs.pendingOrderReviewCount,
        icon: 'assignment',
        color: cs.pendingOrderReviewCount > 0 ? '#2563eb' : '#64748b',
        bgColor: cs.pendingOrderReviewCount > 0 ? '#dbeafe' : '#f1f5f9',
        route: '/lab',
      },
      {
        key: 'safety_alerts',
        label: 'Safety Alerts',
        value: cs.activeSafetyAlertsCount,
        icon: 'emergency',
        color: cs.activeSafetyAlertsCount > 0 ? '#dc2626' : '#64748b',
        bgColor: cs.activeSafetyAlertsCount > 0 ? '#fee2e2' : '#f1f5f9',
      },
    ];
  });

  // ── Stat strip (generic fallback for non-doctor clinical) ───
  statCards = computed<StatCard[]>(() => {
    const base: StatCard[] = [
      {
        key: 'total_today',
        label: "Today's Patients",
        value: this.todayAppointments().length,
        icon: 'group',
        color: '#2563eb',
        bgColor: '#eff6ff',
        route: '/appointments',
      },
      {
        key: 'completed',
        label: 'Completed',
        value: this.completedToday(),
        icon: 'task_alt',
        color: '#2563eb',
        bgColor: '#eff6ff',
        route: '/appointments',
      },
      {
        key: 'in_progress',
        label: 'In Progress',
        value: this.inProgressNow(),
        icon: 'person_play',
        color: '#2563eb',
        bgColor: '#eff6ff',
        route: '/appointments',
      },
      {
        key: 'pending',
        label: 'Pending',
        value: this.pendingToday(),
        icon: 'pending',
        color: '#2563eb',
        bgColor: '#eff6ff',
        route: '/appointments',
      },
      {
        key: 'alerts',
        label: 'Active Alerts',
        value: this.unacknowledgedAlerts().length,
        icon: 'notification_important',
        color: this.criticalAlerts().length > 0 ? '#dc2626' : '#2563eb',
        bgColor: this.criticalAlerts().length > 0 ? '#fee2e2' : '#eff6ff',
      },
      {
        key: 'inbox',
        label: 'Inbox Items',
        value: this.totalInboxCount(),
        icon: 'mark_email_unread',
        color: '#2563eb',
        bgColor: '#eff6ff',
        route: '/chat',
      },
    ];

    const kpiMap = new Map(this.kpis().map((k) => [k.key, k]));
    return base.map((c) => {
      const kpi = kpiMap.get(c.key);
      return kpi ? { ...c, value: kpi.value, trend: kpi.trend, delta: kpi.deltaNum } : c;
    });
  });

  // ── Quick Actions (permission-gated) ─────────────────────────
  quickActions = computed<QuickAction[]>(() => {
    const all: [string, QuickAction][] = [
      // Doctor-priority shortcuts (appear first, dedup 'Start Encounter' on /encounters route)
      ...(this.isDoctor()
        ? ([
            [
              'Create Encounters',
              {
                icon: 'navigate_next',
                label: 'Open Next Patient',
                description: 'Open next waiting patient',
                route: '/encounters',
                color: '#059669',
                bgColor: '#d1fae5',
              },
            ],
            [
              'Create Encounters',
              {
                icon: 'meeting_room',
                label: 'Discharge Patient',
                description: 'Initiate patient discharge',
                route: '/admissions',
                queryParams: { tab: 'admitted' },
                color: '#d97706',
                bgColor: '#fef3c7',
              },
            ],
          ] as [string, QuickAction][])
        : []),
      [
        'Register Patients',
        {
          icon: 'person_add',
          label: 'Register Patient',
          description: 'Onboard a new patient',
          route: '/patients/new',
          color: '#2563eb',
          bgColor: '#eff6ff',
        },
      ],
      [
        'Create Appointments',
        {
          icon: 'calendar_add_on',
          label: 'New Appointment',
          description: 'Schedule a visit',
          route: '/appointments/new',
          color: '#2563eb',
          bgColor: '#eff6ff',
        },
      ],
      [
        'Create Encounters',
        {
          icon: 'stethoscope',
          label: 'Start Encounter',
          description: 'Begin a clinical encounter',
          route: '/encounters',
          color: '#2563eb',
          bgColor: '#eff6ff',
        },
      ],
      [
        'Create Prescriptions',
        {
          icon: 'medication',
          label: 'Write Rx',
          description: 'Prescribe medication',
          route: '/prescriptions',
          queryParams: { new: '1' },
          color: '#2563eb',
          bgColor: '#eff6ff',
        },
      ],
      [
        'View Lab',
        {
          icon: 'science',
          label: 'Lab Orders',
          description: 'Order lab tests',
          route: '/lab',
          color: '#2563eb',
          bgColor: '#eff6ff',
        },
      ],
      [
        'Request Imaging Studies',
        {
          icon: 'radiology',
          label: 'Imaging',
          description: 'Request imaging studies',
          route: '/imaging',
          color: '#2563eb',
          bgColor: '#eff6ff',
        },
      ],
      [
        'View Billing',
        {
          icon: 'receipt_long',
          label: 'Billing',
          description: 'Manage billing',
          route: '/billing',
          color: '#2563eb',
          bgColor: '#eff6ff',
        },
      ],
      [
        'Create Referrals',
        {
          icon: 'send',
          label: 'Create Referral',
          description: 'Refer a patient',
          route: '/referrals',
          color: '#2563eb',
          bgColor: '#eff6ff',
        },
      ],
      [
        'View Prescriptions',
        {
          icon: 'local_pharmacy',
          label: 'Prescriptions',
          description: 'View & dispense prescriptions',
          route: '/prescriptions',
          color: '#2563eb',
          bgColor: '#eff6ff',
        },
      ],
      [
        'Dispense Medications',
        {
          icon: 'medication_liquid',
          label: 'Dispense Rx',
          description: 'Dispense medications',
          route: '/prescriptions',
          color: '#2563eb',
          bgColor: '#eff6ff',
        },
      ],
      [
        'Process Lab Tests',
        {
          icon: 'biotech',
          label: 'Process Lab',
          description: 'Process pending lab tests',
          route: '/lab',
          color: '#2563eb',
          bgColor: '#eff6ff',
        },
      ],
      [
        'Document Nursing Notes',
        {
          icon: 'edit_note',
          label: 'Nursing Notes',
          description: 'Document patient notes',
          route: '/encounters',
          color: '#2563eb',
          bgColor: '#eff6ff',
        },
      ],
      [
        'Check-in Patients',
        {
          icon: 'how_to_reg',
          label: 'Check-in',
          description: 'Check in a patient',
          route: '/appointments',
          color: '#2563eb',
          bgColor: '#eff6ff',
        },
      ],
    ];

    const seen = new Set<string>();
    const actions: QuickAction[] = [];
    for (const [perm, action] of all) {
      if (!seen.has(action.route) && this.permissions.hasPermission(perm)) {
        seen.add(action.route);
        actions.push(action);
        if (actions.length >= 6) break;
      }
    }
    return actions;
  });

  // ── Super-Admin navigation tiles ─────────────────────────────
  adminNavTiles = computed<NavTile[]>(() => [
    {
      icon: 'corporate_fare',
      label: 'Organizations',
      route: '/organizations',
      color: '#2563eb',
      bg: '#eff6ff',
    },
    {
      icon: 'local_hospital',
      label: 'Hospitals',
      route: '/hospitals',
      color: '#2563eb',
      bg: '#eff6ff',
    },
    {
      icon: 'domain',
      label: 'Departments',
      route: '/departments',
      color: '#2563eb',
      bg: '#eff6ff',
    },
    {
      icon: 'shield',
      label: 'Roles',
      route: '/roles',
      color: '#2563eb',
      bg: '#eff6ff',
    },
    { icon: 'group', label: 'Users', route: '/users', color: '#2563eb', bg: '#eff6ff' },
    {
      icon: 'people',
      label: 'Patients',
      route: '/patients',
      color: '#2563eb',
      bg: '#eff6ff',
    },
    {
      icon: 'calendar_month',
      label: 'Appointments',
      route: '/appointments',
      color: '#2563eb',
      bg: '#eff6ff',
    },
    {
      icon: 'history',
      label: 'Audit Logs',
      route: '/audit-logs',
      color: '#2563eb',
      bg: '#eff6ff',
    },
    {
      icon: 'tune',
      label: 'Platform Config',
      route: '/platform',
      color: '#2563eb',
      bg: '#eff6ff',
    },
    {
      icon: 'flag',
      label: 'Feature Flags',
      route: '/feature-flags',
      color: '#2563eb',
      bg: '#eff6ff',
    },
    {
      icon: 'analytics',
      label: 'Analytics',
      route: '/analytics',
      color: '#2563eb',
      bg: '#eff6ff',
    },
    {
      icon: 'notifications',
      label: 'Notifications',
      route: '/notifications',
      color: '#2563eb',
      bg: '#eff6ff',
    },
    {
      icon: 'verified',
      label: 'Digital Signatures',
      route: '/digital-signatures',
      color: '#2563eb',
      bg: '#eff6ff',
    },
  ]);

  // ── Hospital Admin navigation tiles ──────────────────────────
  hospitalAdminNavTiles = computed<NavTile[]>(() => [
    {
      icon: 'group',
      label: 'Staff',
      route: '/staff',
      color: '#2563eb',
      bg: '#eff6ff',
      count: undefined,
    },
    {
      icon: 'people',
      label: 'Patients',
      route: '/patients',
      color: '#2563eb',
      bg: '#eff6ff',
    },
    {
      icon: 'calendar_month',
      label: 'Appointments',
      route: '/appointments',
      color: '#2563eb',
      bg: '#eff6ff',
      count: this.todayAppointments().length,
    },
    {
      icon: 'domain',
      label: 'Departments',
      route: '/departments',
      color: '#2563eb',
      bg: '#eff6ff',
    },
    {
      icon: 'receipt_long',
      label: 'Billing',
      route: '/billing',
      color: '#2563eb',
      bg: '#eff6ff',
    },
    { icon: 'policy', label: 'Audit Logs', route: '/audit-logs', color: '#2563eb', bg: '#eff6ff' },
  ]);

  // ── Hospital Admin stat cards (from summary API) ────────────
  hospitalAdminStatCards = computed<StatCard[]>(() => {
    const s = this.hospitalAdminSummary();
    if (!s || !s.appointments) return [];
    return [
      {
        key: 'appts_today',
        label: "Today's Appointments",
        value: s.appointments.todayTotal,
        icon: 'calendar_today',
        color: '#2563eb',
        bgColor: '#eff6ff',
        route: '/appointments',
      },
      {
        key: 'appts_completed',
        label: 'Completed',
        value: s.appointments.completed,
        icon: 'task_alt',
        color: '#059669',
        bgColor: '#d1fae5',
        route: '/appointments',
      },
      {
        key: 'appts_noshow',
        label: 'No-Shows',
        value: s.appointments.noShows,
        icon: 'person_off',
        color: s.appointments.noShows > 0 ? '#dc2626' : '#64748b',
        bgColor: s.appointments.noShows > 0 ? '#fee2e2' : '#f1f5f9',
        route: '/appointments',
      },
      {
        key: 'admissions_active',
        label: 'Active Admissions',
        value: s.admissions.active,
        icon: 'hotel',
        color: '#7c3aed',
        bgColor: '#ede9fe',
        route: '/admissions',
      },
      {
        key: 'admissions_today',
        label: 'Admitted Today',
        value: s.admissions.admittedToday,
        icon: 'login',
        color: '#2563eb',
        bgColor: '#dbeafe',
        route: '/admissions',
      },
      {
        key: 'discharged_today',
        label: 'Discharged Today',
        value: s.admissions.dischargedToday,
        icon: 'logout',
        color: '#059669',
        bgColor: '#d1fae5',
        route: '/admissions',
      },
      {
        key: 'consults_pending',
        label: 'Consults Pending',
        value: s.consultations.requested + s.consultations.acknowledged,
        icon: 'forum',
        color: '#d97706',
        bgColor: '#fef3c7',
        route: '/consultations',
      },
      {
        key: 'consults_overdue',
        label: 'Consults Overdue',
        value: s.consultations.overdue,
        icon: 'warning',
        color: s.consultations.overdue > 0 ? '#dc2626' : '#64748b',
        bgColor: s.consultations.overdue > 0 ? '#fee2e2' : '#f1f5f9',
        route: '/consultations',
      },
      {
        key: 'staff_active',
        label: 'Active Staff',
        value: s.staffing.activeStaff,
        icon: 'badge',
        color: '#0891b2',
        bgColor: '#cffafe',
        route: '/staff',
      },
      {
        key: 'staff_on_shift',
        label: 'On-Shift Today',
        value: s.staffing.onShiftToday,
        icon: 'work',
        color: '#059669',
        bgColor: '#d1fae5',
        route: '/staff',
      },
      {
        key: 'staff_on_leave',
        label: 'On Leave Today',
        value: s.staffing.staffOnLeaveToday ?? 0,
        icon: 'event_busy',
        color: (s.staffing.staffOnLeaveToday ?? 0) > 0 ? '#d97706' : '#64748b',
        bgColor: (s.staffing.staffOnLeaveToday ?? 0) > 0 ? '#fef3c7' : '#f1f5f9',
        route: '/staff',
      },
      {
        key: 'license_alerts',
        label: 'License Alerts',
        value: (s.licenseAlerts ?? []).length,
        icon: 'gpp_maybe',
        color: (s.licenseAlerts ?? []).some((a) => a.severity === 'EXPIRED')
          ? '#dc2626'
          : (s.licenseAlerts ?? []).length > 0
            ? '#d97706'
            : '#64748b',
        bgColor: (s.licenseAlerts ?? []).some((a) => a.severity === 'EXPIRED')
          ? '#fee2e2'
          : (s.licenseAlerts ?? []).length > 0
            ? '#fef3c7'
            : '#f1f5f9',
        route: '/staff',
      },
      {
        key: 'invoices_overdue',
        label: 'Overdue Invoices',
        value: s.billing.overdueInvoices,
        icon: 'receipt_long',
        color: s.billing.overdueInvoices > 0 ? '#dc2626' : '#64748b',
        bgColor: s.billing.overdueInvoices > 0 ? '#fee2e2' : '#f1f5f9',
        route: '/billing',
      },
      {
        key: 'open_balance',
        label: 'Open Balance',
        value: '$' + (s.billing.openBalanceTotal ?? 0).toLocaleString(),
        icon: 'account_balance',
        color: '#d97706',
        bgColor: '#fef3c7',
        route: '/billing',
      },
    ];
  });

  hospitalAdminAuditEvents = computed<HospitalAdminAuditSnippet[]>(() => {
    return this.hospitalAdminSummary()?.recentAuditEvents ?? [];
  });

  // ── MVP 17: Staffing by department ──────────────────────────
  staffingByDepartment = computed<DepartmentStaffingRow[]>(() => {
    return this.hospitalAdminSummary()?.staffingByDepartment ?? [];
  });

  // ── MVP 17: Consult backlog ─────────────────────────────────
  consultBacklog = computed<ConsultBacklogItem[]>(() => {
    return this.hospitalAdminSummary()?.consultBacklog ?? [];
  });

  overdueConsults = computed<number>(() => {
    return this.consultBacklog().filter((c) => c.overdue).length;
  });

  // ── MVP 17: Audit trend (last 7 days) ──────────────────────
  auditTrend = computed<AuditDayCount[]>(() => {
    return this.hospitalAdminSummary()?.auditTrend ?? [];
  });

  auditTrendMax = computed<number>(() => {
    const trend = this.auditTrend();
    return trend.length > 0 ? Math.max(...trend.map((t) => t.count)) : 1;
  });

  // ── MVP 18: Invoice aging buckets ──────────────────────────
  invoiceAging = computed<InvoiceAgingBuckets | null>(() => {
    return this.hospitalAdminSummary()?.invoiceAging ?? null;
  });

  invoiceAgingBuckets = computed<InvoiceAgingBucket[]>(() => {
    const aging = this.invoiceAging();
    if (!aging) return [];
    return [aging.current, aging.days1to30, aging.days31to60, aging.days61to90, aging.over90];
  });

  paymentCollectionRate = computed<number>(() => {
    return this.hospitalAdminSummary()?.paymentCollectionRate ?? 0;
  });

  // ── MVP 18: Integration status ──────────────────────────────
  integrations = computed<IntegrationStatusRow[]>(() => {
    return this.hospitalAdminSummary()?.integrations ?? [];
  });

  // ── MVP 19: License expiry alerts ──────────────────────────
  licenseAlerts = computed<LicenseExpiryAlert[]>(() => {
    return this.hospitalAdminSummary()?.licenseAlerts ?? [];
  });

  expiredLicenseCount = computed<number>(() => {
    return this.licenseAlerts().filter((a) => a.severity === 'EXPIRED').length;
  });

  criticalLicenseCount = computed<number>(() => {
    return this.licenseAlerts().filter((a) => a.severity === 'CRITICAL').length;
  });

  // ── MVP 19: Leave metrics ──────────────────────────────────
  leaveMetrics = computed<LeaveMetrics | null>(() => {
    return this.hospitalAdminSummary()?.leave ?? null;
  });

  // ── MVP 20: Payment trend ─────────────────────────────────
  paymentTrend = computed<PaymentTrendPoint[]>(() => {
    return this.hospitalAdminSummary()?.paymentTrend ?? [];
  });

  paymentTrendMax = computed<number>(() => {
    const trend = this.paymentTrend();
    return trend.length > 0 ? Math.max(...trend.map((t) => t.amount)) : 1;
  });

  paymentMethodBreakdown = computed<PaymentMethodBreakdown[]>(() => {
    return this.hospitalAdminSummary()?.paymentMethodBreakdown ?? [];
  });

  paymentMethodTotal = computed<number>(() => {
    return this.paymentMethodBreakdown().reduce((sum, m) => sum + m.total, 0);
  });

  writeOffs = computed<WriteOffSummary | null>(() => {
    return this.hospitalAdminSummary()?.writeOffs ?? null;
  });

  // ── MVP 21: Bed occupancy ─────────────────────────────────
  bedOccupancy = computed<BedOccupancy | null>(() => {
    return this.hospitalAdminSummary()?.bedOccupancy ?? null;
  });

  wardOccupancy = computed<WardOccupancyRow[]>(() => {
    return this.hospitalAdminSummary()?.wardOccupancy ?? [];
  });

  // ── Nurse workflow tiles (Epic-style My Activities) ──────────
  nurseWorkflowTiles = computed<NavTile[]>(() => [
    {
      icon: 'monitor_heart',
      label: 'Vitals',
      route: '/nurse-station',
      color: '#dc2626',
      bg: '#fee2e2',
    },
    { icon: 'medication', label: 'MAR', route: '/nurse-station', color: '#7c3aed', bg: '#ede9fe' },
    {
      icon: 'how_to_reg',
      label: 'Check-In',
      route: '/appointments',
      color: '#059669',
      bg: '#d1fae5',
    },
    { icon: 'hotel', label: 'Admissions', route: '/admissions', color: '#2563eb', bg: '#dbeafe' },
    { icon: 'people', label: 'Patients', route: '/patients', color: '#0891b2', bg: '#cffafe' },
    {
      icon: 'clinical_notes',
      label: 'Encounters',
      route: '/encounters',
      color: '#d97706',
      bg: '#fef3c7',
    },
    {
      icon: 'local_pharmacy',
      label: 'Prescriptions',
      route: '/prescriptions',
      color: '#9333ea',
      bg: '#f3e8ff',
    },
    { icon: 'science', label: 'Lab Orders', route: '/lab', color: '#0d9488', bg: '#ccfbf1' },
    {
      icon: 'assignment',
      label: 'Tasks',
      route: '/nurse-station',
      color: '#ea580c',
      bg: '#fff7ed',
    },
    {
      icon: 'swap_horiz',
      label: 'Handoffs',
      route: '/nurse-station',
      color: '#4f46e5',
      bg: '#eef2ff',
    },
  ]);

  // ── Doctor workflow tiles (Epic-style Provider Activities) ───
  doctorWorkflowTiles = computed<NavTile[]>(() => [
    {
      icon: 'calendar_month',
      label: 'Schedule',
      route: '/appointments',
      color: '#2563eb',
      bg: '#dbeafe',
    },
    { icon: 'people', label: 'Patients', route: '/patients', color: '#0891b2', bg: '#cffafe' },
    {
      icon: 'stethoscope',
      label: 'Encounters',
      route: '/encounters',
      color: '#059669',
      bg: '#d1fae5',
    },
    {
      icon: 'medication',
      label: 'Prescriptions',
      route: '/prescriptions',
      color: '#7c3aed',
      bg: '#ede9fe',
    },
    { icon: 'science', label: 'Lab Orders', route: '/lab', color: '#0d9488', bg: '#ccfbf1' },
    { icon: 'radiology', label: 'Imaging', route: '/imaging', color: '#d97706', bg: '#fef3c7' },
    { icon: 'hotel', label: 'Admissions', route: '/admissions', color: '#dc2626', bg: '#fee2e2' },
    { icon: 'forum', label: 'Consults', route: '/consultations', color: '#4f46e5', bg: '#eef2ff' },
    {
      icon: 'assignment',
      label: 'Treatment Plans',
      route: '/treatment-plans',
      color: '#ea580c',
      bg: '#fff7ed',
    },
    { icon: 'send', label: 'Referrals', route: '/referrals', color: '#9333ea', bg: '#f3e8ff' },
  ]);

  // ── Receptionist workflow tiles (Epic-style Front Desk) ──────
  receptionistWorkflowTiles = computed<NavTile[]>(() => [
    {
      icon: 'how_to_reg',
      label: 'Check-In',
      route: '/appointments',
      color: '#059669',
      bg: '#d1fae5',
    },
    {
      icon: 'calendar_add_on',
      label: 'Schedule',
      route: '/appointments',
      color: '#2563eb',
      bg: '#dbeafe',
    },
    { icon: 'person_add', label: 'Register', route: '/patients', color: '#0891b2', bg: '#cffafe' },
    { icon: 'people', label: 'Patients', route: '/patients', color: '#7c3aed', bg: '#ede9fe' },
    { icon: 'receipt_long', label: 'Billing', route: '/billing', color: '#d97706', bg: '#fef3c7' },
    {
      icon: 'notifications',
      label: 'Notifications',
      route: '/notifications',
      color: '#dc2626',
      bg: '#fee2e2',
    },
    { icon: 'chat', label: 'Messages', route: '/chat', color: '#4f46e5', bg: '#eef2ff' },
    {
      icon: 'event_note',
      label: 'Scheduling',
      route: '/scheduling',
      color: '#ea580c',
      bg: '#fff7ed',
    },
  ]);

  // ── Lab Scientist workflow tiles ─────────────────────────────
  labWorkflowTiles = computed<NavTile[]>(() => [
    { icon: 'science', label: 'Lab Orders', route: '/lab', color: '#0d9488', bg: '#ccfbf1' },
    { icon: 'biotech', label: 'Process Tests', route: '/lab', color: '#7c3aed', bg: '#ede9fe' },
    { icon: 'task_alt', label: 'Results', route: '/lab', color: '#059669', bg: '#d1fae5' },
    { icon: 'people', label: 'Patients', route: '/patients', color: '#0891b2', bg: '#cffafe' },
    {
      icon: 'clinical_notes',
      label: 'Encounters',
      route: '/encounters',
      color: '#d97706',
      bg: '#fef3c7',
    },
    { icon: 'priority_high', label: 'Urgent', route: '/lab', color: '#dc2626', bg: '#fee2e2' },
    { icon: 'inventory_2', label: 'Inventory', route: '/lab', color: '#4f46e5', bg: '#eef2ff' },
    { icon: 'analytics', label: 'Reports', route: '/lab', color: '#ea580c', bg: '#fff7ed' },
  ]);

  // ── Pharmacist workflow tiles ────────────────────────────────
  pharmacistWorkflowTiles = computed<NavTile[]>(() => [
    {
      icon: 'local_pharmacy',
      label: 'Prescriptions',
      route: '/prescriptions',
      color: '#9333ea',
      bg: '#f3e8ff',
    },
    {
      icon: 'medication_liquid',
      label: 'Dispense',
      route: '/prescriptions',
      color: '#059669',
      bg: '#d1fae5',
    },
    { icon: 'loop', label: 'Refills', route: '/prescriptions', color: '#2563eb', bg: '#dbeafe' },
    { icon: 'people', label: 'Patients', route: '/patients', color: '#0891b2', bg: '#cffafe' },
    {
      icon: 'clinical_notes',
      label: 'Encounters',
      route: '/encounters',
      color: '#d97706',
      bg: '#fef3c7',
    },
    {
      icon: 'warning',
      label: 'Interactions',
      route: '/prescriptions',
      color: '#dc2626',
      bg: '#fee2e2',
    },
    {
      icon: 'inventory_2',
      label: 'Inventory',
      route: '/prescriptions',
      color: '#4f46e5',
      bg: '#eef2ff',
    },
    {
      icon: 'analytics',
      label: 'Reports',
      route: '/prescriptions',
      color: '#ea580c',
      bg: '#fff7ed',
    },
  ]);

  // ── Radiologist workflow tiles ───────────────────────────────
  radiologistWorkflowTiles = computed<NavTile[]>(() => [
    {
      icon: 'radiology',
      label: 'Imaging Studies',
      route: '/imaging',
      color: '#2563eb',
      bg: '#dbeafe',
    },
    {
      icon: 'pending_actions',
      label: 'Pending Reports',
      route: '/imaging',
      color: '#d97706',
      bg: '#fef3c7',
    },
    { icon: 'task_alt', label: 'Completed', route: '/imaging', color: '#059669', bg: '#d1fae5' },
    { icon: 'people', label: 'Patients', route: '/patients', color: '#0891b2', bg: '#cffafe' },
    {
      icon: 'clinical_notes',
      label: 'Encounters',
      route: '/encounters',
      color: '#7c3aed',
      bg: '#ede9fe',
    },
    {
      icon: 'priority_high',
      label: 'STAT Orders',
      route: '/imaging',
      color: '#dc2626',
      bg: '#fee2e2',
    },
    { icon: 'description', label: 'Templates', route: '/imaging', color: '#4f46e5', bg: '#eef2ff' },
    { icon: 'analytics', label: 'Reports', route: '/imaging', color: '#ea580c', bg: '#fff7ed' },
  ]);

  // ── Patient quick-access tiles (Epic MyChart style) ──────────
  patientQuickLinks = computed<NavTile[]>(() => [
    {
      icon: 'calendar_month',
      label: 'Appointments',
      route: '/my-appointments',
      color: '#059669',
      bg: '#d1fae5',
    },
    { icon: 'chat', label: 'Messages', route: '/chat', color: '#2563eb', bg: '#dbeafe' },
    { icon: 'history', label: 'Visits', route: '/my-visits', color: '#4f46e5', bg: '#eef2ff' },
    {
      icon: 'science',
      label: 'Test Results',
      route: '/my-lab-results',
      color: '#7c3aed',
      bg: '#ede9fe',
    },
    {
      icon: 'medication',
      label: 'Medications',
      route: '/my-medications',
      color: '#0d9488',
      bg: '#ccfbf1',
    },
    {
      icon: 'receipt_long',
      label: 'Billing',
      route: '/my-billing',
      color: '#d97706',
      bg: '#fef3c7',
    },
    { icon: 'groups', label: 'Care Team', route: '/my-care-team', color: '#ec4899', bg: '#fce7f3' },
    {
      icon: 'monitor_heart',
      label: 'Vitals',
      route: '/my-vitals',
      color: '#dc2626',
      bg: '#fee2e2',
    },
  ]);

  // ── Upcoming appointments for patient (computed from portal data) ────
  upcomingAppointments = computed(() => {
    const now = new Date();
    return this.myAppointments()
      .filter((a) => a.status !== 'CANCELLED' && new Date(a.date) >= now)
      .slice(0, 3);
  });

  // ────────────────────────────────────────────────────────────
  // LIFECYCLE
  // ────────────────────────────────────────────────────────────

  ngOnInit(): void {
    this.initGreeting();
    this.initProfile();
    this.loadDashboardData();

    this.clockInterval = setInterval(() => {
      this.currentTime.set(this.formatTime(new Date()));
      this.initGreeting();
    }, 60_000);
  }

  ngOnDestroy(): void {
    if (this.clockInterval) clearInterval(this.clockInterval);
  }

  searchPatients(event: Event): void {
    const input = event.target as HTMLInputElement;
    const q = input.value.trim();
    input.value = '';
    if (q) {
      this.router.navigate(['/patients'], { queryParams: { q } });
    } else {
      this.router.navigate(['/patients']);
    }
  }

  // ────────────────────────────────────────────────────────────
  // PRIVATE INITIALIZERS
  // ────────────────────────────────────────────────────────────

  private initGreeting(): void {
    const h = new Date().getHours();
    if (h < 12) this.greeting.set('Good Morning');
    else if (h < 17) this.greeting.set('Good Afternoon');
    else this.greeting.set('Good Evening');
  }

  private initProfile(): void {
    const profile = this.auth.getUserProfile();
    const first = profile?.firstName ?? '';
    const last = profile?.lastName ?? '';
    this.userName.set(first || last ? `${first} ${last}`.trim() : (profile?.username ?? 'User'));

    // Set role flags
    this.isSuperAdmin.set(this.auth.hasAnyRole(['ROLE_SUPER_ADMIN']));
    this.isHospitalAdmin.set(this.auth.hasAnyRole(['ROLE_HOSPITAL_ADMIN', 'ROLE_ADMIN']));
    this.isDoctor.set(this.auth.hasAnyRole(['ROLE_DOCTOR', 'ROLE_PHYSICIAN', 'ROLE_SURGEON']));
    this.isNurse.set(this.auth.hasAnyRole(['ROLE_NURSE']));
    this.isMidwife.set(this.auth.hasAnyRole(['ROLE_MIDWIFE']));
    this.isReceptionist.set(this.auth.hasAnyRole(['ROLE_RECEPTIONIST']));
    this.isLabScientist.set(
      this.auth.hasAnyRole(['ROLE_LAB_SCIENTIST', 'ROLE_LAB_TECHNICIAN', 'ROLE_LAB_MANAGER']),
    );
    this.isPharmacist.set(this.auth.hasAnyRole(['ROLE_PHARMACIST']));
    this.isRadiologist.set(this.auth.hasAnyRole(['ROLE_RADIOLOGIST']));
    this.isPatient.set(this.auth.hasAnyRole(['ROLE_PATIENT']));

    // Title prefix
    if (this.auth.hasAnyRole(['ROLE_DOCTOR', 'ROLE_PHYSICIAN', 'ROLE_SURGEON'])) {
      this.userTitle.set('Dr.');
    } else if (this.auth.hasAnyRole(['ROLE_MIDWIFE'])) {
      this.userTitle.set('Mid.');
    } else {
      this.userTitle.set('');
    }
  }

  private loadDashboardData(): void {
    this.loading.set(true);
    this.scheduleLoading.set(true);

    let pending = 0;
    const done = () => {
      if (--pending <= 0) this.loading.set(false);
    };

    // Super-admin summary
    if (this.isSuperAdmin()) {
      pending++;
      this.dashboardService.getSummary().subscribe({
        next: (s) => {
          this.adminSummary.set(s);
          this.recentAuditEvents.set(s.recentAuditEvents ?? []);
          done();
        },
        error: () => {
          // Set an empty fallback so the dashboard renders with zeros instead of blank
          this.adminSummary.set({
            totalPatients: 0,
            totalUsers: 0,
            activeUsers: 0,
            inactiveUsers: 0,
            totalHospitals: 0,
            activeHospitals: 0,
            inactiveHospitals: 0,
            totalOrganizations: 0,
            activeOrganizations: 0,
            totalDepartments: 0,
            totalRoles: 0,
            totalAssignments: 0,
            activeAssignments: 0,
            inactiveAssignments: 0,
            globalAssignments: 0,
            activeGlobalAssignments: 0,
            todayAppointmentsCount: 0,
            generatedAt: new Date().toISOString(),
            recentAuditEvents: [],
          });
          done();
        },
      });
    }

    // Hospital-admin summary
    if (this.isHospitalAdmin() && !this.isSuperAdmin()) {
      pending++;
      const today = new Date().toISOString().split('T')[0];
      this.dashboardService.getHospitalAdminSummary(today).subscribe({
        next: (s) => {
          this.hospitalAdminSummary.set(s);
          done();
        },
        error: () => done(),
      });
    }

    // Clinical dashboard (doctor / nurse / midwife)
    if (this.isClinician()) {
      pending++;
      this.dashboardService.getClinicalDashboard().subscribe({
        next: (d) => {
          this.kpis.set(d.kpis ?? []);
          this.alerts.set(d.alerts ?? []);
          this.inboxCounts.set(d.inboxCounts ?? null);
          this.onCallStatus.set(d.onCallStatus ?? null);
          this.roomedPatients.set(d.roomedPatients ?? []);
          this.specialization.set(d.specialization ?? null);
          this.departmentName.set(d.departmentName ?? null);
          done();
        },
        error: () => done(),
      });
    }

    // Physician Cockpit (doctor-only)
    if (this.isDoctor()) {
      pending++;
      this.dashboardService.getCriticalStrip().subscribe({
        next: (cs) => {
          this.criticalStrip.set(cs);
          done();
        },
        error: () => done(),
      });

      pending++;
      this.dashboardService.getWorklist().subscribe({
        next: (items) => {
          this.worklistItems.set(items);
          done();
        },
        error: () => done(),
      });

      pending++;
      this.dashboardService.getPatientFlow().subscribe({
        next: (data) => {
          this.patientFlowData.set(data);
          done();
        },
        error: () => done(),
      });

      pending++;
      this.dashboardService.getInbox().subscribe({
        next: (items) => {
          this.inboxItems.set(items);
          done();
        },
        error: () => done(),
      });

      pending++;
      this.dashboardService.getResultReviewQueue().subscribe({
        next: (items) => {
          this.resultQueue.set(items);
          done();
        },
        error: () => done(),
      });
    }

    // Today's appointments (for roles that can see them)
    if (this.permissions.hasPermission('View Appointments')) {
      pending++;
      const today = new Date().toISOString().split('T')[0];
      this.appointmentService.list({ fromDate: today, toDate: today }).subscribe({
        next: (appts) => {
          const sorted = [...appts].sort((a, b) => a.startTime.localeCompare(b.startTime));
          this.todayAppointments.set(sorted);
          this.scheduleLoading.set(false);
          done();
        },
        error: () => {
          this.scheduleLoading.set(false);
          done();
        },
      });
    } else {
      this.scheduleLoading.set(false);
    }

    // Recent patients (doctors see their own recently encountered patients)
    if (this.permissions.hasPermission('View Patient Records')) {
      pending++;
      const recentObs = this.isDoctor()
        ? this.dashboardService.getRecentPatients()
        : this.patientService.list();
      recentObs.subscribe({
        next: (patients) => {
          this.recentPatients.set(patients.slice(0, 6));
          done();
        },
        error: () => done(),
      });
    }

    // Patient portal data (MyChart-style)
    if (this.isPatient()) {
      pending++;
      this.portalService.getHealthSummary().subscribe({
        next: (summary) => {
          this.healthSummary.set(summary);
          done();
        },
        error: () => done(),
      });

      pending++;
      this.portalService.getMyAppointments().subscribe({
        next: (appts) => {
          this.myAppointments.set(appts);
          this.scheduleLoading.set(false);
          done();
        },
        error: () => {
          this.scheduleLoading.set(false);
          done();
        },
      });

      pending++;
      this.portalService.getMyMedications().subscribe({
        next: (meds) => {
          this.myMedications.set(meds);
          done();
        },
        error: () => done(),
      });

      pending++;
      this.portalService.getMyLabResults(5).subscribe({
        next: (results) => {
          this.myLabResults.set(results);
          done();
        },
        error: () => done(),
      });

      pending++;
      this.portalService.getMyInvoices().subscribe({
        next: (invoices) => {
          this.myInvoices.set(invoices);
          done();
        },
        error: () => done(),
      });

      pending++;
      this.portalService.getMyCareTeam().subscribe({
        next: (team) => {
          this.myCareTeam.set(team);
          done();
        },
        error: () => done(),
      });
    }

    if (pending === 0) {
      this.loading.set(false);
      this.scheduleLoading.set(false);
    }
  }

  // ────────────────────────────────────────────────────────────
  // ACTIONS
  // ────────────────────────────────────────────────────────────

  acknowledgeAlert(alertId: string): void {
    this.dashboardService.acknowledgeAlert(alertId).subscribe({
      next: () =>
        this.alerts.update((list) =>
          list.map((a) => (a.id === alertId ? { ...a, acknowledged: true } : a)),
        ),
    });
  }

  refreshDashboard(): void {
    this.loadDashboardData();
  }

  toggleInboxSection(category: string): void {
    this.inboxCollapsedSections.update((s) => {
      const next = new Set(s);
      if (next.has(category)) {
        next.delete(category);
      } else {
        next.add(category);
      }
      return next;
    });
  }

  handleInboxAction(item: ClinicalInboxItem): void {
    switch (item.actionType) {
      case 'SIGN':
        this.router.navigate(['/encounters']);
        break;
      case 'REPLY':
        this.router.navigate(['/chat']);
        break;
      case 'REVIEW':
        if (item.category === 'PHARMACY_CLARIFICATION') {
          this.router.navigate(['/prescriptions']);
        } else if (item.patientId) {
          this.openPatientSnapshot(item.patientId);
        }
        break;
      default:
        if (item.patientId) this.openPatientSnapshot(item.patientId);
    }
  }

  reloadWorklistForDate(date: string): void {
    this.dashboardService.getWorklist(undefined, undefined, date).subscribe({
      next: (items) => this.worklistItems.set(items),
    });
  }

  openPatientSnapshot(patientId: string): void {
    this.snapshotDrawerOpen.set(true);
    this.patientSnapshot.set(null);
    this.dashboardService.getPatientSnapshot(patientId).subscribe({
      next: (s) => this.patientSnapshot.set(s),
      error: () => this.snapshotDrawerOpen.set(false),
    });
  }

  acknowledgeResult(resultId: string): void {
    this.resultQueue.update((q) => q.filter((r) => r.id !== resultId));
  }

  closePatientSnapshot(): void {
    this.snapshotDrawerOpen.set(false);
    this.patientSnapshot.set(null);
  }

  // ────────────────────────────────────────────────────────────
  // TEMPLATE HELPERS
  // ────────────────────────────────────────────────────────────

  getAlertSeverityClass(severity: string): string {
    const map: Record<string, string> = {
      CRITICAL: 'severity-critical',
      URGENT: 'severity-urgent',
      WARNING: 'severity-warning',
      INFO: 'severity-info',
    };
    return `alert-item ${map[severity] ?? ''}`;
  }

  getAlertIcon(severity: string): string {
    const map: Record<string, string> = {
      CRITICAL: 'emergency',
      URGENT: 'warning',
      WARNING: 'info',
      INFO: 'info_i',
    };
    return map[severity] ?? 'notification_important';
  }

  getTriageClass(status: string): string {
    const map: Record<string, string> = {
      TRIAGED: 'triage-triaged',
      READY_FOR_PROVIDER: 'triage-ready',
      TRIAGE_IN_PROGRESS: 'triage-progress',
    };
    return `triage-badge ${map[status] ?? 'triage-default'}`;
  }

  getApptStatusClass(status: string): string {
    const map: Record<string, string> = {
      SCHEDULED: 'status-scheduled',
      CONFIRMED: 'status-confirmed',
      COMPLETED: 'status-completed',
      CANCELLED: 'status-cancelled',
      NO_SHOW: 'status-noshow',
      IN_PROGRESS: 'status-inprogress',
      REQUESTED: 'status-requested',
    };
    return `appt-status ${map[status] ?? ''}`;
  }

  getApptStatusLabel(status: string): string {
    const map: Record<string, string> = {
      SCHEDULED: 'Scheduled',
      CONFIRMED: 'Confirmed',
      COMPLETED: 'Done',
      CANCELLED: 'Cancelled',
      NO_SHOW: 'No Show',
      IN_PROGRESS: 'In Progress',
      REQUESTED: 'Requested',
    };
    return map[status] ?? status;
  }

  getTrendIcon(trend: string): string {
    if (trend === 'up') return 'trending_up';
    if (trend === 'down') return 'trending_down';
    return 'trending_flat';
  }

  getTrendClass(trend: string): string {
    if (trend === 'up') return 'trend-pill trend-up';
    if (trend === 'down') return 'trend-pill trend-down';
    return 'trend-pill trend-stable';
  }

  getPatientInitials(name: string): string {
    const parts = (name ?? '').trim().split(' ');
    if (parts.length >= 2) return `${parts[0][0]}${parts[parts.length - 1][0]}`.toUpperCase();
    return (parts[0]?.[0] ?? '?').toUpperCase();
  }

  getAvatarColor(name: string): string {
    const colors = [
      '#2563eb',
      '#059669',
      '#7c3aed',
      '#d97706',
      '#dc2626',
      '#0891b2',
      '#db2777',
      '#65a30d',
    ];
    let hash = 0;
    for (let i = 0; i < (name?.length ?? 0); i++) {
      hash = name.charCodeAt(i) + ((hash << 5) - hash);
    }
    return colors[Math.abs(hash) % colors.length];
  }

  /** Format an ISO/HH:mm time string as 12-hour for display */
  formatApptTime(time: string): string {
    if (!time) return '';
    const [h, m] = time.split(':').map(Number);
    const ampm = h >= 12 ? 'PM' : 'AM';
    const hour = h % 12 || 12;
    return `${hour}:${String(m).padStart(2, '0')} ${ampm}`;
  }

  private formatTime(d: Date): string {
    return d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', hour12: true });
  }

  getIntegrationStatusClass(status: string): string {
    const map: Record<string, string> = {
      ACTIVE: 'integration-active',
      PILOT: 'integration-pilot',
      INACTIVE: 'integration-inactive',
      PENDING: 'integration-pending',
      DECOMMISSIONED: 'integration-decommissioned',
    };
    return `integration-badge ${map[status] ?? ''}`;
  }

  getAgingBucketColor(index: number): string {
    const colors = ['#059669', '#2563eb', '#d97706', '#ea580c', '#dc2626'];
    return colors[index] ?? '#64748b';
  }

  getPaymentMethodColor(method: string): string {
    const map: Record<string, string> = {
      CASH: '#059669',
      CREDIT_CARD: '#2563eb',
      DEBIT_CARD: '#7c3aed',
      INSURANCE: '#0891b2',
      BANK_TRANSFER: '#d97706',
      CHECK: '#ea580c',
      OTHER: '#64748b',
    };
    return map[method] ?? '#64748b';
  }

  formatMethodLabel(method: string): string {
    return method.replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
  }
}
