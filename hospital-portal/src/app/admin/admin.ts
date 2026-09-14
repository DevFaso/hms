import { Component, inject, OnInit, signal, ChangeDetectionStrategy } from '@angular/core';

import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { AuthService } from '../auth/auth.service';
import { DashboardService } from '../services/dashboard.service';

interface SystemStat {
  /** i18n keys, not text — see ADMIN.STAT.*. */
  labelKey: string;
  value: string | number;
  /** Set when the value is a word rather than a number. */
  valueKey?: string;
  icon: string;
  color: string;
}

interface AdminSection {
  title: string;
  description: string;
  icon: string;
  route: string;
  color: string;
}

@Component({
  selector: 'app-admin',
  standalone: true,
  imports: [RouterLink, TranslateModule],
  templateUrl: './admin.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './admin.scss',
})
export class AdminComponent implements OnInit {
  private readonly dashboardService = inject(DashboardService);
  private readonly auth = inject(AuthService);

  loading = signal(true);

  stats = signal<SystemStat[]>([
    { labelKey: 'ADMIN.STAT.TOTAL_USERS', value: '—', icon: 'group', color: '#23b79c' },
    { labelKey: 'ADMIN.STAT.ACTIVE_STAFF', value: '—', icon: 'badge', color: '#10b981' },
    { labelKey: 'ADMIN.STAT.DEPARTMENTS', value: '—', icon: 'domain', color: '#8b5cf6' },
    {
      labelKey: 'ADMIN.STAT.SYSTEM_HEALTH',
      value: '',
      valueKey: 'ADMIN.STAT.ONLINE',
      icon: 'monitor_heart',
      color: '#059669',
    },
  ]);

  // Role audit decision C1: only ROLE_ADMIN can reach this page (super
  // admins are redirected to the Control Tower), so every tile must be a
  // surface whose route guard AND backend admit ADMIN. The old set sent
  // admins into /staff, /departments, /billing and /lab — all-403 pages —
  // and the Audit Logs tile pointed back at /admin itself.
  sections: AdminSection[] = [
    {
      title: 'User Management',
      description: 'Manage user accounts, roles, and permissions',
      icon: 'manage_accounts',
      route: '/users',
      color: '#23b79c',
    },
    {
      title: 'Patient Tracker',
      description: 'Live patient flow across the hospital',
      icon: 'view_kanban',
      route: '/patient-tracker',
      color: '#8b5cf6',
    },
    {
      title: 'Front Desk',
      description: 'Reception cockpit: check-in, queues, and recalls',
      icon: 'support_agent',
      route: '/reception',
      color: '#10b981',
    },
    {
      title: 'System Notifications',
      description: 'Configure notification templates and delivery channels',
      icon: 'notifications_active',
      route: '/notifications',
      color: '#ef4444',
    },
    {
      title: 'Audit Logs',
      description: 'View system audit trail and access logs',
      icon: 'shield',
      route: '/audit-logs',
      color: '#64748b',
    },
  ];

  ngOnInit(): void {
    this.loadStats();
  }

  loadStats(): void {
    this.loading.set(true);
    // Role audit decision C1: this page is reachable ONLY by ROLE_ADMIN
    // (super admins get redirected to /super-admin), yet it called the
    // SUPER_ADMIN-only /super-admin/summary — a guaranteed 403 leaving the
    // stat cards at "—" forever. The hospital-admin summary admits ADMIN.
    this.dashboardService.getHospitalAdminSummary().subscribe({
      next: (data) => {
        this.stats.set([
          {
            labelKey: 'ADMIN.STAT.ACTIVE_STAFF',
            value: data.staffing?.activeStaff ?? '—',
            icon: 'badge',
            color: '#10b981',
          },
          {
            labelKey: 'ADMIN.STAT.ON_SHIFT_TODAY',
            value: data.staffing?.onShiftToday ?? '—',
            icon: 'schedule',
            color: '#23b79c',
          },
          {
            labelKey: 'ADMIN.STAT.TODAY_APPOINTMENTS',
            value: data.appointments?.todayTotal ?? '—',
            icon: 'calendar_month',
            color: '#8b5cf6',
          },
          {
            labelKey: 'ADMIN.STAT.SYSTEM_HEALTH',
            value: '',
            valueKey: 'ADMIN.STAT.ONLINE',
            icon: 'monitor_heart',
            color: '#059669',
          },
        ]);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  get userName(): string {
    const profile = this.auth.getUserProfile();
    return profile ? `${profile.firstName} ${profile.lastName}` : 'Admin';
  }
}
