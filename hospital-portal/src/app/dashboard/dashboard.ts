import {
  Component,
  inject,
  OnInit,
  OnDestroy,
  signal,
  computed,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
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
} from '../services/dashboard.service';

interface QuickAction {
  icon: string;
  label: string;
  route: string;
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

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink, DatePipe],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DashboardComponent implements OnInit, OnDestroy {
  private readonly auth = inject(AuthService);
  readonly permissions = inject(PermissionService);
  private readonly appointmentService = inject(AppointmentService);
  private readonly patientService = inject(PatientService);
  private readonly dashboardService = inject(DashboardService);

  // ── Time & Identity ──────────────────────────────────────────
  greeting = signal('');
  userName = signal('');
  userTitle = signal('Dr.');
  currentTime = signal(this.formatTime(new Date()));
  todayLabel = new Date().toLocaleDateString('en-US', {
    weekday: 'long',
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  });
  private clockInterval?: ReturnType<typeof setInterval>;

  // ── Loading States ───────────────────────────────────────────
  loading = signal(true);
  scheduleLoading = signal(true);

  // ── Role flags ───────────────────────────────────────────────
  isSuperAdmin = signal(false);
  isClinician = signal(false);
  isDoctor = signal(false);
  isNurse = signal(false);

  // ── Super-admin data ─────────────────────────────────────────
  adminSummary = signal<SuperAdminSummary | null>(null);
  recentAuditEvents = signal<RecentAuditEvent[]>([]);

  // ── Clinical dashboard data ───────────────────────────────────
  kpis = signal<DashboardKPI[]>([]);
  alerts = signal<ClinicalAlert[]>([]);
  inboxCounts = signal<InboxCounts | null>(null);
  onCallStatus = signal<OnCallStatus | null>(null);
  roomedPatients = signal<RoomedPatient[]>([]);

  // ── Schedule & Patients ───────────────────────────────────────
  todayAppointments = signal<AppointmentResponse[]>([]);
  recentPatients = signal<PatientResponse[]>([]);

  // ── Derived/computed ─────────────────────────────────────────
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

  /** The main stat strip for the doctor hero section */
  statCards = computed<StatCard[]>(() => {
    const cards: StatCard[] = [
      {
        key: 'total_today',
        label: "Today's Patients",
        value: this.todayAppointments().length,
        icon: 'group',
        color: '#2563eb',
        bgColor: '#dbeafe',
        route: '/appointments',
        trend: 'up',
        delta: 2,
      },
      {
        key: 'completed',
        label: 'Completed',
        value: this.completedToday(),
        icon: 'task_alt',
        color: '#059669',
        bgColor: '#d1fae5',
        route: '/appointments',
      },
      {
        key: 'in_progress',
        label: 'In Progress',
        value: this.inProgressNow(),
        icon: 'person_play',
        color: '#d97706',
        bgColor: '#fef3c7',
        route: '/appointments',
      },
      {
        key: 'pending',
        label: 'Pending',
        value: this.pendingToday(),
        icon: 'pending',
        color: '#7c3aed',
        bgColor: '#ede9fe',
        route: '/appointments',
      },
      {
        key: 'alerts',
        label: 'Active Alerts',
        value: this.unacknowledgedAlerts().length,
        icon: 'notification_important',
        color: this.criticalAlerts().length > 0 ? '#dc2626' : '#ea580c',
        bgColor: this.criticalAlerts().length > 0 ? '#fee2e2' : '#ffedd5',
      },
      {
        key: 'inbox',
        label: 'Inbox Items',
        value: this.totalInboxCount(),
        icon: 'mark_email_unread',
        color: '#0891b2',
        bgColor: '#cffafe',
        route: '/chat',
      },
    ];

    // Merge KPI data if available
    const kpiMap = new Map(this.kpis().map((k) => [k.key, k]));
    return cards.map((c) => {
      const kpi = kpiMap.get(c.key);
      if (kpi) {
        return { ...c, value: kpi.value, trend: kpi.trend, delta: kpi.deltaNum };
      }
      return c;
    });
  });

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

  /** Day completion percentage for the progress ring */
  scheduleProgress = computed(() => {
    const total = this.todayAppointments().length;
    if (total === 0) return 0;
    return Math.round((this.completedToday() / total) * 100);
  });

  /** Circumference-based stroke-dashoffset for SVG progress ring */
  progressOffset = computed(() => {
    const radius = 34;
    const circ = 2 * Math.PI * radius;
    return circ - (this.scheduleProgress() / 100) * circ;
  });

  quickActions = computed<QuickAction[]>(() => {
    const actions: QuickAction[] = [];
    if (this.permissions.hasPermission('Register Patients')) {
      actions.push({
        icon: 'person_add',
        label: 'Register Patient',
        description: 'Onboard a new patient',
        route: '/patients/new',
        color: '#2563eb',
        bgColor: '#dbeafe',
      });
    }
    if (this.permissions.hasPermission('Create Appointments')) {
      actions.push({
        icon: 'calendar_add_on',
        label: 'New Appointment',
        description: 'Schedule a visit',
        route: '/appointments/new',
        color: '#059669',
        bgColor: '#d1fae5',
      });
    }
    if (this.permissions.hasPermission('Create Encounters')) {
      actions.push({
        icon: 'stethoscope',
        label: 'Start Encounter',
        description: 'Begin a clinical encounter',
        route: '/encounters/new',
        color: '#7c3aed',
        bgColor: '#ede9fe',
      });
    }
    if (this.permissions.hasPermission('Create Prescriptions')) {
      actions.push({
        icon: 'medication',
        label: 'Write Rx',
        description: 'Prescribe medication',
        route: '/prescriptions/new',
        color: '#0891b2',
        bgColor: '#cffafe',
      });
    }
    if (this.permissions.hasPermission('View Lab')) {
      actions.push({
        icon: 'science',
        label: 'Lab Orders',
        description: 'Order lab tests',
        route: '/lab',
        color: '#dc2626',
        bgColor: '#fee2e2',
      });
    }
    if (this.permissions.hasPermission('Request Imaging Studies')) {
      actions.push({
        icon: 'radiology',
        label: 'Imaging',
        description: 'Request imaging studies',
        route: '/imaging',
        color: '#d97706',
        bgColor: '#fef3c7',
      });
    }
    if (this.permissions.hasPermission('View Billing')) {
      actions.push({
        icon: 'receipt_long',
        label: 'Billing',
        description: 'Manage billing',
        route: '/billing',
        color: '#64748b',
        bgColor: '#f1f5f9',
      });
    }
    return actions;
  });

  // ── Lifecycle ─────────────────────────────────────────────────
  ngOnInit(): void {
    this.initGreeting();
    this.initProfile();
    this.loadDashboardData();

    // Live clock — update every minute
    this.clockInterval = setInterval(() => {
      this.currentTime.set(this.formatTime(new Date()));
      this.initGreeting(); // greeting changes at 12:00 and 17:00
    }, 60_000);
  }

  ngOnDestroy(): void {
    if (this.clockInterval) clearInterval(this.clockInterval);
  }

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
    this.userName.set(first || last ? `${first} ${last}`.trim() : (profile?.username ?? 'Doctor'));

    const roles = profile?.roles ?? [];
    const isDoc =
      this.auth.hasAnyRole(['ROLE_DOCTOR']) ||
      roles.some((r) => r.toLowerCase().includes('doctor'));
    const isMid = this.auth.hasAnyRole(['ROLE_MIDWIFE']);
    if (isDoc) this.userTitle.set('Dr.');
    else if (isMid) this.userTitle.set('Mid.');
    else this.userTitle.set('');

    this.isSuperAdmin.set(this.auth.hasAnyRole(['ROLE_SUPER_ADMIN']));
    this.isDoctor.set(this.auth.hasAnyRole(['ROLE_DOCTOR', 'ROLE_PHYSICIAN', 'ROLE_SURGEON']));
    this.isNurse.set(this.auth.hasAnyRole(['ROLE_NURSE', 'ROLE_MIDWIFE']));
    this.isClinician.set(
      this.auth.hasAnyRole([
        'ROLE_DOCTOR',
        'ROLE_PHYSICIAN',
        'ROLE_SURGEON',
        'ROLE_NURSE',
        'ROLE_MIDWIFE',
      ]),
    );
  }

  private loadDashboardData(): void {
    this.loading.set(true);
    this.scheduleLoading.set(true);
    let pending = 0;
    const done = () => {
      if (--pending <= 0) this.loading.set(false);
    };

    if (this.auth.hasAnyRole(['ROLE_SUPER_ADMIN'])) {
      pending++;
      this.dashboardService.getSummary().subscribe({
        next: (s) => {
          this.adminSummary.set(s);
          this.recentAuditEvents.set(s.recentAuditEvents ?? []);
          done();
        },
        error: () => done(),
      });
    }

    const clinicalRoles = [
      'ROLE_DOCTOR',
      'ROLE_PHYSICIAN',
      'ROLE_SURGEON',
      'ROLE_NURSE',
      'ROLE_MIDWIFE',
    ];
    if (this.auth.hasAnyRole(clinicalRoles)) {
      pending++;
      this.dashboardService.getClinicalDashboard().subscribe({
        next: (d) => {
          this.kpis.set(d.kpis ?? []);
          this.alerts.set(d.alerts ?? []);
          this.inboxCounts.set(d.inboxCounts ?? null);
          this.onCallStatus.set(d.onCallStatus ?? null);
          this.roomedPatients.set(d.roomedPatients ?? []);
          done();
        },
        error: () => done(),
      });
    }

    // Always load today's appointments for clinical users
    if (this.permissions.hasPermission('View Appointments')) {
      pending++;
      const today = new Date().toISOString().split('T')[0];
      this.appointmentService.list({ fromDate: today, toDate: today }).subscribe({
        next: (appts) => {
          // Sort by start time ascending
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

    if (this.permissions.hasPermission('View Patient Records')) {
      pending++;
      this.patientService.list().subscribe({
        next: (patients) => {
          this.recentPatients.set(patients.slice(0, 6));
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

  // ── Actions ───────────────────────────────────────────────────
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

  // ── Style helpers ─────────────────────────────────────────────
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
    const parts = name?.trim().split(' ') ?? [];
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

  private formatTime(d: Date): string {
    return d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', hour12: true });
  }
}
