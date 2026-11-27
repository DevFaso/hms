import {
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { AuditSearchService } from '../services/audit-search.service';
import { AuditSearchFilter, AuditSearchPage, AuditSearchRow } from '../services/audit-search.model';
import { RoleContextService } from '../core/role-context.service';
import { RoleLabelPipe } from '../shared/pipes/role-label.pipe';
import { auditTargetKey, recordedAuditValue } from './audit-presentation';
import { CommonModule, DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { catchError, of } from 'rxjs';

import {
  DashboardService,
  SuperAdminRecentItem,
  SuperAdminSummary,
} from '../services/dashboard.service';
import { IntegrationHealthService } from '../services/integration-health.service';
import { IntegrationHealthSummary } from '../services/integration-health.model';
import { ActionPanel, PlatformService, PlatformSummary } from '../services/platform.service';
import { DashPipe } from '../shared/pipes/dash.pipe';
import { KeyLabelPipe } from '../shared/pipes/key-label.pipe';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { PluralPipe } from '../shared/i18n/plural';
import { formatLabNumber } from '../lab/shared/lab-value.pipe';

interface ControlTowerStat {
  key: string;
  labelKey: string;
  value: number;
  sublabelPlural?: string;
  subvalue?: number;
  icon: string;
  color: string;
  route: string;
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
  /** The record's id: the row's identity for tracking, never shown (QA D992). */
  id: string;
  /** The human code the record carries (encounter, order or invoice number, MRN…), or ''. */
  code: string;
  summary: string;
  timestamp: string | null;
  timestampLabel: string;
  dateOnly: boolean;
  subject: string;
  hospital: string;
  details: string;
  statusLabel: string;
  statusKey: string;
}

import { EnumLabelService } from '../core/enum-label.service';
import { DateTimePipe } from '../shared/pipes/date-time.pipe';
@Component({
  selector: 'app-super-admin',
  standalone: true,
  imports: [
    FormsModule,
    RoleLabelPipe,
    DashPipe,
    KeyLabelPipe,
    DateTimePipe,
    ServerTimePipe,
    PluralPipe,
    CommonModule,
    RouterLink,
    TranslateModule,
    DatePipe,
    EnumLabelPipe,
  ],
  templateUrl: './super-admin.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './super-admin.scss',
})
export class SuperAdminComponent implements OnInit {
  private readonly dashboard = inject(DashboardService);
  private readonly auditSearch = inject(AuditSearchService);
  private readonly roleContext = inject(RoleContextService);
  private auditRequest = 0;
  readonly auditPage = signal<AuditSearchPage | null>(null);
  readonly auditLoading = signal(false);
  readonly auditUnavailable = signal(false);
  readonly auditDays = signal(1);
  readonly auditBounds = signal(this.newAuditBounds());
  readonly auditTotal = computed(() => this.auditPage()?.totalElements ?? 0);
  readonly auditFilter = computed<AuditSearchFilter>(() => ({
    ...this.auditBounds(),
    status: this.auditFailuresOnly() ? 'FAILURE' : undefined,
    excludeRoutineSuccesses: !this.showRoutineAudit(),
    hospitalId: this.roleContext.selectedHospitalId() ?? undefined,
  }));
  readonly auditQueryParams = computed(() => this.auditFilter());
  readonly recordedAuditValue = recordedAuditValue;
  readonly auditTargetKey = auditTargetKey;
  private readonly enumLabels = inject(EnumLabelService);
  /** QA D619: an audit entity token as its keyed label (core/enum-label.service). */
  entityLabel(raw: string | null | undefined): string | null {
    return this.enumLabels.auditEntityLabel(raw);
  }

  private readonly platform = inject(PlatformService);
  private readonly integrationHealth = inject(IntegrationHealthService);
  private readonly destroyRef = inject(DestroyRef);

  readonly loading = signal(true);
  readonly errored = signal(false);
  readonly summaryUnavailable = signal(false);
  readonly platformUnavailable = signal(false);
  readonly activityLoading = signal(false);
  readonly auditFailuresOnly = signal(false);
  readonly showRoutineAudit = signal(false);
  readonly auditExpanded = signal(false);
  readonly displayedAudit = computed(() => this.auditPage()?.content ?? []);
  readonly activityUnavailable = signal(false);
  readonly healthLoading = signal(true);
  readonly healthUnavailable = signal(false);
  readonly health = signal<IntegrationHealthSummary | null>(null);
  readonly healthCards = computed(() => [
    { key: 'FAILING', count: this.health()?.failingCount, icon: 'error' },
    { key: 'DEGRADED', count: this.health()?.degradedCount, icon: 'warning' },
    { key: 'NO_HISTORY', count: this.health()?.noHistoryCount, icon: 'help' },
    { key: 'HEALTHY', count: this.health()?.healthyCount, icon: 'check_circle' },
  ]);
  private loadRequest = 0;

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
        sublabelPlural: 'SUPER_ADMIN.STAT.ACTIVE_F',
        subvalue: s.activeOrganizations ?? 0,
        icon: 'corporate_fare',
        color: '#6366f1',
        route: '/organizations',
      },
      {
        key: 'hospitals',
        labelKey: 'SUPER_ADMIN.STAT.HOSPITALS',
        value: s.totalHospitals ?? 0,
        sublabelPlural: 'SUPER_ADMIN.STAT.ACTIVE_M',
        subvalue: s.activeHospitals ?? 0,
        icon: 'local_hospital',
        color: '#0ea5e9',
        route: '/hospitals',
      },
      {
        key: 'users',
        labelKey: 'COMMON.LABEL.USERS',
        value: s.totalUsers ?? 0,
        sublabelPlural: 'SUPER_ADMIN.STAT.ACTIVE_M',
        subvalue: s.activeUsers ?? 0,
        icon: 'group',
        color: '#23b79c',
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
        sublabelPlural: 'SUPER_ADMIN.STAT.ACTIVE_F',
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
        labelKey: 'COMMON.LABEL.PRESCRIPTIONS',
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
      labelKey: 'COMMON.LABEL.PRESCRIPTIONS',
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

  readonly activityRoutes: Record<ActivityKey, string> = {
    consultations: '/consultations',
    admissions: '/admissions',
    prescriptions: '/prescriptions',
    labOrders: '/lab',
    labResults: '/lab-results',
    labTestDefinitions: '/lab-test-config',
    treatmentPlans: '/treatment-plans',
    referrals: '/referrals',
  };

  readonly selectedActivityRows = computed<ActivityRow[]>(() => {
    const tab = this.selectedActivityTab();
    const items = this.recent()[tab] ?? [];
    return items.map((item) => this.toActivityRow(item, tab));
  });

  readonly platformActions = computed<ActionPanel | null>(
    () => this.platformSummary()?.actions ?? null,
  );

  private newAuditBounds(): { fromDate: string; toDate: string } {
    const now = new Date();
    const from = new Date(now.getTime() - (this.auditDays?.() ?? 1) * 86400000);
    return { fromDate: from.toISOString().slice(0, 19), toDate: now.toISOString().slice(0, 19) };
  }

  setAuditFailures(value: boolean): void {
    this.auditFailuresOnly.set(value);
    this.auditExpanded.set(false);
    this.loadAudit();
  }

  setAuditRoutine(value: boolean): void {
    this.showRoutineAudit.set(value);
    this.auditExpanded.set(false);
    this.loadAudit();
  }

  setAuditDays(value: number): void {
    this.auditDays.set(Number(value));
    this.auditBounds.set(this.newAuditBounds());
    this.auditExpanded.set(false);
    this.loadAudit();
  }

  expandAudit(): void {
    this.auditExpanded.set(!this.auditExpanded());
    this.loadAudit();
  }

  loadAudit(): void {
    const request = ++this.auditRequest;
    this.auditLoading.set(true);
    this.auditUnavailable.set(false);
    this.auditPage.set(null);
    this.auditSearch
      .search({ ...this.auditFilter(), page: 0, size: this.auditExpanded() ? 10 : 5 })
      .pipe(
        catchError(() => of(null)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((page) => {
        if (request !== this.auditRequest) return;
        this.auditPage.set(page);
        this.auditUnavailable.set(page === null);
        this.auditLoading.set(false);
      });
  }

  auditTargetLabel(event: AuditSearchRow): string | null {
    const target = recordedAuditValue(event.resourceName);
    const technical =
      target.includes('/') || target.includes('?') || /^[a-z]+(?:-[a-z]+)+$/.test(target);
    return (!technical && this.entityLabel(target)) || this.entityLabel(event.entityType);
  }

  auditScopeKey(event: AuditSearchRow): string {
    return event.entityType === 'PLATFORM'
      ? 'SUPER_ADMIN.AUDIT_OVERSIGHT.PLATFORM'
      : 'SUPER_ADMIN.AUDIT_OVERSIGHT.SCOPE_UNKNOWN';
  }

  readonly generatedAt = computed<string | null>(() => this.summary()?.generatedAt ?? null);

  ngOnInit(): void {
    this.loadAll();
  }

  loadAll(): void {
    this.auditBounds.set(this.newAuditBounds());
    this.loadAudit();
    const request = ++this.loadRequest;
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
    this.summaryUnavailable.set(false);
    this.platformUnavailable.set(false);
    this.activityLoading.set(true);
    this.activityUnavailable.set(false);
    this.healthLoading.set(true);
    this.healthUnavailable.set(false);
    this.health.set(null);
    this.integrationHealth
      .getInventory()
      .pipe(
        catchError(() => of(null)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((health) => {
        if (request !== this.loadRequest) return;
        this.health.set(health);
        this.healthLoading.set(false);
        this.healthUnavailable.set(health === null);
      });
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
        if (request !== this.loadRequest) return;
        if (res === null) {
          summaryFailed.set(true);
        }
        this.summary.set(res);
        this.summaryUnavailable.set(res === null);
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
        if (request !== this.loadRequest) return;
        if (res === null) {
          platformFailed.set(true);
        }
        this.platformSummary.set(res);
        this.platformUnavailable.set(res?.actions == null);
        this.errored.set(summaryFailed() && platformFailed());
      });

    // A failed aggregate feed is unavailable, not a successful empty response.
    this.dashboard
      .getRecentActivity(10)
      .pipe(
        catchError(() => of(null)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((bundle) => {
        if (request !== this.loadRequest) return;
        this.activityLoading.set(false);
        this.activityUnavailable.set(bundle === null);
        this.recent.set({
          consultations: bundle?.consultations ?? [],
          labOrders: bundle?.labOrders ?? [],
          labResults: bundle?.labResults ?? [],
          labTestDefinitions: bundle?.labTestDefinitions ?? [],
          admissions: bundle?.admissions ?? [],
          prescriptions: bundle?.prescriptions ?? [],
          treatmentPlans: bundle?.treatmentPlans ?? [],
          referrals: bundle?.referrals ?? [],
          // bundle.encounters is fetched too; not surfaced as an activity
          // tab today (that's a separate frontend follow-up — the tab
          // map currently has 8 keys, encounters is on the cards row).
        });
      });
  }

  selectActivityTab(key: ActivityKey): void {
    this.selectedActivityTab.set(key);
  }

  onActivityKeydown(event: KeyboardEvent): void {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    const current = this.activityTabs.findIndex((tab) => tab.key === this.selectedActivityTab());
    const last = this.activityTabs.length - 1;
    const next =
      event.key === 'Home'
        ? 0
        : event.key === 'End'
          ? last
          : (current + (event.key === 'ArrowRight' ? 1 : last)) % this.activityTabs.length;
    event.preventDefault();
    this.selectActivityTab(this.activityTabs[next].key);
    const list = (event.currentTarget as HTMLElement).parentElement;
    list?.querySelector<HTMLElement>(`#activity-tab-${this.activityTabs[next].key}`)?.focus();
  }

  countForTab(tab: ActivityTab): number {
    const s = this.summary();
    return s ? tab.countOf(s) : 0;
  }

  /**
   * Ordered, semantic field list per activity tab. The previous
   * heuristic ("filter out fields ending in 'id'/'at'/'date' and take
   * the first 2") drew arbitrary pairs from object-enumeration order,
   * which on a prescription row produced "Hospital B · DoctorBF DoctorBL"
   * with no patient name or medication — exactly the bug the super-admin
   * dashboard surfaced on PR review.
   *
   * Each entry names the DTO fields we want to surface, in clinical
   * priority. {@link #toActivityRow} extracts them in order, skips
   * blanks, and joins with the bullet separator.
   */
  private static readonly ACTIVITY_FIELDS: Record<ActivityKey, string[]> = {
    consultations: ['patientName', 'specialtyRequested', 'urgency', 'status'],
    admissions: ['patientName', 'admissionType', 'chiefComplaint', 'status'],
    prescriptions: ['patientFullName', 'medicationName', 'dosage', 'frequency', 'status'],
    labOrders: ['patientFullName', 'labTestName', 'status'],
    labResults: ['patientFullName', 'labTestName', 'resultValue', 'status'],
    labTestDefinitions: ['name', 'labTestCode', 'category'],
    treatmentPlans: ['patientName', 'status'],
    referrals: ['patientName', 'receivingHospitalName', 'referralType', 'status'],
  };

  /**
   * QA CX-006: the coded fields of {@link ACTIVITY_FIELDS}, per tab, with their
   * label domain: the summaries showed ROUTINE, URGENT, REQUESTED, CANCELLED in
   * every language. `null` looks the code up in every domain (a lab result's
   * status has no domain of its own). `scripts/lib/activity-summary-enums.test.mjs`
   * fails on a coded field left out of this table.
   */
  private static readonly ACTIVITY_ENUM_DOMAINS: Record<
    ActivityKey,
    Readonly<Record<string, string | null>>
  > = {
    consultations: { urgency: 'consultationUrgency', status: 'consultationStatus' },
    admissions: { admissionType: 'admissionType', status: 'admissionStatus' },
    prescriptions: { status: 'prescriptionStatus' },
    labOrders: { status: 'labOrderStatus' },
    labResults: { status: null },
    labTestDefinitions: {},
    treatmentPlans: { status: 'treatmentPlanStatus' },
    referrals: { referralType: 'referralType', status: 'referralStatus' },
  };

  /** Human codes a recent-activity record may carry, most specific first. */
  private static readonly ACTIVITY_CODE_FIELDS = [
    'labOrderCode',
    'encounterNumber',
    'orderNumber',
    'accessionNumber',
    'invoiceNumber',
    'referralNumber',
    'prescriptionNumber',
    'admissionNumber',
    'mrn',
    'patientMrn',
    'labTestCode',
  ];

  private toActivityRow(item: SuperAdminRecentItem, tab: ActivityKey): ActivityRow {
    // QA D992 class: the column showed the first 8 characters of the UUID.
    // It now shows the human code the record carries, or nothing.
    const id = typeof item['id'] === 'string' ? (item['id'] as string) : '';
    const code =
      SuperAdminComponent.ACTIVITY_CODE_FIELDS.map((field) => item[field]).find(
        (v): v is string => typeof v === 'string' && v.trim().length > 0,
      ) ?? '';
    const ordered = SuperAdminComponent.ACTIVITY_FIELDS[tab] ?? [];
    // QA D1002 class: a lab value in the summary reads in the reader's number format.
    const domains = SuperAdminComponent.ACTIVITY_ENUM_DOMAINS[tab] ?? {};
    const stringFields = ordered
      .map((field) => this.formatActivityField(item, field, domains))
      .filter((value) => value.length > 0);
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
    const timestampField = [
      'encounterDate',
      'admissionDateTime',
      'resultDate',
      'orderDatetime',
      'submittedAt',
      'timelineStartDate',
      'consentTimestamp',
      'requestedAt',
      'admissionDate',
      'orderedAt',
      'createdAt',
    ].find((field) => item[field] != null);
    const timestamp = timestampField ? (item[timestampField] as string) : null;
    // Every semantic field list starts with its patient or catalog resource.
    const subject = String(item[ordered[0]] ?? '').trim();
    const details = ordered
      .slice(1)
      .filter((field) => field !== 'status')
      .map((field) => this.formatActivityField(item, field, domains))
      .filter(Boolean)
      .join(' · ');
    return {
      id,
      code,
      subject,
      hospital: String(item['hospitalName'] ?? '').trim(),
      details,
      statusLabel: this.formatActivityField(item, 'status', domains),
      statusKey: this.activityStatusKey(item, tab),
      timestampLabel: 'SUPER_ADMIN.ACTIVITY.DATE_' + (timestampField ?? 'UNKNOWN'),
      dateOnly: /^\d{4}-\d{2}-\d{2}$/.test(timestamp ?? ''),
      summary: stringFields.join(' · '),
      timestamp,
    };
  }
  private formatActivityField(
    item: SuperAdminRecentItem,
    field: string,
    domains: Readonly<Record<string, string | null>>,
  ): string {
    const value = field === 'resultValue' ? formatLabNumber(item[field] as string) : item[field];
    if (value == null) return '';
    return field in domains
      ? this.enumLabels.transform(String(value), domains[field] ?? undefined)
      : String(value);
  }

  private activityStatusKey(item: SuperAdminRecentItem, tab: ActivityKey): string {
    if (tab !== 'labResults' || typeof item['released'] !== 'boolean') return '';
    return item['released'] ? 'LAB_RESULTS.RELEASED' : 'LAB_RESULTS.PENDING_RELEASE';
  }
}
