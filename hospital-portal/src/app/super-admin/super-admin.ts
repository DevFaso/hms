import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { Observable, forkJoin } from 'rxjs';
import { catchError, of } from 'rxjs';

import {
  DashboardService,
  RecentAuditEvent,
  SuperAdminRecentItem,
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

type ActivityKey =
  | 'consultations'
  | 'labOrders'
  | 'labResults'
  | 'labTestDefinitions'
  | 'admissions'
  | 'prescriptions'
  | 'treatmentPlans'
  | 'referrals';

interface ActivityTab {
  key: ActivityKey;
  labelKey: string;
  icon: string;
  countOf: (s: SuperAdminSummary) => number;
}

interface ActivityRow {
  id: string;
  summary: string;
  timestamp: string | null;
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

  private readonly emptyActivity: Record<ActivityKey, SuperAdminRecentItem[]> = {
    consultations: [],
    labOrders: [],
    labResults: [],
    labTestDefinitions: [],
    admissions: [],
    prescriptions: [],
    treatmentPlans: [],
    referrals: [],
  };

  private readonly recent = signal<Record<ActivityKey, SuperAdminRecentItem[]>>(this.emptyActivity);
  readonly selectedActivityTab = signal<ActivityKey>('consultations');

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

  readonly clinicalStats = computed<ControlTowerStat[]>(() => {
    const s = this.summary();
    if (!s) return [];
    return [
      {
        key: 'encounters',
        labelKey: 'SUPER_ADMIN.STAT.ENCOUNTERS',
        value: s.totalEncounters ?? 0,
        icon: 'event_note',
        color: '#0ea5e9',
        route: '/encounters',
      },
      {
        key: 'consultations',
        labelKey: 'SUPER_ADMIN.STAT.CONSULTATIONS',
        value: s.totalConsultations ?? 0,
        icon: 'forum',
        color: '#6366f1',
        route: '/consultations',
      },
      {
        key: 'admissions',
        labelKey: 'SUPER_ADMIN.STAT.ADMISSIONS',
        value: s.totalAdmissions ?? 0,
        icon: 'hotel',
        color: '#8b5cf6',
        route: '/admissions',
      },
      {
        key: 'prescriptions',
        labelKey: 'SUPER_ADMIN.STAT.PRESCRIPTIONS',
        value: s.totalPrescriptions ?? 0,
        icon: 'medication',
        color: '#10b981',
        route: '/prescriptions',
      },
      {
        key: 'lab_orders',
        labelKey: 'SUPER_ADMIN.STAT.LAB_ORDERS',
        value: s.totalLabOrders ?? 0,
        icon: 'biotech',
        color: '#f59e0b',
        route: '/lab',
      },
      {
        key: 'lab_results',
        labelKey: 'SUPER_ADMIN.STAT.LAB_RESULTS',
        value: s.totalLabResults ?? 0,
        icon: 'science',
        color: '#0d9488',
        route: '/lab-results',
      },
      {
        key: 'lab_test_definitions',
        labelKey: 'SUPER_ADMIN.STAT.LAB_TEST_DEFINITIONS',
        value: s.totalLabTestDefinitions ?? 0,
        icon: 'list_alt',
        color: '#64748b',
        route: '/lab-test-config',
      },
      {
        key: 'treatment_plans',
        labelKey: 'SUPER_ADMIN.STAT.TREATMENT_PLANS',
        value: s.totalTreatmentPlans ?? 0,
        icon: 'medical_services',
        color: '#ef4444',
        route: '/treatment-plans',
      },
      {
        key: 'referrals',
        labelKey: 'SUPER_ADMIN.STAT.REFERRALS',
        value: s.totalReferrals ?? 0,
        icon: 'send',
        color: '#dc2626',
        route: '/referrals',
      },
    ];
  });

  readonly activityTabs: ActivityTab[] = [
    {
      key: 'consultations',
      labelKey: 'SUPER_ADMIN.STAT.CONSULTATIONS',
      icon: 'forum',
      countOf: (s) => s.totalConsultations ?? 0,
    },
    {
      key: 'admissions',
      labelKey: 'SUPER_ADMIN.STAT.ADMISSIONS',
      icon: 'hotel',
      countOf: (s) => s.totalAdmissions ?? 0,
    },
    {
      key: 'prescriptions',
      labelKey: 'SUPER_ADMIN.STAT.PRESCRIPTIONS',
      icon: 'medication',
      countOf: (s) => s.totalPrescriptions ?? 0,
    },
    {
      key: 'labOrders',
      labelKey: 'SUPER_ADMIN.STAT.LAB_ORDERS',
      icon: 'biotech',
      countOf: (s) => s.totalLabOrders ?? 0,
    },
    {
      key: 'labResults',
      labelKey: 'SUPER_ADMIN.STAT.LAB_RESULTS',
      icon: 'science',
      countOf: (s) => s.totalLabResults ?? 0,
    },
    {
      key: 'labTestDefinitions',
      labelKey: 'SUPER_ADMIN.STAT.LAB_TEST_DEFINITIONS',
      icon: 'list_alt',
      countOf: (s) => s.totalLabTestDefinitions ?? 0,
    },
    {
      key: 'treatmentPlans',
      labelKey: 'SUPER_ADMIN.STAT.TREATMENT_PLANS',
      icon: 'medical_services',
      countOf: (s) => s.totalTreatmentPlans ?? 0,
    },
    {
      key: 'referrals',
      labelKey: 'SUPER_ADMIN.STAT.REFERRALS',
      icon: 'send',
      countOf: (s) => s.totalReferrals ?? 0,
    },
  ];

  readonly selectedActivityRows = computed<ActivityRow[]>(() => {
    const items = this.recent()[this.selectedActivityTab()] ?? [];
    return items.map((item) => this.toActivityRow(item));
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
      route: '/super-admin/feature-flags',
      color: '#f59e0b',
    },
    {
      titleKey: 'SUPER_ADMIN.LINK.ANALYTICS_TITLE',
      descKey: 'SUPER_ADMIN.LINK.ANALYTICS_DESC',
      icon: 'insights',
      route: '/super-admin/analytics',
      color: '#10b981',
    },
    {
      titleKey: 'SUPER_ADMIN.LINK.PLATFORM_TITLE',
      descKey: 'SUPER_ADMIN.LINK.PLATFORM_DESC',
      icon: 'hub',
      route: '/super-admin/platform',
      color: '#0ea5e9',
    },
    {
      titleKey: 'SUPER_ADMIN.LINK.AUDIT_TITLE',
      descKey: 'SUPER_ADMIN.LINK.AUDIT_DESC',
      icon: 'policy',
      route: '/super-admin/audit-logs',
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
    {
      titleKey: 'SUPER_ADMIN.LINK.REGIONS_TITLE',
      descKey: 'SUPER_ADMIN.LINK.REGIONS_DESC',
      icon: 'public',
      route: '/super-admin/data-residency',
      color: '#0d9488',
    },
  ];

  ngOnInit(): void {
    this.loadAll();
  }

  loadAll(): void {
    this.loading.set(true);
    this.errored.set(false);
    const empty = (): Observable<SuperAdminRecentItem[]> => of([]);
    forkJoin({
      summary: this.dashboard.getSummary(10).pipe(catchError(() => of(null))),
      platform: this.platform.getSummary().pipe(catchError(() => of(null))),
      consultations: this.dashboard.getRecentConsultations(10).pipe(catchError(empty)),
      labOrders: this.dashboard.getRecentLabOrders(10).pipe(catchError(empty)),
      labResults: this.dashboard.getRecentLabResults(10).pipe(catchError(empty)),
      labTestDefinitions: this.dashboard.getRecentLabTestDefinitions(10).pipe(catchError(empty)),
      admissions: this.dashboard.getRecentAdmissions(10).pipe(catchError(empty)),
      prescriptions: this.dashboard.getRecentPrescriptions(10).pipe(catchError(empty)),
      treatmentPlans: this.dashboard.getRecentTreatmentPlans(10).pipe(catchError(empty)),
      referrals: this.dashboard.getRecentReferrals(10).pipe(catchError(empty)),
    }).subscribe({
      next: (res) => {
        this.summary.set(res.summary);
        this.platformSummary.set(res.platform);
        this.recent.set({
          consultations: res.consultations,
          labOrders: res.labOrders,
          labResults: res.labResults,
          labTestDefinitions: res.labTestDefinitions,
          admissions: res.admissions,
          prescriptions: res.prescriptions,
          treatmentPlans: res.treatmentPlans,
          referrals: res.referrals,
        });
        this.errored.set(res.summary === null && res.platform === null);
        this.loading.set(false);
      },
      error: () => {
        this.errored.set(true);
        this.loading.set(false);
      },
    });
  }

  selectActivityTab(key: ActivityKey): void {
    this.selectedActivityTab.set(key);
  }

  countForTab(tab: ActivityTab): number {
    const s = this.summary();
    return s ? tab.countOf(s) : 0;
  }

  private toActivityRow(item: SuperAdminRecentItem): ActivityRow {
    const idValue = typeof item['id'] === 'string' ? (item['id'] as string) : '';
    const id = idValue ? idValue.slice(0, 8) : '';
    const stringFields = Object.entries(item)
      .filter(
        ([k, v]) =>
          typeof v === 'string' &&
          k !== 'id' &&
          !k.toLowerCase().endsWith('id') &&
          !k.toLowerCase().endsWith('at') &&
          !k.toLowerCase().endsWith('date'),
      )
      .slice(0, 2)
      .map(([, v]) => v as string);
    const timestamp =
      (item['createdAt'] as string | undefined) ??
      (item['requestedAt'] as string | undefined) ??
      (item['admissionDate'] as string | undefined) ??
      (item['orderedAt'] as string | undefined) ??
      null;
    return {
      id,
      summary: stringFields.filter((s) => s && s.length > 0).join(' · '),
      timestamp,
    };
  }
}
