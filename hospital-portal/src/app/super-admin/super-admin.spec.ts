import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { ActivityKey, SuperAdminComponent } from './super-admin';
import {
  DashboardService,
  RecentAuditEvent,
  SuperAdminRecentActivityBundle,
  SuperAdminRecentItem,
  SuperAdminSummary,
} from '../services/dashboard.service';
import { PlatformService, PlatformSummary } from '../services/platform.service';

/**
 * Empty {@link SuperAdminRecentActivityBundle} for tests — F5 from
 * docs/super-admin-cross-tenant-design.md collapsed the dashboard's 8
 * `getRecent*` subscriptions into a single `getRecentActivity()` call,
 * so test stubs build a bundle once and overlay per-feed rows where
 * needed via {@link bundleWith}.
 */
function emptyBundle(): SuperAdminRecentActivityBundle {
  return {
    encounters: [],
    consultations: [],
    labOrders: [],
    labResults: [],
    labTestDefinitions: [],
    admissions: [],
    prescriptions: [],
    treatmentPlans: [],
    referrals: [],
  };
}

function bundleWith(
  overrides: Partial<SuperAdminRecentActivityBundle>,
): SuperAdminRecentActivityBundle {
  return { ...emptyBundle(), ...overrides };
}

const fakeSummary = (overrides: Partial<SuperAdminSummary> = {}): SuperAdminSummary => ({
  totalUsers: 120,
  activeUsers: 100,
  inactiveUsers: 20,
  totalHospitals: 8,
  activeHospitals: 7,
  inactiveHospitals: 1,
  totalOrganizations: 3,
  activeOrganizations: 3,
  totalDepartments: 24,
  totalPatients: 4321,
  totalRoles: 18,
  totalAssignments: 200,
  activeAssignments: 180,
  inactiveAssignments: 20,
  globalAssignments: 5,
  activeGlobalAssignments: 5,
  todayAppointmentsCount: 42,
  totalEncounters: 11,
  totalConsultations: 22,
  totalLabOrders: 33,
  totalLabResults: 44,
  totalLabTestDefinitions: 55,
  totalAdmissions: 66,
  totalPrescriptions: 77,
  totalTreatmentPlans: 88,
  totalReferrals: 99,
  generatedAt: '2026-05-02T12:00:00Z',
  recentAuditEvents: [],
  ...overrides,
});

const fakePlatformSummary = (): PlatformSummary => ({
  modules: [],
  automationTasks: [],
  actions: {
    totalIntegrations: 12,
    pendingIntegrations: 2,
    disabledLinks: 1,
    activeReleaseWindows: 0,
  },
});

const auditEvent = (id: string, status: 'SUCCESS' | 'FAILURE' = 'SUCCESS'): RecentAuditEvent => ({
  id,
  eventType: 'USER_LOGIN',
  status,
  entityType: 'User',
  resourceId: 'r1',
  resourceName: 'jdoe',
  userName: 'jdoe',
  roleName: 'ROLE_DOCTOR',
  hospitalName: 'St. Mary',
  eventTimestamp: '2026-05-02T11:55:00Z',
  eventDescription: 'Login',
});

