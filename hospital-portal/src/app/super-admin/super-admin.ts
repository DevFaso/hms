import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule, DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
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

export type ActivityKey =
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
  private readonly destroyRef = inject(DestroyRef);

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
    // Single-roundtrip load (F5 from
    // docs/super-admin-cross-tenant-design.md). Replaces the prior
    // streaming-load of 8 individual recent-* subscriptions with one
    // call to the aggregate `/super-admin/recent-activity` endpoint.
    //
    // Trade-offs vs. previous streaming load:
    //   - 1 round-trip instead of 8 → ~7× fewer HTTP / TLS / parse
    //     overheads, and a single backend transaction sees a
    //     consistent snapshot across all 9 feeds (no torn state where
    //     a counter shows N but the recent tab shows N-1 because a
    //     delete landed mid-fan-out).
    //   - Activity tabs populate together rather than progressively.
    //     With the new transactional bundle this is intentional: a
    //     consistent set of 9 feeds is a better dashboard experience
    //     than slivers arriving out-of-order.
    //   - The summary + platform observables still run in parallel
    //     (independent of the activity bundle); the header still
    //     flips `loading=false` as soon as `summary` returns so the
    //     counter cards render immediately.
    //   - `errored` semantics unchanged: only when BOTH summary AND
    //     platform fail.
    this.loading.set(true);
    this.errored.set(false);
    const summaryFailed = signal(false);
    const platformFailed = signal(false);

    // takeUntilDestroyed wired on every subscription so navigating away
    // from the super-admin route during an in-flight request tears down
    // the subscription instead of leaking it into the global Subscription
    // tree. Mirrors the pattern used in HospitalScopeChipComponent /
    // HospitalTypeaheadComponent. (Copilot review 2026-05-06.)
    this.dashboard
      .getSummary(10)
      .pipe(
        catchError(() => of(null)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((res) => {
        if (res === null) {
          summaryFailed.set(true);
        }
        this.summary.set(res);
        this.errored.set(summaryFailed() && platformFailed());
        // Header is the gating element for "is the page useful yet?";
        // flip loading off as soon as it arrives so the user sees the
        // counters without waiting for clinical-activity round-trips.
        this.loading.set(false);
      });

    this.platform
      .getSummary()
      .pipe(
        catchError(() => of(null)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((res) => {
        if (res === null) {
          platformFailed.set(true);
        }
        this.platformSummary.set(res);
        this.errored.set(summaryFailed() && platformFailed());
      });

    // Aggregate recent-activity bundle — one call, 9 feeds. The service
    // already swallows transport errors into an empty bundle so we
    // don't need a per-tab catchError here.
    this.dashboard
      .getRecentActivity(10)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((bundle) => {
        this.recent.set({
          consultations: bundle.consultations ?? [],
          labOrders: bundle.labOrders ?? [],
          labResults: bundle.labResults ?? [],
          labTestDefinitions: bundle.labTestDefinitions ?? [],
          admissions: bundle.admissions ?? [],
          prescriptions: bundle.prescriptions ?? [],
          treatmentPlans: bundle.treatmentPlans ?? [],
          referrals: bundle.referrals ?? [],
          // bundle.encounters is fetched too; not surfaced as an activity
          // tab today (that's a separate frontend follow-up — the tab
          // map currently has 8 keys, encounters is on the cards row).
        });
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
    // Activity-row timestamp extractor.
    //
    // Order matches "most clinically meaningful first":
    //   - clinical event time (when the thing happened to the patient)
    //   - workflow event time (request / submission / order)
    //   - row-creation time (database insert) as last-resort fallback
    //
    // Without the clinical-time entries (`encounterDate`,
    // `admissionDateTime`, `resultDate`, `orderDatetime`,
    // `submittedAt`, `timelineStartDate`, `consentTimestamp`) the
    // recent-activity panel would render a blank timestamp for every
    // row of those entity types — which is exactly the Copilot
    // finding on commit 2678681f.
    //
    // The corresponding backend sort fields live in
    // SuperAdminDashboardServiceImpl.getRecent*; both sides must stay
    // in lock-step or the column shows out-of-order rows with blank
    // timestamps.
    const timestamp =
      (item['encounterDate'] as string | undefined) ??
      (item['admissionDateTime'] as string | undefined) ??
      (item['resultDate'] as string | undefined) ??
      (item['orderDatetime'] as string | undefined) ??
      (item['submittedAt'] as string | undefined) ??
      (item['timelineStartDate'] as string | undefined) ??
      (item['consentTimestamp'] as string | undefined) ??
      (item['requestedAt'] as string | undefined) ??
      (item['admissionDate'] as string | undefined) ??
      (item['orderedAt'] as string | undefined) ??
      (item['createdAt'] as string | undefined) ??
      null;
    return {
      id,
      summary: stringFields.filter((s) => s && s.length > 0).join(' · '),
      timestamp,
    };
  }
}
