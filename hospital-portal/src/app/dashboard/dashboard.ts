import { Component, inject, OnInit, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { PermissionService } from '../core/permission.service';
import { AppointmentService } from '../services/appointment.service';
import { PatientService } from '../services/patient.service';
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
}

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
})
export class DashboardComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly permissions = inject(PermissionService);
  private readonly appointmentService = inject(AppointmentService);
  private readonly patientService = inject(PatientService);
  private readonly dashboardService = inject(DashboardService);

  greeting = signal('');
  userName = signal('');
  today = new Date().toLocaleDateString('en-US', {
    weekday: 'long',
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  });
  loading = signal(true);

  /* Super-admin data */
  adminSummary = signal<SuperAdminSummary | null>(null);
  recentAuditEvents = signal<RecentAuditEvent[]>([]);

  /* Clinical dashboard data */
  kpis = signal<DashboardKPI[]>([]);
  alerts = signal<ClinicalAlert[]>([]);
  inboxCounts = signal<InboxCounts | null>(null);
  onCallStatus = signal<OnCallStatus | null>(null);
  roomedPatients = signal<RoomedPatient[]>([]);

  /* Fallback data */
  todayAppointments = signal<unknown[]>([]);
  recentPatients = signal<unknown[]>([]);

  isSuperAdmin = signal(false);
  isClinician = signal(false);

  quickActions = computed<QuickAction[]>(() => {
    const actions: QuickAction[] = [];
    if (this.permissions.hasPermission('Register Patients')) {
      actions.push({
        icon: 'person_add',
        label: 'Register Patient',
        route: '/patients/new',
        color: '#2563eb',
      });
    }
    if (this.permissions.hasPermission('Create Appointments')) {
      actions.push({
        icon: 'event',
        label: 'New Appointment',
        route: '/appointments/new',
        color: '#059669',
      });
    }
    if (this.permissions.hasPermission('View Lab')) {
      actions.push({ icon: 'science', label: 'Lab Orders', route: '/lab', color: '#7c3aed' });
    }
    if (this.permissions.hasPermission('View Billing')) {
      actions.push({ icon: 'receipt_long', label: 'Billing', route: '/billing', color: '#d97706' });
    }
    return actions;
  });

  ngOnInit(): void {
    const hour = new Date().getHours();
    if (hour < 12) this.greeting.set('Good Morning');
    else if (hour < 17) this.greeting.set('Good Afternoon');
    else this.greeting.set('Good Evening');

    const profile = this.auth.getUserProfile();
    this.userName.set(profile?.firstName ?? profile?.username ?? 'User');

    this.loadDashboardData();
  }

  private loadDashboardData(): void {
    this.loading.set(true);
    let pending = 0;
    const done = () => {
      if (--pending <= 0) this.loading.set(false);
    };

    // Try super-admin summary
    pending++;
    this.dashboardService.getSummary().subscribe({
      next: (summary) => {
        this.adminSummary.set(summary);
        this.recentAuditEvents.set(summary.recentAuditEvents ?? []);
        this.isSuperAdmin.set(true);
        done();
      },
      error: () => done(),
    });

    // Try clinical dashboard
    pending++;
    this.dashboardService.getClinicalDashboard().subscribe({
      next: (dashboard) => {
        this.kpis.set(dashboard.kpis ?? []);
        this.alerts.set(dashboard.alerts ?? []);
        this.inboxCounts.set(dashboard.inboxCounts ?? null);
        this.onCallStatus.set(dashboard.onCallStatus ?? null);
        this.roomedPatients.set(dashboard.roomedPatients ?? []);
        this.isClinician.set(true);
        done();
      },
      error: () => done(),
    });

    // Today's appointments
    if (this.permissions.hasPermission('View Appointments')) {
      pending++;
      const today = new Date().toISOString().split('T')[0];
      this.appointmentService.list({ fromDate: today, toDate: today }).subscribe({
        next: (appts) => {
          this.todayAppointments.set(appts.slice(0, 8));
          done();
        },
        error: () => done(),
      });
    }

    // Recent patients
    if (this.permissions.hasPermission('View Patient Records')) {
      pending++;
      this.patientService.list().subscribe({
        next: (patients) => {
          this.recentPatients.set(patients.slice(0, 5));
          done();
        },
        error: () => done(),
      });
    }
  }

  acknowledgeAlert(alertId: string): void {
    this.dashboardService.acknowledgeAlert(alertId).subscribe({
      next: () => {
        this.alerts.update((list) =>
          list.map((a) => (a.id === alertId ? { ...a, acknowledged: true } : a)),
        );
      },
    });
  }

  getAlertSeverityClass(severity: string): string {
    switch (severity) {
      case 'CRITICAL':
        return 'alert-item severity-critical';
      case 'URGENT':
        return 'alert-item severity-urgent';
      case 'WARNING':
        return 'alert-item severity-warning';
      case 'INFO':
        return 'alert-item severity-info';
      default:
        return 'alert-item';
    }
  }

  getTriageClass(status: string): string {
    switch (status) {
      case 'TRIAGED':
        return 'triage-badge triage-triaged';
      case 'READY_FOR_PROVIDER':
        return 'triage-badge triage-ready';
      case 'TRIAGE_IN_PROGRESS':
        return 'triage-badge triage-progress';
      default:
        return 'triage-badge triage-default';
    }
  }

  getTrendIcon(trend: string): string {
    switch (trend) {
      case 'up':
        return 'trending_up';
      case 'down':
        return 'trending_down';
      default:
        return 'trending_flat';
    }
  }

  getTrendClass(trend: string): string {
    switch (trend) {
      case 'up':
        return 'kpi-trend trend-up';
      case 'down':
        return 'kpi-trend trend-down';
      default:
        return 'kpi-trend trend-stable';
    }
  }
}
