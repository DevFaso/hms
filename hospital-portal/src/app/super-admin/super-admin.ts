import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { forkJoin } from 'rxjs';
import { catchError, of } from 'rxjs';

import {
  DashboardService,
  RecentAuditEvent,
  SuperAdminSummary,
} from '../services/dashboard.service';
import { ActionPanel, PlatformService, PlatformSummary } from '../services/platform.service';

interface ControlTowerStat {
  key: string;
  labelKey: string;
  value: number | string;
  sublabelKey?: string;
  subvalue?: number | string;
  icon: string;
  color: string;
  route: string;
}

interface ControlTowerLink {
  titleKey: string;
  descKey: string;
  icon: string;
  route: string;
  color: string;
}

@Component({
  selector: 'app-super-admin',
  standalone: true,
  imports: [CommonModule, RouterLink, TranslateModule, DatePipe],
  templateUrl: './super-admin.html',
  styleUrl: './super-admin.scss',
})
export class SuperAdminComponent implements OnInit {
  private readonly dashboard = inject(DashboardService);
  private readonly platform = inject(PlatformService);

  readonly loading = signal(true);
  readonly errored = signal(false);

  private readonly summary = signal<SuperAdminSummary | null>(null);
  private readonly platformSummary = signal<PlatformSummary | null>(null);

  readonly stats = computed<ControlTowerStat[]>(() => {
    const s = this.summary();
    if (!s) return [];
    return [
      {
        key: 'organizations',
        labelKey: 'SUPER_ADMIN.STAT.ORGANIZATIONS',
        value: s.totalOrganizations ?? 0,
        sublabelKey: 'SUPER_ADMIN.STAT.ACTIVE',
        subvalue: s.activeOrganizations ?? 0,
        icon: 'corporate_fare',
        color: '#6366f1',
        route: '/organizations',
      },
      {
        key: 'hospitals',
        labelKey: 'SUPER_ADMIN.STAT.HOSPITALS',
        value: s.totalHospitals ?? 0,
        sublabelKey: 'SUPER_ADMIN.STAT.ACTIVE',
        subvalue: s.activeHospitals ?? 0,
        icon: 'local_hospital',
        color: '#0ea5e9',
        route: '/hospitals',
      },
      {
        key: 'users',
        labelKey: 'SUPER_ADMIN.STAT.USERS',
        value: s.totalUsers ?? 0,
        sublabelKey: 'SUPER_ADMIN.STAT.ACTIVE',
        subvalue: s.activeUsers ?? 0,
        icon: 'group',
        color: '#3b82f6',
        route: '/users',
      },
      {
        key: 'patients',
        labelKey: 'SUPER_ADMIN.STAT.PATIENTS',
        value: s.totalPatients ?? 0,
        icon: 'sick',
        color: '#10b981',
        route: '/patients',
      },
      {
        key: 'today_appointments',
        labelKey: 'SUPER_ADMIN.STAT.TODAY_APPOINTMENTS',
        value: s.todayAppointmentsCount ?? 0,
        icon: 'calendar_today',
        color: '#f59e0b',
        route: '/appointments',
      },
      {
        key: 'departments',
        labelKey: 'SUPER_ADMIN.STAT.DEPARTMENTS',
        value: s.totalDepartments ?? 0,
        icon: 'domain',
        color: '#8b5cf6',
        route: '/departments',
      },
      {
        key: 'roles',
        labelKey: 'SUPER_ADMIN.STAT.ROLES',
        value: s.totalRoles ?? 0,
        icon: 'shield',
        color: '#64748b',
        route: '/roles',
      },
      {
        key: 'assignments',
        labelKey: 'SUPER_ADMIN.STAT.ASSIGNMENTS',
        value: s.totalAssignments ?? 0,
        sublabelKey: 'SUPER_ADMIN.STAT.ACTIVE',
        subvalue: s.activeAssignments ?? 0,
        icon: 'assignment_ind',
        color: '#ef4444',
        route: '/users',
      },
    ];
  });

  readonly platformActions = computed<ActionPanel | null>(
    () => this.platformSummary()?.actions ?? null,
  );

  readonly recentAudit = computed<RecentAuditEvent[]>(
    () => this.summary()?.recentAuditEvents ?? [],
  );