describe('SuperAdminComponent', () => {
  let dashboard: jasmine.SpyObj<DashboardService>;
  let platform: jasmine.SpyObj<PlatformService>;

  function stubAllRecent(): void {
    // F5: super-admin.ts now fetches the aggregate /recent-activity
    // bundle in one call instead of 8 individual calls. Stub the bundle
    // method with empty arrays for every feed by default; tests that
    // need a specific feed populate it via `bundleWith({ key: rows })`.
    dashboard.getRecentActivity.and.returnValue(of(emptyBundle()));
  }

  function setup(): SuperAdminComponent {
    TestBed.configureTestingModule({
      imports: [SuperAdminComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: DashboardService, useValue: dashboard },
        { provide: PlatformService, useValue: platform },
      ],
    });
    const fixture = TestBed.createComponent(SuperAdminComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  beforeEach(() => {
    dashboard = jasmine.createSpyObj<DashboardService>('DashboardService', [
      'getSummary',
      'getRecentActivity',
    ]);
    platform = jasmine.createSpyObj<PlatformService>('PlatformService', ['getSummary']);
    stubAllRecent();
  });

  afterEach(() => TestBed.resetTestingModule());

  it('builds 8 stat tiles from a successful summary', () => {
    dashboard.getSummary.and.returnValue(of(fakeSummary()));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));

    const c = setup();

    expect(c.loading()).toBeFalse();
    expect(c.errored()).toBeFalse();
    expect(c.stats().length).toBe(8);
    expect(c.stats().find((s) => s.key === 'organizations')?.value).toBe(3);
    expect(c.stats().find((s) => s.key === 'hospitals')?.subvalue).toBe(7);
    expect(c.platformActions()?.totalIntegrations).toBe(12);
  });

  it('exposes audit events from the summary', () => {
    dashboard.getSummary.and.returnValue(
      of(fakeSummary({ recentAuditEvents: [auditEvent('a1'), auditEvent('a2', 'FAILURE')] })),
    );
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));

    const c = setup();

    expect(c.recentAudit().length).toBe(2);
    expect(c.recentAudit()[1].status).toBe('FAILURE');
  });

  it('shows empty stats and an error flag when both sources fail', () => {
    dashboard.getSummary.and.returnValue(throwError(() => new Error('boom')));
    platform.getSummary.and.returnValue(throwError(() => new Error('boom')));

    const c = setup();

    expect(c.loading()).toBeFalse();
    expect(c.errored()).toBeTrue();
    expect(c.stats()).toEqual([]);
    expect(c.platformActions()).toBeNull();
  });

  it('does not error when only the platform call fails', () => {
    dashboard.getSummary.and.returnValue(of(fakeSummary()));
    platform.getSummary.and.returnValue(throwError(() => new Error('boom')));

    const c = setup();

    expect(c.errored()).toBeFalse();
    expect(c.stats().length).toBe(8);
    expect(c.platformActions()).toBeNull();
  });

  it('exposes the quick-link grid covering core super-admin destinations', () => {
    dashboard.getSummary.and.returnValue(of(fakeSummary()));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));

    const c = setup();
    const routes = c.quickLinks.map((l) => l.route);

    // MVP-{6,7,8} added Audit Search / Emergency / Subscriptions cards on top of MVP-3,
    // and MVP-9 added the Data Residency card.
    expect(c.quickLinks.length).toBe(13);
    expect(routes).toContain('/super-admin/integrations');
    expect(routes).toContain('/super-admin/audit-search');
    expect(routes).toContain('/super-admin/emergency');
    expect(routes).toContain('/super-admin/subscriptions');
    expect(routes).toContain('/super-admin/data-residency');
    // MVP-5b: the four super-admin-namespaced cards now use the
    // /super-admin/* aliases; legacy URLs are reachable via the
    // path-rewrite guard but the Control Tower advertises the
    // canonical paths.
    expect(routes).toEqual(
      jasmine.arrayContaining([
        '/organizations',
        '/users',
        '/roles',
        '/super-admin/feature-flags',
        '/super-admin/analytics',
        '/super-admin/platform',
        '/super-admin/audit-logs',
        '/hospitals',
      ]),
    );
  });

  it('builds 9 clinical stat tiles from the summary', () => {
    dashboard.getSummary.and.returnValue(of(fakeSummary()));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));

    const c = setup();

    const clinical = c.clinicalStats();
    expect(clinical.length).toBe(9);
    expect(clinical.find((s) => s.key === 'encounters')?.value).toBe(11);
    expect(clinical.find((s) => s.key === 'consultations')?.value).toBe(22);
    expect(clinical.find((s) => s.key === 'lab_results')?.value).toBe(44);
    expect(clinical.find((s) => s.key === 'referrals')?.value).toBe(99);
  });

  it('fetches the aggregate recent-activity bundle in a single call (F5)', () => {
    // Pre-F5 the dashboard fanned out 8 individual `getRecentX` calls
    // (`forkJoin` originally, then a streaming load). After F5 from
    // docs/super-admin-cross-tenant-design.md the dashboard makes ONE
    // round-trip against /super-admin/recent-activity. This test locks
    // the new behaviour so a future "consistency" refactor doesn't
    // silently re-introduce the 8-way fan-out.
    dashboard.getSummary.and.returnValue(of(fakeSummary()));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));

    setup();

    expect(dashboard.getRecentActivity).toHaveBeenCalledTimes(1);
    expect(dashboard.getRecentActivity).toHaveBeenCalledWith(10);
  });

  it('renders selected activity tab rows from the response', () => {
    dashboard.getSummary.and.returnValue(of(fakeSummary()));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));
    dashboard.getRecentActivity.and.returnValue(
      of(
        bundleWith({
          prescriptions: [
            {
              id: 'aaaa1111-2222-3333-4444-555555555555',
              medicationName: 'Amoxicillin',
              patientName: 'Ada',
              createdAt: '2026-05-02T10:00:00Z',
            },
          ] as SuperAdminRecentItem[],
        }),
      ),
    );

    const c = setup();
    c.selectActivityTab('prescriptions');

    const rows = c.selectedActivityRows();
    expect(rows.length).toBe(1);
    expect(rows[0].id).toBe('aaaa1111');
    expect(rows[0].summary).toContain('Amoxicillin');
    expect(rows[0].timestamp).toBe('2026-05-02T10:00:00Z');
  });

  it('reflects empty activity collections gracefully', () => {
    dashboard.getSummary.and.returnValue(of(fakeSummary()));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));
    // All recent endpoints already stubbed to of([])

    const c = setup();
    c.selectActivityTab('referrals');

    expect(c.selectedActivityRows()).toEqual([]);
  });

  it('reports the correct count for each activity tab', () => {
    dashboard.getSummary.and.returnValue(of(fakeSummary()));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));

    const c = setup();
    const consultationTab = c.activityTabs.find((t) => t.key === 'consultations')!;
    const referralTab = c.activityTabs.find((t) => t.key === 'referrals')!;

    expect(c.countForTab(consultationTab)).toBe(22);
    expect(c.countForTab(referralTab)).toBe(99);
  });

  /* ────────────────────────────────────────────────────────────────────
   * Copilot review on commit 2678681f.
   * ──────────────────────────────────────────────────────────────────── */

  // Finding #3: row-timestamp extractor must read the entity-specific
  // clinical-time field, not just `createdAt` / a small allowlist. If
  // the extractor falls back to null the activity panel renders rows
  // with a blank timestamp.
  //
  // (The Angular DashboardService doesn't yet surface
  // `getRecentEncounters` to the super-admin activity panel — that's
  // a separate gap. Until the encounters tab is added here, we
  // exercise the 7 tabs that DO exist plus their clinical-time field.)
  const TIMESTAMP_FIELDS_BY_TAB: {
    tab: ActivityKey;
    field: string;
    bundleKey: keyof SuperAdminRecentActivityBundle;
  }[] = [
    { tab: 'admissions', field: 'admissionDateTime', bundleKey: 'admissions' },
    { tab: 'labResults', field: 'resultDate', bundleKey: 'labResults' },
    { tab: 'labOrders', field: 'orderDatetime', bundleKey: 'labOrders' },
    { tab: 'referrals', field: 'submittedAt', bundleKey: 'referrals' },
    { tab: 'treatmentPlans', field: 'timelineStartDate', bundleKey: 'treatmentPlans' },
    { tab: 'prescriptions', field: 'createdAt', bundleKey: 'prescriptions' },
  ];

  for (const { tab, field, bundleKey } of TIMESTAMP_FIELDS_BY_TAB) {
    it(`row timestamp extractor reads "${field}" for ${tab}`, () => {
      dashboard.getSummary.and.returnValue(of(fakeSummary()));
      platform.getSummary.and.returnValue(of(fakePlatformSummary()));
      const row: SuperAdminRecentItem = {
        id: 'aaaa1111-2222-3333-4444-555555555555',
        patientName: 'Ada',
        // Set the entity's clinical-time field — but NOT createdAt
        // (except for the prescriptions row, where `createdAt` IS
        // the clinical-time field by design — see the comment on
        // SuperAdminDashboardServiceImpl.getRecentPrescriptions).
        [field]: '2026-05-04T10:00:00Z',
      };
      dashboard.getRecentActivity.and.returnValue(
        of(bundleWith({ [bundleKey]: [row] } as Partial<SuperAdminRecentActivityBundle>)),
      );

      const c = setup();
      c.selectActivityTab(tab);

      const rows = c.selectedActivityRows();
      expect(rows.length).toBe(1);
      expect(rows[0].timestamp).toBe('2026-05-04T10:00:00Z');
    });
  }

  it('row timestamp prefers the clinical-time field over createdAt', () => {
    // When both `admissionDateTime` (clinical) and `createdAt` (row
    // insert) are present, the extractor must pick the clinical one —
    // that's the whole point of the Copilot finding.
    dashboard.getSummary.and.returnValue(of(fakeSummary()));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));
    dashboard.getRecentActivity.and.returnValue(
      of(
        bundleWith({
          admissions: [
            {
              id: 'adm-1',
              patientName: 'Ada',
              admissionDateTime: '2026-04-01T08:00:00Z',
              createdAt: '2026-05-04T10:00:00Z', // late backfill
            },
          ] as unknown as SuperAdminRecentItem[],
        }),
      ),
    );

    const c = setup();
    c.selectActivityTab('admissions');

    expect(c.selectedActivityRows()[0].timestamp).toBe('2026-04-01T08:00:00Z');
  });

  // Finding #4: tabs need id + aria-controls; the panel needs a matching
  // id + aria-labelledby pointing back at the active tab. Without these
  // a screen-reader user can't tell which panel each tab opens.
  it('activity tabs and panel are wired together for screen readers', () => {
    dashboard.getSummary.and.returnValue(of(fakeSummary()));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));

    TestBed.configureTestingModule({
      imports: [SuperAdminComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: DashboardService, useValue: dashboard },
        { provide: PlatformService, useValue: platform },
      ],
    });
    const fix = TestBed.createComponent(SuperAdminComponent);
    fix.detectChanges();
    const dom: HTMLElement = fix.nativeElement;

    const tabs = dom.querySelectorAll<HTMLButtonElement>('[role="tab"]');
    expect(tabs.length).toBeGreaterThan(0);
    tabs.forEach((tab) => {
      expect(tab.id).toMatch(/^activity-tab-/);
      expect(tab.getAttribute('aria-controls')).toBe('activity-tabpanel');
    });

    const panel = dom.querySelector<HTMLElement>('[role="tabpanel"]');
    expect(panel).toBeTruthy();
    expect(panel!.id).toBe('activity-tabpanel');
    // aria-labelledby points at the currently-selected tab's id
    expect(panel!.getAttribute('aria-labelledby')).toBe(
      `activity-tab-${fix.componentInstance.selectedActivityTab()}`,
    );
  });
});
