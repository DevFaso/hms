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
  LabDirectorDashboard,
  QualityManagerDashboard,
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
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Subscription } from 'rxjs';
import { DoctorWorklistComponent } from './doctor-worklist/doctor-worklist';
import { DoctorPatientFlowComponent } from './doctor-patient-flow/doctor-patient-flow';
import { DoctorResultsPanelComponent } from './doctor-results-panel/doctor-results-panel';
import { PatientSnapshotDrawerComponent } from './patient-snapshot-drawer/patient-snapshot-drawer';
import { InBasketPanelComponent } from './in-basket-panel/in-basket-panel';
import { EncounterService } from '../services/encounter.service';
import { ToastService } from '../core/toast.service';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';

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
    TranslateModule,
    DoctorWorklistComponent,
    DoctorPatientFlowComponent,
    DoctorResultsPanelComponent,
    PatientSnapshotDrawerComponent,
    InBasketPanelComponent,
    EnumLabelPipe,
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
  private readonly encounterService = inject(EncounterService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  /**
   * Bumps every time the active language changes. Reading this signal inside a
   * `computed()` makes the array re-evaluate so localized labels follow the
   * language switch without forcing a component re-mount.
   */
  private readonly langTick = signal(0);
  private langSub?: Subscription;

  // ── Time & Identity ──────────────────────────────────────────
  greeting = signal('');
  userName = signal('');
  userTitle = signal('');
  currentTime = signal(this.formatTime(new Date()));
  todayLabel = signal(this.formatTodayLabel(this.translate.currentLang));
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
  isLabDirector = signal(false);
  isQualityManager = signal(false);
  isPharmacist = signal(false);
  isRadiologist = signal(false);
  isPatient = signal(false);

  /** True for DOCTOR / NURSE / MIDWIFE — gets clinical workspace layout */
  isClinician = computed(() => this.isDoctor() || this.isNurse() || this.isMidwife());

  /** True for anyone who has View Appointments permission but is not a clinician */
  isSchedulingRole = computed(
    () => !this.isClinician() && this.permissions.hasPermission('View Appointments'),
  );

  // ── Hospital-Admin data ────────────────────────────────────
  hospitalAdminSummary = signal<HospitalAdminSummary | null>(null);

  // ── Lab Director data ─────────────────────────────────────
  labDirectorDashboard = signal<LabDirectorDashboard | null>(null);

  // ── Quality Manager data ──────────────────────────────────
  qualityManagerDashboard = signal<QualityManagerDashboard | null>(null);

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
  /** Translation keys keyed by inbox category code. */
  readonly _inboxLabelKeys: Record<string, string> = {
    MESSAGE: 'DASHBOARD.MESSAGES',
    CRITICAL_RESULT: 'DASHBOARD.CRITICAL_RESULTS',
    CONSULT_REQUEST: 'DASHBOARD.CONSULT_REQUESTS',
    DOCUMENT_TO_SIGN: 'DASHBOARD.DOCUMENTS_TO_SIGN',
    REFILL_REQUEST: 'DASHBOARD.REFILL_REQUESTS',
    PHARMACY_CLARIFICATION: 'DASHBOARD.PHARMACY_CLARIFICATIONS',
    TASK: 'DASHBOARD.TASKS',
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
    this.langTick();
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
        label: this.t(this._inboxLabelKeys[cat] ?? cat),
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
    if (this.isHospitalAdmin()) return 'hero-gradient-hospital-admin';
    if (this.isDoctor()) return 'hero-gradient-doctor';
    if (this.isNurse() || this.isMidwife()) return 'hero-gradient-nurse';
    if (this.isReceptionist()) return 'hero-gradient-receptionist';
    if (this.isLabDirector()) return 'hero-gradient-lab-director';
    if (this.isQualityManager()) return 'hero-gradient-quality-manager';
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
    | 'hospitaladmin'
    | 'doctor'
    | 'nurse'
    | 'receptionist'
    | 'lab-director'
    | 'quality-manager'
    | 'lab'
    | 'pharmacist'
    | 'radiologist'
    | 'patient'
    | 'fallback'
  >(() => {
    if (this.isPatient()) return 'patient';
    if (this.isHospitalAdmin()) return 'hospitaladmin';
    if (this.isDoctor()) return 'doctor';
    if (this.isNurse() || this.isMidwife()) return 'nurse';
    if (this.isReceptionist()) return 'receptionist';
    if (this.isLabDirector()) return 'lab-director';
    if (this.isQualityManager()) return 'quality-manager';
    if (this.isLabScientist()) return 'lab';
    if (this.isPharmacist()) return 'pharmacist';
    if (this.isRadiologist()) return 'radiologist';
    return 'fallback';
  });

  // ── Role display label ────────────────────────────────────────
  roleLabel = computed(() => {
    this.langTick();
    if (this.isPatient()) return this.t('DASHBOARD.ROLE.PATIENT');
    if (this.isSuperAdmin()) return this.t('DASHBOARD.ROLE.SUPER_ADMIN');
    if (this.isHospitalAdmin()) return this.t('DASHBOARD.ROLE.HOSPITAL_ADMIN');
    if (this.isDoctor()) return this.t('DASHBOARD.ROLE.DOCTOR');
    if (this.isMidwife()) return this.t('DASHBOARD.ROLE.MIDWIFE');
    if (this.isNurse()) return this.t('DASHBOARD.ROLE.NURSE');
    if (this.isReceptionist()) return this.t('DASHBOARD.ROLE.RECEPTIONIST');
    if (this.isLabDirector()) return this.t('DASHBOARD.ROLE.LAB_DIRECTOR');
    if (this.isQualityManager()) return this.t('DASHBOARD.ROLE.QUALITY_MANAGER');
    if (this.isLabScientist()) return this.t('DASHBOARD.ROLE.LAB_SCIENTIST');
    if (this.isPharmacist()) return this.t('DASHBOARD.ROLE.PHARMACIST');
    if (this.isRadiologist()) return this.t('DASHBOARD.ROLE.RADIOLOGIST');
    return this.t('DASHBOARD.ROLE.STAFF');
  });

  // ── Critical Strip (doctor — actionable cards) ───────────────
  criticalStripCards = computed<StatCard[]>(() => {
    this.langTick();
    const cs = this.criticalStrip();
    if (!cs) return [];
    return [
      {
        key: 'critical_labs',
        label: this.t('DASHBOARD.CRITICAL_LABS'),
        value: cs.criticalLabsCount,
        icon: 'science',
        color: '#dc2626',
        bgColor: '#fee2e2',
        route: '/lab',
      },
      {
        key: 'waiting_long',
        label: this.t('DASHBOARD.WAITING_LONG'),
        value: cs.waitingLongCount,
        icon: 'hourglass_top',
        color: cs.waitingLongCount > 0 ? '#d97706' : '#64748b',
        bgColor: cs.waitingLongCount > 0 ? '#fef3c7' : '#f1f5f9',
      },
      {
        key: 'pending_consults',
        label: this.t('DASHBOARD.PENDING_CONSULTS'),
        value: cs.pendingConsultsCount,
        icon: 'forum',
        color: cs.pendingConsultsCount > 0 ? '#7c3aed' : '#64748b',
        bgColor: cs.pendingConsultsCount > 0 ? '#ede9fe' : '#f1f5f9',
        route: '/consultations',
      },
      {
        key: 'unsigned_notes',
        label: this.t('DASHBOARD.UNSIGNED_NOTES'),
        value: cs.unsignedNotesCount,
        icon: 'draw',
        color: cs.unsignedNotesCount > 0 ? '#d97706' : '#64748b',
        bgColor: cs.unsignedNotesCount > 0 ? '#fef3c7' : '#f1f5f9',
      },
      {
        key: 'pending_orders',
        label: this.t('DASHBOARD.ORDERS_TO_REVIEW'),
        value: cs.pendingOrderReviewCount,
        icon: 'assignment',
        color: cs.pendingOrderReviewCount > 0 ? '#2563eb' : '#64748b',
        bgColor: cs.pendingOrderReviewCount > 0 ? '#dbeafe' : '#f1f5f9',
        route: '/lab',
      },
      {
        key: 'safety_alerts',
        label: this.t('DASHBOARD.SAFETY_ALERTS'),
        value: cs.activeSafetyAlertsCount,
        icon: 'emergency',
        color: cs.activeSafetyAlertsCount > 0 ? '#dc2626' : '#64748b',
        bgColor: cs.activeSafetyAlertsCount > 0 ? '#fee2e2' : '#f1f5f9',
      },
    ];
  });

  // ── Stat strip (generic fallback for non-doctor clinical) ───
  statCards = computed<StatCard[]>(() => {
    this.langTick();
    const base: StatCard[] = [
      {
        key: 'total_today',
        label: this.t('DASHBOARD.PATIENTS_TODAY'),
        value: this.todayAppointments().length,
        icon: 'group',
        color: '#2563eb',
        bgColor: '#eff6ff',
        route: '/appointments',
      },
      {
        key: 'completed',
        label: this.t('COMMON.COMPLETED'),
        value: this.completedToday(),
        icon: 'task_alt',
        color: '#2563eb',
        bgColor: '#eff6ff',
        route: '/appointments',
      },
      {
        key: 'in_progress',
        label: this.t('DASHBOARD.IN_PROGRESS'),
        value: this.inProgressNow(),
        icon: 'person_play',
        color: '#2563eb',
        bgColor: '#eff6ff',
        route: '/appointments',
      },
      {
        key: 'pending',
        label: this.t('DASHBOARD.PENDING'),
        value: this.pendingToday(),
        icon: 'pending',
        color: '#2563eb',
        bgColor: '#eff6ff',
        route: '/appointments',
      },
      {
        key: 'alerts',
        label: this.t('DASHBOARD.ACTIVE_ALERTS'),
        value: this.unacknowledgedAlerts().length,
        icon: 'notification_important',
        color: this.criticalAlerts().length > 0 ? '#dc2626' : '#2563eb',
        bgColor: this.criticalAlerts().length > 0 ? '#fee2e2' : '#eff6ff',
      },
      {
        key: 'inbox',
        label: this.t('DASHBOARD.INBOX_ITEMS'),
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
    this.langTick();
    const all: [string, QuickAction][] = [
      // Doctor-priority shortcuts (appear first, dedup 'Start Encounter' on /encounters route)
      ...(this.isDoctor()
        ? ([
            [
              'Create Encounters',
              {
                icon: 'navigate_next',
                label: this.t('DASHBOARD.QA.OPEN_NEXT_PATIENT'),
                description: this.t('DASHBOARD.QA.OPEN_NEXT_PATIENT_DESC'),
                route: '/encounters',
                color: '#059669',
                bgColor: '#d1fae5',
              },
            ],
            [
              'Create Encounters',
              {
                icon: 'meeting_room',
                label: this.t('DASHBOARD.QA.DISCHARGE_PATIENT'),
                description: this.t('DASHBOARD.QA.DISCHARGE_PATIENT_DESC'),
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
          label: this.t('DASHBOARD.REGISTER_PATIENT'),
          description: this.t('DASHBOARD.QA.REGISTER_PATIENT_DESC'),
          route: '/patients/new',
          color: '#2563eb',
          bgColor: '#eff6ff',
        },
      ],
      [
        'Create Appointments',
        {
          icon: 'calendar_add_on',
          label: this.t('DASHBOARD.NEW_APPOINTMENT'),
          description: this.t('DASHBOARD.QA.NEW_APPOINTMENT_DESC'),
          route: '/appointments/new',
          color: '#2563eb',
          bgColor: '#eff6ff',
        },
      ],
      [
        'Create Encounters',
        {
          icon: 'stethoscope',
          label: this.t('DASHBOARD.START_ENCOUNTER'),
          description: this.t('DASHBOARD.QA.START_ENCOUNTER_DESC'),
          route: '/encounters',
          color: '#2563eb',
          bgColor: '#eff6ff',
        },
      ],
      [
        'Create Prescriptions',
        {
          icon: 'medication',
          label: this.t('DASHBOARD.WRITE_RX'),
          description: this.t('DASHBOARD.QA.WRITE_RX_DESC'),
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
          label: this.t('DASHBOARD.LAB_ORDERS'),
          description: this.t('DASHBOARD.QA.LAB_ORDERS_DESC'),
          route: '/lab',
          color: '#2563eb',
          bgColor: '#eff6ff',
        },
      ],
      [
        'Request Imaging Studies',
        {
          icon: 'radiology',
          label: this.t('DASHBOARD.IMAGING'),
          description: this.t('DASHBOARD.QA.IMAGING_DESC'),
          route: '/imaging',
          color: '#2563eb',
          bgColor: '#eff6ff',
        },
      ],
      [
        'View Billing',
        {
          icon: 'receipt_long',
          label: this.t('DASHBOARD.BILLING'),
          description: this.t('DASHBOARD.QA.BILLING_DESC'),
          route: '/billing',
          color: '#2563eb',
          bgColor: '#eff6ff',
        },
      ],
      [
        'Create Referrals',
        {
          icon: 'send',
          label: this.t('DASHBOARD.QA.CREATE_REFERRAL'),
          description: this.t('DASHBOARD.QA.CREATE_REFERRAL_DESC'),
          route: '/referrals',
          color: '#2563eb',
          bgColor: '#eff6ff',
        },
      ],
      [
        'View Prescriptions',
        {
          icon: 'local_pharmacy',
          label: this.t('DASHBOARD.PRESCRIPTIONS'),
          description: this.t('DASHBOARD.QA.PRESCRIPTIONS_DESC'),
          route: '/prescriptions',
          color: '#2563eb',
          bgColor: '#eff6ff',
        },
      ],
      [
        'Dispense Medications',
        {
          icon: 'medication_liquid',
          label: this.t('DASHBOARD.QA.DISPENSE_RX'),
          description: this.t('DASHBOARD.QA.DISPENSE_RX_DESC'),
          route: '/prescriptions',
          color: '#2563eb',
          bgColor: '#eff6ff',
        },
      ],
      [
        'Process Lab Tests',
        {
          icon: 'biotech',
          label: this.t('DASHBOARD.QA.PROCESS_LAB'),
          description: this.t('DASHBOARD.QA.PROCESS_LAB_DESC'),
          route: '/lab',
          color: '#2563eb',
          bgColor: '#eff6ff',
        },
      ],
      [
        'Document Nursing Notes',
        {
          icon: 'edit_note',
          label: this.t('DASHBOARD.QA.NURSING_NOTES'),
          description: this.t('DASHBOARD.QA.NURSING_NOTES_DESC'),
          route: '/encounters',
          color: '#2563eb',
          bgColor: '#eff6ff',
        },
      ],
      [
        'Check-in Patients',
        {
          icon: 'how_to_reg',
          label: this.t('DASHBOARD.QA.CHECK_IN'),
          description: this.t('DASHBOARD.QA.CHECK_IN_DESC'),
          route: '/reception',
          color: '#2563eb',
          bgColor: '#eff6ff',
        },
      ],
    ];

    const seen = new Set<string>();
    const actions: QuickAction[] = [];
    for (const [perm, action] of all) {
      if (
        !seen.has(action.route) &&
        this.permissions.hasPermission(perm) &&
        this.canAccessRoute(action.route)
      ) {
        seen.add(action.route);
        actions.push(action);
        if (actions.length >= 6) break;
      }
    }
    return actions;
  });

  /** Check whether the current user can navigate to a role-guarded route. */
  private canAccessRoute(route: string): boolean {
    const normalizedPath = route.replace(/^\//, '');
    const entry = this.findRouteRecursive(this.router.config, normalizedPath);
    const requiredRoles = entry?.data?.['roles'];
    if (!Array.isArray(requiredRoles) || requiredRoles.length === 0) {
      return true; // no role guard → accessible
    }
    return this.auth.hasAnyRole(requiredRoles);
  }

  /** Walk the route tree (including children) to find a matching route config. */
  private findRouteRecursive(
    routes: import('@angular/router').Routes,
    path: string,
  ): import('@angular/router').Route | undefined {
    for (const r of routes) {
      if (r.path === path) return r;
      if (r.children) {
        const found = this.findRouteRecursive(r.children, path);
        if (found) return found;
      }
    }
    return undefined;
  }

  // ── Hospital Admin navigation tiles ──────────────────────────
  hospitalAdminNavTiles = computed<NavTile[]>(() => {
    this.langTick();
    return [
      {
        icon: 'group',
        label: this.t('DASHBOARD.TILE.STAFF'),
        route: '/staff',
        color: '#2563eb',
        bg: '#eff6ff',
        count: undefined,
      },
      {
        icon: 'people',
        label: this.t('PATIENTS.TITLE'),
        route: '/patients',
        color: '#2563eb',
        bg: '#eff6ff',
      },
      {
        icon: 'calendar_month',
        label: this.t('DASHBOARD.TILE.APPOINTMENTS'),
        route: '/appointments',
        color: '#2563eb',
        bg: '#eff6ff',
        count: this.todayAppointments().length,
      },
      {
        icon: 'domain',
        label: this.t('DASHBOARD.DEPARTMENTS'),
        route: '/departments',
        color: '#2563eb',
        bg: '#eff6ff',
      },
      {
        icon: 'receipt_long',
        label: this.t('DASHBOARD.BILLING'),
        route: '/billing',
        color: '#2563eb',
        bg: '#eff6ff',
      },
      {
        icon: 'policy',
        label: this.t('DASHBOARD.TILE.AUDIT_LOGS'),
        route: '/audit-logs',
        color: '#2563eb',
        bg: '#eff6ff',
      },
    ];
  });

  // ── Hospital Admin stat cards (from summary API) ────────────
  hospitalAdminStatCards = computed<StatCard[]>(() => {
    this.langTick();
    const s = this.hospitalAdminSummary();
    if (!s || !s.appointments) return [];
    return [
      {
        key: 'appts_today',
        label: this.t('DASHBOARD.TODAYS_APPOINTMENTS'),
        value: s.appointments.todayTotal,
        icon: 'calendar_today',
        color: '#2563eb',
        bgColor: '#eff6ff',
        route: '/appointments',
      },
      {
        key: 'appts_completed',
        label: this.t('COMMON.COMPLETED'),
        value: s.appointments.completed,
        icon: 'task_alt',
        color: '#059669',
        bgColor: '#d1fae5',
        route: '/appointments',
      },
      {
        key: 'appts_noshow',
        label: this.t('DASHBOARD.NO_SHOWS'),
        value: s.appointments.noShows,
        icon: 'person_off',
        color: s.appointments.noShows > 0 ? '#dc2626' : '#64748b',
        bgColor: s.appointments.noShows > 0 ? '#fee2e2' : '#f1f5f9',
        route: '/appointments',
      },
      {
        key: 'admissions_active',
        label: this.t('DASHBOARD.ACTIVE_ADMISSIONS'),
        value: s.admissions.active,
        icon: 'hotel',
        color: '#7c3aed',
        bgColor: '#ede9fe',
        route: '/admissions',
      },
      {
        key: 'admissions_today',
        label: this.t('DASHBOARD.ADMITTED_TODAY'),
        value: s.admissions.admittedToday,
        icon: 'login',
        color: '#2563eb',
        bgColor: '#dbeafe',
        route: '/admissions',
      },
      {
        key: 'discharged_today',
        label: this.t('DASHBOARD.DISCHARGED_TODAY'),
        value: s.admissions.dischargedToday,
        icon: 'logout',
        color: '#059669',
        bgColor: '#d1fae5',
        route: '/admissions',
      },
      {
        key: 'consults_pending',
        label: this.t('DASHBOARD.CONSULTS_PENDING'),
        value: s.consultations.requested + s.consultations.acknowledged,
        icon: 'forum',
        color: '#d97706',
        bgColor: '#fef3c7',
        route: '/consultations',
      },
      {
        key: 'consults_overdue',
        label: this.t('DASHBOARD.CONSULTS_OVERDUE'),
        value: s.consultations.overdue,
        icon: 'warning',
        color: s.consultations.overdue > 0 ? '#dc2626' : '#64748b',
        bgColor: s.consultations.overdue > 0 ? '#fee2e2' : '#f1f5f9',
        route: '/consultations',
      },
      {
        key: 'staff_active',
        label: this.t('DASHBOARD.ACTIVE_STAFF'),
        value: s.staffing.activeStaff,
        icon: 'badge',
        color: '#0891b2',
        bgColor: '#cffafe',
        route: '/staff',
      },
      {
        key: 'staff_on_shift',
        label: this.t('DASHBOARD.ON_SHIFT_TODAY'),
        value: s.staffing.onShiftToday,
        icon: 'work',
        color: '#059669',
        bgColor: '#d1fae5',
        route: '/staff',
      },
      {
        key: 'staff_on_leave',
        label: this.t('DASHBOARD.ON_LEAVE_TODAY'),
        value: s.staffing.staffOnLeaveToday ?? 0,
        icon: 'event_busy',
        color: (s.staffing.staffOnLeaveToday ?? 0) > 0 ? '#d97706' : '#64748b',
        bgColor: (s.staffing.staffOnLeaveToday ?? 0) > 0 ? '#fef3c7' : '#f1f5f9',
        route: '/staff',
      },
      {
        key: 'license_alerts',
        label: this.t('DASHBOARD.LICENSE_ALERTS'),
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
        label: this.t('DASHBOARD.OVERDUE_INVOICES'),
        value: s.billing.overdueInvoices,
        icon: 'receipt_long',
        color: s.billing.overdueInvoices > 0 ? '#dc2626' : '#64748b',
        bgColor: s.billing.overdueInvoices > 0 ? '#fee2e2' : '#f1f5f9',
        route: '/billing',
      },
      {
        key: 'open_balance',
        label: this.t('DASHBOARD.OPEN_BALANCE'),
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
  nurseWorkflowTiles = computed<NavTile[]>(() => {
    this.langTick();
    return [
      {
        icon: 'monitor_heart',
        label: this.t('DASHBOARD.TILE.VITALS'),
        route: '/nurse-station',
        color: '#dc2626',
        bg: '#fee2e2',
      },
      {
        icon: 'medication',
        label: this.t('DASHBOARD.TILE.MAR'),
        route: '/nurse-station',
        color: '#7c3aed',
        bg: '#ede9fe',
      },
      {
        icon: 'how_to_reg',
        label: this.t('DASHBOARD.TILE.CHECK_IN'),
        route: '/nurse-station',
        color: '#059669',
        bg: '#d1fae5',
      },
      {
        icon: 'hotel',
        label: this.t('DASHBOARD.TILE.ADMISSIONS'),
        route: '/admissions',
        color: '#2563eb',
        bg: '#dbeafe',
      },
      {
        icon: 'people',
        label: this.t('PATIENTS.TITLE'),
        route: '/patients',
        color: '#0891b2',
        bg: '#cffafe',
      },
      {
        icon: 'clinical_notes',
        label: this.t('DASHBOARD.TILE.ENCOUNTERS'),
        route: '/encounters',
        color: '#d97706',
        bg: '#fef3c7',
      },
      {
        icon: 'local_pharmacy',
        label: this.t('DASHBOARD.PRESCRIPTIONS'),
        route: '/prescriptions',
        color: '#9333ea',
        bg: '#f3e8ff',
      },
      {
        icon: 'science',
        label: this.t('DASHBOARD.LAB_ORDERS'),
        route: '/lab',
        color: '#0d9488',
        bg: '#ccfbf1',
      },
      {
        icon: 'assignment',
        label: this.t('DASHBOARD.TASKS'),
        route: '/nurse-station',
        color: '#ea580c',
        bg: '#fff7ed',
      },
      {
        icon: 'swap_horiz',
        label: this.t('DASHBOARD.TILE.HANDOFFS'),
        route: '/nurse-station',
        color: '#4f46e5',
        bg: '#eef2ff',
      },
    ];
  });

  // ── Doctor workflow tiles (Epic-style Provider Activities) ───
  doctorWorkflowTiles = computed<NavTile[]>(() => {
    this.langTick();
    return [
      {
        icon: 'calendar_month',
        label: this.t('DASHBOARD.SCHEDULE'),
        route: '/appointments',
        color: '#2563eb',
        bg: '#dbeafe',
      },
      {
        icon: 'people',
        label: this.t('PATIENTS.TITLE'),
        route: '/patients',
        color: '#0891b2',
        bg: '#cffafe',
      },
      {
        icon: 'stethoscope',
        label: this.t('DASHBOARD.TILE.ENCOUNTERS'),
        route: '/encounters',
        color: '#059669',
        bg: '#d1fae5',
      },
      {
        icon: 'medication',
        label: this.t('DASHBOARD.PRESCRIPTIONS'),
        route: '/prescriptions',
        color: '#7c3aed',
        bg: '#ede9fe',
      },
      {
        icon: 'science',
        label: this.t('DASHBOARD.LAB_ORDERS'),
        route: '/lab',
        color: '#0d9488',
        bg: '#ccfbf1',
      },
      {
        icon: 'radiology',
        label: this.t('DASHBOARD.IMAGING'),
        route: '/imaging',
        color: '#d97706',
        bg: '#fef3c7',
      },
      {
        icon: 'hotel',
        label: this.t('DASHBOARD.TILE.ADMISSIONS'),
        route: '/admissions',
        color: '#dc2626',
        bg: '#fee2e2',
      },
      {
        icon: 'forum',
        label: this.t('DASHBOARD.TILE.CONSULTS'),
        route: '/consultations',
        color: '#4f46e5',
        bg: '#eef2ff',
      },
      {
        icon: 'assignment',
        label: this.t('DASHBOARD.TILE.TREATMENT_PLANS'),
        route: '/treatment-plans',
        color: '#ea580c',
        bg: '#fff7ed',
      },
      {
        icon: 'send',
        label: this.t('DASHBOARD.TILE.REFERRALS'),
        route: '/referrals',
        color: '#9333ea',
        bg: '#f3e8ff',
      },
    ];
  });

  // ── Receptionist workflow tiles (Epic-style Front Desk) ──────
  // Tiles ordered by daily front-desk workflow:
  //   patient lifecycle → schedule → billing → comms.
  // Two routes were previously duplicated (REGISTER + Patients both went to
  // /patients); REGISTER now points at /patients/new so each tile leads
  // somewhere distinct. Color palette is constrained to the same blue/teal
  // family as the hero banner so the dashboard reads as one product
  // surface rather than a rainbow of unrelated apps.
  receptionistWorkflowTiles = computed<NavTile[]>(() => {
    this.langTick();
    return [
      {
        icon: 'how_to_reg',
        label: this.t('DASHBOARD.TILE.CHECK_IN'),
        route: '/reception',
        color: '#1d4ed8',
        bg: '#dbeafe',
      },
      {
        icon: 'person_add',
        label: this.t('DASHBOARD.TILE.REGISTER'),
        route: '/patients/new',
        color: '#0e7490',
        bg: '#cffafe',
      },
      {
        icon: 'people',
        label: this.t('PATIENTS.TITLE'),
        route: '/patients',
        color: '#0369a1',
        bg: '#e0f2fe',
      },
      {
        icon: 'calendar_add_on',
        label: this.t('DASHBOARD.SCHEDULE'),
        route: '/appointments',
        color: '#2563eb',
        bg: '#dbeafe',
      },
      {
        icon: 'event_note',
        label: this.t('DASHBOARD.TILE.SCHEDULING'),
        route: '/scheduling',
        color: '#1e40af',
        bg: '#dbeafe',
      },
      {
        icon: 'receipt_long',
        label: this.t('DASHBOARD.BILLING'),
        route: '/billing',
        color: '#0891b2',
        bg: '#cffafe',
      },
      {
        icon: 'chat',
        label: this.t('DASHBOARD.MESSAGES'),
        route: '/chat',
        color: '#1d4ed8',
        bg: '#dbeafe',
      },
      {
        icon: 'notifications',
        label: this.t('DASHBOARD.TILE.NOTIFICATIONS'),
        route: '/notifications',
        color: '#0369a1',
        bg: '#e0f2fe',
      },
    ];
  });

  // ── Lab Scientist workflow tiles ─────────────────────────────
  labWorkflowTiles = computed<NavTile[]>(() => {
    this.langTick();
    return [
      {
        icon: 'science',
        label: this.t('DASHBOARD.LAB_ORDERS'),
        route: '/lab',
        color: '#0d9488',
        bg: '#ccfbf1',
      },
      {
        icon: 'biotech',
        label: this.t('DASHBOARD.TILE.PROCESS_TESTS'),
        route: '/lab',
        color: '#7c3aed',
        bg: '#ede9fe',
      },
      {
        icon: 'task_alt',
        label: this.t('DASHBOARD.TILE.RESULTS'),
        route: '/lab',
        color: '#059669',
        bg: '#d1fae5',
      },
      {
        icon: 'people',
        label: this.t('PATIENTS.TITLE'),
        route: '/patients',
        color: '#0891b2',
        bg: '#cffafe',
      },
      {
        icon: 'clinical_notes',
        label: this.t('DASHBOARD.TILE.ENCOUNTERS'),
        route: '/encounters',
        color: '#d97706',
        bg: '#fef3c7',
      },
      {
        icon: 'priority_high',
        label: this.t('DASHBOARD.URGENT'),
        route: '/lab',
        color: '#dc2626',
        bg: '#fee2e2',
      },
      {
        icon: 'inventory_2',
        label: this.t('DASHBOARD.TILE.INVENTORY'),
        route: '/lab',
        color: '#4f46e5',
        bg: '#eef2ff',
      },
      {
        icon: 'analytics',
        label: this.t('DASHBOARD.TILE.REPORTS'),
        route: '/lab',
        color: '#ea580c',
        bg: '#fff7ed',
      },
    ];
  });

  // ── Lab Director stat cards ───────────────────────────────────
  labDirectorStatCards = computed<StatCard[]>(() => {
    this.langTick();
    const d = this.labDirectorDashboard();
    if (!d) return [];
    const tatValue: number | string =
      d.avgTurnaroundMinutesToday != null ? Math.round(d.avgTurnaroundMinutesToday) : 'N/A';
    return [
      {
        key: 'pending_director',
        label: this.t('DASHBOARD.PENDING_YOUR_APPROVAL'),
        value: d.pendingDirectorApproval,
        icon: 'approval',
        color: d.pendingDirectorApproval > 0 ? '#dc2626' : '#64748b',
        bgColor: d.pendingDirectorApproval > 0 ? '#fee2e2' : '#f1f5f9',
        route: '/lab',
      },
      {
        key: 'pending_qa',
        label: this.t('DASHBOARD.PENDING_QA_REVIEW'),
        value: d.pendingQaReview,
        icon: 'fact_check',
        color: d.pendingQaReview > 0 ? '#d97706' : '#64748b',
        bgColor: d.pendingQaReview > 0 ? '#fef3c7' : '#f1f5f9',
        route: '/lab',
      },
      {
        key: 'active_definitions',
        label: this.t('DASHBOARD.ACTIVE_TEST_DEFINITIONS'),
        value: d.activeDefinitions,
        icon: 'science',
        color: '#059669',
        bgColor: '#d1fae5',
        route: '/lab',
      },
      {
        key: 'orders_today',
        label: this.t('DASHBOARD.ORDERS_TODAY'),
        value: d.ordersToday,
        icon: 'assignment',
        color: '#2563eb',
        bgColor: '#dbeafe',
        route: '/lab',
      },
      {
        key: 'avg_tat',
        label: this.t('DASHBOARD.AVG_TURNAROUND'),
        value: tatValue,
        icon: 'timer',
        color: '#7c3aed',
        bgColor: '#ede9fe',
      },
    ];
  });

  // ── Lab Director workflow tiles ───────────────────────────────
  labDirectorNavTiles = computed<NavTile[]>(() => {
    this.langTick();
    const d = this.labDirectorDashboard();
    return [
      {
        icon: 'approval',
        label: this.t('DASHBOARD.TILE.APPROVAL_QUEUE'),
        route: '/lab',
        color: '#dc2626',
        bg: '#fee2e2',
        count: d?.pendingDirectorApproval,
      },
      {
        icon: 'fact_check',
        label: this.t('DASHBOARD.TILE.QA_REVIEWS'),
        route: '/lab',
        color: '#d97706',
        bg: '#fef3c7',
        count: d?.pendingQaReview,
      },
      {
        icon: 'science',
        label: this.t('DASHBOARD.LAB_ORDERS'),
        route: '/lab',
        color: '#0d9488',
        bg: '#ccfbf1',
      },
      {
        icon: 'biotech',
        label: this.t('DASHBOARD.TILE.TEST_DEFINITIONS'),
        route: '/lab',
        color: '#7c3aed',
        bg: '#ede9fe',
      },
      {
        icon: 'analytics',
        label: this.t('DASHBOARD.VALIDATION_STUDIES'),
        route: '/lab-qc-dashboard',
        color: '#2563eb',
        bg: '#dbeafe',
      },
      {
        icon: 'people',
        label: this.t('PATIENTS.TITLE'),
        route: '/patients',
        color: '#0891b2',
        bg: '#cffafe',
      },
      {
        icon: 'history',
        label: this.t('DASHBOARD.TILE.AUDIT_TRAIL'),
        route: '/audit-logs',
        color: '#4f46e5',
        bg: '#eef2ff',
      },
      {
        icon: 'bar_chart',
        label: this.t('DASHBOARD.TILE.QC_DASHBOARD'),
        route: '/lab-qc-dashboard',
        color: '#ea580c',
        bg: '#fff7ed',
      },
      {
        icon: 'monitoring',
        label: this.t('DASHBOARD.TILE.LAB_OPS_DASHBOARD'),
        route: '/lab-ops-dashboard',
        color: '#0284c7',
        bg: '#e0f2fe',
      },
      {
        icon: 'badge',
        label: this.t('DASHBOARD.TILE.LAB_STAFF'),
        route: '/lab-staff',
        color: '#7c3aed',
        bg: '#ede9fe',
      },
      {
        icon: 'tune',
        label: this.t('DASHBOARD.TILE.TEST_CONFIG'),
        route: '/lab-test-config',
        color: '#0369a1',
        bg: '#e0f2fe',
      },
      {
        icon: 'precision_manufacturing',
        label: this.t('DASHBOARD.TILE.INSTRUMENTS'),
        route: '/lab-instruments',
        color: '#0284c7',
        bg: '#e0f2fe',
      },
      {
        icon: 'inventory_2',
        label: this.t('DASHBOARD.TILE.INVENTORY'),
        route: '/lab-inventory',
        color: '#0891b2',
        bg: '#cffafe',
      },
    ];
  });

  // ── Quality Manager stat cards ────────────────────────────────
  qualityManagerStatCards = computed<StatCard[]>(() => {
    this.langTick();
    const d = this.qualityManagerDashboard();
    if (!d) return [];
    const passRateDisplay: string =
      d.qualityPassRate != null ? d.qualityPassRate.toFixed(1) + '%' : 'N/A';
    return [
      {
        key: 'pending_qa',
        label: this.t('DASHBOARD.PENDING_QA_REVIEW'),
        value: d.pendingQaReview,
        icon: 'fact_check',
        color: d.pendingQaReview > 0 ? '#dc2626' : '#64748b',
        bgColor: d.pendingQaReview > 0 ? '#fee2e2' : '#f1f5f9',
        route: '/lab',
      },
      {
        key: 'total_studies',
        label: this.t('DASHBOARD.VALIDATION_STUDIES'),
        value: d.totalValidationStudies,
        icon: 'biotech',
        color: '#7c3aed',
        bgColor: '#ede9fe',
        route: '/lab',
      },
      {
        key: 'pass_rate',
        label: this.t('DASHBOARD.QUALITY_PASS_RATE'),
        value: passRateDisplay,
        icon: 'verified',
        color: '#059669',
        bgColor: '#d1fae5',
      },
      {
        key: 'active_definitions',
        label: this.t('DASHBOARD.ACTIVE_TEST_DEFINITIONS'),
        value: d.activeDefinitions,
        icon: 'science',
        color: '#2563eb',
        bgColor: '#dbeafe',
        route: '/lab',
      },
      {
        key: 'studies_30d',
        label: this.t('DASHBOARD.VALIDATION_STUDIES_30D'),
        value: d.validationStudiesLast30Days,
        icon: 'calendar_month',
        color: '#0891b2',
        bgColor: '#cffafe',
        route: '/lab',
      },
    ];
  });

  // ── Quality Manager workflow tiles ────────────────────────────
  qualityManagerNavTiles = computed<NavTile[]>(() => {
    this.langTick();
    const d = this.qualityManagerDashboard();
    return [
      {
        icon: 'fact_check',
        label: this.t('DASHBOARD.TILE.QA_REVIEWS'),
        route: '/lab',
        color: '#dc2626',
        bg: '#fee2e2',
        count: d?.pendingQaReview,
      },
      {
        icon: 'biotech',
        label: this.t('DASHBOARD.VALIDATION_STUDIES'),
        route: '/lab-qc-dashboard',
        color: '#7c3aed',
        bg: '#ede9fe',
      },
      {
        icon: 'science',
        label: this.t('DASHBOARD.TILE.TEST_DEFINITIONS'),
        route: '/lab',
        color: '#0d9488',
        bg: '#ccfbf1',
      },
      {
        icon: 'verified',
        label: this.t('DASHBOARD.TILE.APPROVED_TESTS'),
        route: '/lab',
        color: '#059669',
        bg: '#d1fae5',
      },
      {
        icon: 'analytics',
        label: this.t('DASHBOARD.TILE.QUALITY_REPORTS'),
        route: '/lab-qc-dashboard',
        color: '#2563eb',
        bg: '#dbeafe',
      },
      {
        icon: 'warning',
        label: this.t('DASHBOARD.TILE.FAILED_STUDIES'),
        route: '/lab',
        color: '#d97706',
        bg: '#fef3c7',
        count: d?.failedValidationStudies,
      },
      {
        icon: 'history',
        label: this.t('DASHBOARD.TILE.AUDIT_TRAIL'),
        route: '/audit-logs',
        color: '#4f46e5',
        bg: '#eef2ff',
      },
      {
        icon: 'pending_actions',
        label: this.t('DASHBOARD.TILE.DIRECTOR_PENDING'),
        route: '/lab',
        color: '#ea580c',
        bg: '#fff7ed',
        count: d?.pendingDirectorApproval,
      },
      {
        icon: 'monitoring',
        label: this.t('DASHBOARD.TILE.LAB_OPS_DASHBOARD'),
        route: '/lab-ops-dashboard',
        color: '#0284c7',
        bg: '#e0f2fe',
      },
    ];
  });

  // ── Pharmacist workflow tiles ────────────────────────────────
  pharmacistWorkflowTiles = computed<NavTile[]>(() => {
    this.langTick();
    return [
      {
        icon: 'local_pharmacy',
        label: this.t('DASHBOARD.PRESCRIPTIONS'),
        route: '/prescriptions',
        color: '#9333ea',
        bg: '#f3e8ff',
      },
      {
        icon: 'medication_liquid',
        label: this.t('DASHBOARD.TILE.DISPENSE'),
        route: '/prescriptions',
        color: '#059669',
        bg: '#d1fae5',
      },
      {
        icon: 'loop',
        label: this.t('DASHBOARD.TILE.REFILLS'),
        route: '/prescriptions',
        color: '#2563eb',
        bg: '#dbeafe',
      },
      {
        icon: 'people',
        label: this.t('PATIENTS.TITLE'),
        route: '/patients',
        color: '#0891b2',
        bg: '#cffafe',
      },
      {
        icon: 'clinical_notes',
        label: this.t('DASHBOARD.TILE.ENCOUNTERS'),
        route: '/encounters',
        color: '#d97706',
        bg: '#fef3c7',
      },
      {
        icon: 'warning',
        label: this.t('DASHBOARD.TILE.INTERACTIONS'),
        route: '/prescriptions',
        color: '#dc2626',
        bg: '#fee2e2',
      },
      {
        icon: 'inventory_2',
        label: this.t('DASHBOARD.TILE.INVENTORY'),
        route: '/prescriptions',
        color: '#4f46e5',
        bg: '#eef2ff',
      },
      {
        icon: 'analytics',
        label: this.t('DASHBOARD.TILE.REPORTS'),
        route: '/prescriptions',
        color: '#ea580c',
        bg: '#fff7ed',
      },
    ];
  });

  // ── Radiologist workflow tiles ───────────────────────────────
  radiologistWorkflowTiles = computed<NavTile[]>(() => {
    this.langTick();
    return [
      {
        icon: 'radiology',
        label: this.t('DASHBOARD.TILE.IMAGING_STUDIES'),
        route: '/imaging',
        color: '#2563eb',
        bg: '#dbeafe',
      },
      {
        icon: 'pending_actions',
        label: this.t('DASHBOARD.TILE.PENDING_REPORTS'),
        route: '/imaging',
        color: '#d97706',
        bg: '#fef3c7',
      },
      {
        icon: 'task_alt',
        label: this.t('COMMON.COMPLETED'),
        route: '/imaging',
        color: '#059669',
        bg: '#d1fae5',
      },
      {
        icon: 'people',
        label: this.t('PATIENTS.TITLE'),
        route: '/patients',
        color: '#0891b2',
        bg: '#cffafe',
      },
      {
        icon: 'clinical_notes',
        label: this.t('DASHBOARD.TILE.ENCOUNTERS'),
        route: '/encounters',
        color: '#7c3aed',
        bg: '#ede9fe',
      },
      {
        icon: 'priority_high',
        label: this.t('DASHBOARD.TILE.STAT_ORDERS'),
        route: '/imaging',
        color: '#dc2626',
        bg: '#fee2e2',
      },
      {
        icon: 'description',
        label: this.t('DASHBOARD.TILE.TEMPLATES'),
        route: '/imaging',
        color: '#4f46e5',
        bg: '#eef2ff',
      },
      {
        icon: 'analytics',
        label: this.t('DASHBOARD.TILE.REPORTS'),
        route: '/imaging',
        color: '#ea580c',
        bg: '#fff7ed',
      },
    ];
  });

  // ── Patient quick-access tiles (Epic MyChart style) ──────────
  patientQuickLinks = computed<NavTile[]>(() => {
    this.langTick();
    return [
      {
        icon: 'calendar_month',
        label: this.t('DASHBOARD.TILE.APPOINTMENTS'),
        route: '/my-appointments',
        color: '#059669',
        bg: '#d1fae5',
      },
      {
        icon: 'chat',
        label: this.t('DASHBOARD.MESSAGES'),
        route: '/chat',
        color: '#2563eb',
        bg: '#dbeafe',
      },
      {
        icon: 'history',
        label: this.t('DASHBOARD.TILE.VISITS'),
        route: '/my-visits',
        color: '#4f46e5',
        bg: '#eef2ff',
      },
      {
        icon: 'science',
        label: this.t('DASHBOARD.RECENT_TEST_RESULTS'),
        route: '/my-lab-results',
        color: '#7c3aed',
        bg: '#ede9fe',
      },
      {
        icon: 'medication',
        label: this.t('DASHBOARD.TILE.MEDICATIONS'),
        route: '/my-medications',
        color: '#0d9488',
        bg: '#ccfbf1',
      },
      {
        icon: 'receipt_long',
        label: this.t('DASHBOARD.BILLING'),
        route: '/my-billing',
        color: '#d97706',
        bg: '#fef3c7',
      },
      {
        icon: 'groups',
        label: this.t('DASHBOARD.MY_CARE_TEAM'),
        route: '/my-care-team',
        color: '#ec4899',
        bg: '#fce7f3',
      },
      {
        icon: 'monitor_heart',
        label: this.t('DASHBOARD.TILE.VITALS'),
        route: '/my-vitals',
        color: '#dc2626',
        bg: '#fee2e2',
      },
    ];
  });

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

    // Re-localize labels and time-of-day strings whenever the user changes language.
    this.langSub = this.translate.onLangChange.subscribe(() => {
      this.langTick.update((v) => v + 1);
      this.initGreeting();
      this.initProfile();
      this.todayLabel.set(this.formatTodayLabel(this.translate.currentLang));
      this.currentTime.set(this.formatTime(new Date()));
    });

    this.clockInterval = setInterval(() => {
      this.currentTime.set(this.formatTime(new Date()));
      this.initGreeting();
    }, 60_000);
  }

  ngOnDestroy(): void {
    if (this.clockInterval) clearInterval(this.clockInterval);
    this.langSub?.unsubscribe();
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
    if (h < 12) this.greeting.set(this.t('DASHBOARD.GREETING_MORNING'));
    else if (h < 17) this.greeting.set(this.t('DASHBOARD.GREETING_AFTERNOON'));
    else this.greeting.set(this.t('DASHBOARD.GREETING_EVENING'));
  }

  /** Translate helper that falls back to the key when no translation is registered. */
  private t(key: string): string {
    const value = this.translate.instant(key);
    return value === key ? key : value;
  }

  /** Locale-aware version of the long-form date shown beneath the hero greeting. */
  private formatTodayLabel(lang: string | undefined): string {
    const localeTag = this.toBrowserLocaleTag(lang);
    return new Date().toLocaleDateString(localeTag, {
      weekday: 'long',
      year: 'numeric',
      month: 'long',
      day: 'numeric',
    });
  }

  /** Maps the i18n key (`fr`, `en`, `es`) to a BCP-47 locale tag for the browser APIs. */
  private toBrowserLocaleTag(lang: string | undefined): string {
    switch ((lang ?? '').toLowerCase()) {
      case 'fr':
        return 'fr-FR';
      case 'es':
        return 'es-ES';
      default:
        return 'en-US';
    }
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
    this.isLabScientist.set(this.auth.hasAnyRole(['ROLE_LAB_SCIENTIST']));
    this.isLabDirector.set(this.auth.hasAnyRole(['ROLE_LAB_DIRECTOR']));
    this.isQualityManager.set(this.auth.hasAnyRole(['ROLE_QUALITY_MANAGER']));
    this.isPharmacist.set(this.auth.hasAnyRole(['ROLE_PHARMACIST']));
    this.isRadiologist.set(this.auth.hasAnyRole(['ROLE_RADIOLOGIST']));
    this.isPatient.set(this.auth.hasAnyRole(['ROLE_PATIENT']));

    // Title prefix
    if (this.auth.hasAnyRole(['ROLE_DOCTOR', 'ROLE_PHYSICIAN', 'ROLE_SURGEON'])) {
      this.userTitle.set(this.t('DASHBOARD.TITLE_DOCTOR'));
    } else if (this.auth.hasAnyRole(['ROLE_MIDWIFE'])) {
      this.userTitle.set(this.t('DASHBOARD.TITLE_MIDWIFE'));
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

    // Lab Director summary
    if (this.isLabDirector()) {
      pending++;
      this.dashboardService.getLabDirectorSummary().subscribe({
        next: (d) => {
          this.labDirectorDashboard.set(d);
          done();
        },
        error: () => done(),
      });
    }

    // Quality Manager summary
    if (this.isQualityManager()) {
      pending++;
      this.dashboardService.getQualityManagerSummary().subscribe({
        next: (d) => {
          this.qualityManagerDashboard.set(d);
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
    if (item.category === 'REFILL_REQUEST') {
      this.router.navigate(['/refills']);
      return;
    }
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

  onStartEncounter(encounterId: string): void {
    this.encounterService.startEncounter(encounterId).subscribe({
      next: () => {
        this.router.navigate(['/encounters', encounterId]);
      },
      error: (err) => {
        const msg = err?.error?.message || 'Failed to start encounter';
        this.toast.error(msg);
      },
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
    // Read langTick so the template re-evaluates when the user switches language;
    // status values come from the API so they are not signals themselves.
    this.langTick();
    const map: Record<string, string> = {
      SCHEDULED: 'APPOINTMENTS.SCHEDULED',
      CONFIRMED: 'APPOINTMENTS.CONFIRMED',
      COMPLETED: 'DASHBOARD.DONE',
      CANCELLED: 'APPOINTMENTS.CANCELLED',
      NO_SHOW: 'APPOINTMENTS.NO_SHOW',
      IN_PROGRESS: 'DASHBOARD.IN_PROGRESS',
      REQUESTED: 'APPOINTMENTS.REQUESTED',
    };
    const key = map[status];
    return key ? this.t(key) : status;
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

  /** Format an ISO/HH:mm time string for display in the active locale. */
  formatApptTime(time: string): string {
    if (!time) return '';
    const [h, m] = time.split(':').map(Number);
    const sample = new Date();
    sample.setHours(h ?? 0, m ?? 0, 0, 0);
    return sample.toLocaleTimeString(this.toBrowserLocaleTag(this.translate.currentLang), {
      hour: 'numeric',
      minute: '2-digit',
    });
  }

  private formatTime(d: Date): string {
    return d.toLocaleTimeString(this.toBrowserLocaleTag(this.translate.currentLang), {
      hour: 'numeric',
      minute: '2-digit',
    });
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