  readonly generatedAt = computed<string | null>(() => this.summary()?.generatedAt ?? null);

  readonly quickLinks: ControlTowerLink[] = [
    {
      titleKey: 'SUPER_ADMIN.LINK.ORGANIZATIONS_TITLE',
      descKey: 'SUPER_ADMIN.LINK.ORGANIZATIONS_DESC',
      icon: 'corporate_fare',
      route: '/organizations',
      color: '#6366f1',
    },
    {
      titleKey: 'SUPER_ADMIN.LINK.USERS_TITLE',
      descKey: 'SUPER_ADMIN.LINK.USERS_DESC',
      icon: 'manage_accounts',
      route: '/users',
      color: '#3b82f6',
    },
    {
      titleKey: 'SUPER_ADMIN.LINK.ROLES_TITLE',
      descKey: 'SUPER_ADMIN.LINK.ROLES_DESC',
      icon: 'shield',
      route: '/roles',
      color: '#64748b',
    },
    {
      titleKey: 'SUPER_ADMIN.LINK.FEATURE_FLAGS_TITLE',
      descKey: 'SUPER_ADMIN.LINK.FEATURE_FLAGS_DESC',
      icon: 'flag',
      route: '/feature-flags',
      color: '#f59e0b',
    },
    {
      titleKey: 'SUPER_ADMIN.LINK.ANALYTICS_TITLE',
      descKey: 'SUPER_ADMIN.LINK.ANALYTICS_DESC',
      icon: 'insights',
      route: '/analytics',
      color: '#10b981',
    },
    {
      titleKey: 'SUPER_ADMIN.LINK.PLATFORM_TITLE',
      descKey: 'SUPER_ADMIN.LINK.PLATFORM_DESC',
      icon: 'hub',
      route: '/platform',
      color: '#0ea5e9',
    },
    {
      titleKey: 'SUPER_ADMIN.LINK.AUDIT_TITLE',
      descKey: 'SUPER_ADMIN.LINK.AUDIT_DESC',
      icon: 'policy',
      route: '/audit-logs',
      color: '#8b5cf6',
    },
    {
      titleKey: 'SUPER_ADMIN.LINK.HOSPITALS_TITLE',
      descKey: 'SUPER_ADMIN.LINK.HOSPITALS_DESC',
      icon: 'local_hospital',
      route: '/hospitals',
      color: '#ef4444',
    },
    {
      titleKey: 'SUPER_ADMIN.LINK.INTEGRATIONS_TITLE',
      descKey: 'SUPER_ADMIN.LINK.INTEGRATIONS_DESC',
      icon: 'cable',
      route: '/super-admin/integrations',
      color: '#0d9488',
    },
    {
      titleKey: 'SUPER_ADMIN.LINK.AUDIT_SEARCH_TITLE',
      descKey: 'SUPER_ADMIN.LINK.AUDIT_SEARCH_DESC',
      icon: 'policy',
      route: '/super-admin/audit-search',
      color: '#0284c7',
    },
    {
      titleKey: 'SUPER_ADMIN.LINK.EMERGENCY_TITLE',
      descKey: 'SUPER_ADMIN.LINK.EMERGENCY_DESC',
      icon: 'emergency',
      route: '/super-admin/emergency',
      color: '#dc2626',
    },
    {
      titleKey: 'SUPER_ADMIN.LINK.SUBSCRIPTIONS_TITLE',
      descKey: 'SUPER_ADMIN.LINK.SUBSCRIPTIONS_DESC',
      icon: 'subscriptions',
      route: '/super-admin/subscriptions',
      color: '#16a34a',
    },
  ];

  ngOnInit(): void {
    this.loadAll();
  }

  loadAll(): void {
    this.loading.set(true);
    this.errored.set(false);
    forkJoin({
      summary: this.dashboard.getSummary(10).pipe(catchError(() => of(null))),
      platform: this.platform.getSummary().pipe(catchError(() => of(null))),
    }).subscribe({
      next: ({ summary, platform }) => {
        this.summary.set(summary);
        this.platformSummary.set(platform);
        this.errored.set(summary === null && platform === null);
        this.loading.set(false);
      },
      error: () => {
        this.errored.set(true);
        this.loading.set(false);
      },
    });
  }
}
