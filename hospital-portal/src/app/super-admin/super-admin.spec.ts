import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError, Subject } from 'rxjs';

import { AuditSearchService } from '../services/audit-search.service';
import { AuditSearchRow } from '../services/audit-search.model';
import { RoleContextService } from '../core/role-context.service';
import { ActivityKey, SuperAdminComponent } from './super-admin';
import {
  DashboardService,
  RecentAuditEvent,
  SuperAdminRecentActivityBundle,
  SuperAdminRecentItem,
  SuperAdminSummary,
} from '../services/dashboard.service';
import { PlatformService, PlatformSummary } from '../services/platform.service';
import { IntegrationHealthService } from '../services/integration-health.service';
import fr from '../../assets/i18n/fr.json';
import es from '../../assets/i18n/es.json';

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
  let audits: jasmine.SpyObj<AuditSearchService>;
  let rows: AuditSearchRow[];
  let platform: jasmine.SpyObj<PlatformService>;
  let health: jasmine.SpyObj<IntegrationHealthService>;
  let fixture: ComponentFixture<SuperAdminComponent>;

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
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: DashboardService, useValue: dashboard },
        { provide: AuditSearchService, useValue: audits },
        { provide: RoleContextService, useValue: { selectedHospitalId: () => null } },
        { provide: PlatformService, useValue: platform },
        { provide: IntegrationHealthService, useValue: health },
      ],
    });
    fixture = TestBed.createComponent(SuperAdminComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  beforeEach(() => {
    rows = [];
    audits = jasmine.createSpyObj<AuditSearchService>('AuditSearchService', ['search']);
    audits.search.and.callFake(() =>
      of({ content: rows, totalElements: rows.length, pageSize: 5, pageNumber: 0, totalPages: 1 }),
    );
    dashboard = jasmine.createSpyObj<DashboardService>('DashboardService', [
      'getSummary',
      'getRecentActivity',
    ]);
    platform = jasmine.createSpyObj<PlatformService>('PlatformService', ['getSummary']);
    health = jasmine.createSpyObj<IntegrationHealthService>('IntegrationHealthService', [
      'getInventory',
    ]);
    health.getInventory.and.returnValue(
      of({
        totalIntegrations: 12,
        healthyCount: 6,
        degradedCount: 2,
        failingCount: 3,
        noHistoryCount: 1,
        integrations: [],
      }),
    );
    stubAllRecent();
  });

  afterEach(() => TestBed.resetTestingModule());

  it('keeps all administration destinations visible before the network overview', () => {
    dashboard.getSummary.and.returnValue(of(fakeSummary()));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));
    setup();
    const dom: HTMLElement = fixture.nativeElement;
    expect(dom.querySelectorAll('[data-test="links-grid"] a').length).toBe(13);
    expect(dom.querySelector('details')).toBeNull();
    expect(
      dom
        .querySelector('.admin-directory')!
        .compareDocumentPosition(dom.querySelector('.network-overview')!) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });
  it('ignores an older health request after refresh', () => {
    dashboard.getSummary.and.returnValue(of(fakeSummary()));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));
    const older = new Subject<
      import('../services/integration-health.model').IntegrationHealthSummary
    >();
    health.getInventory.and.returnValue(older);
    const component = setup();
    health.getInventory.and.returnValue(throwError(() => new Error('latest request unavailable')));
    component.loadAll();
    older.next({
      totalIntegrations: 1,
      healthyCount: 1,
      degradedCount: 0,
      failingCount: 0,
      noHistoryCount: 0,
      integrations: [],
    });
    expect(component.healthUnavailable()).toBeTrue();
    expect(component.health()).toBeNull();
  });

  it('does not show zero network totals when the summary source failed', () => {
    dashboard.getSummary.and.returnValue(throwError(() => new Error('summary unavailable')));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));
    setup();
    const values: HTMLElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('.network-overview .stat-value'),
    );
    expect(values.length).toBe(0);
    expect(
      fixture.nativeElement.querySelector('.network-overview .availability-note').textContent,
    ).toContain('SUPER_ADMIN.SUMMARY_UNAVAILABLE');
  });

  it('uses server health counts independently of returned inventory rows', () => {
    dashboard.getSummary.and.returnValue(of(fakeSummary()));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));
    const component = setup();
    expect(component.healthCards().map((card) => card.count)).toEqual([3, 2, 1, 6]);
    expect(fixture.nativeElement.querySelectorAll('.health-card.attention').length).toBe(2);
  });
  it('keeps health errors distinct from healthy zero counts', () => {
    dashboard.getSummary.and.returnValue(of(fakeSummary()));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));
    health.getInventory.and.returnValue(throwError(() => new Error('offline')));
    const component = setup();
    expect(component.healthUnavailable()).toBeTrue();
    expect(fixture.nativeElement.querySelector('[data-test="health-grid"]')).toBeNull();
  });
  it('retains failed refreshes returned by the server and passes both filters without narrowing the sample', () => {
    dashboard.getSummary.and.returnValue(of(fakeSummary()));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));
    rows = [{ ...auditEvent('failed', 'FAILURE'), eventType: 'TOKEN_REFRESH' }] as AuditSearchRow[];
    const c = setup();
    expect(c.displayedAudit()[0].id).toBe('failed');
    c.setAuditFailures(true);
    expect(audits.search.calls.mostRecent().args[0]).toEqual(
      jasmine.objectContaining({ status: 'FAILURE', excludeRoutineSuccesses: true }),
    );
  });
  it('distinguishes an unavailable clinical feed from an empty feed', () => {
    dashboard.getSummary.and.returnValue(of(fakeSummary()));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));
    dashboard.getRecentActivity.and.returnValue(of(null));
    const component = setup();
    expect(component.activityUnavailable()).toBeTrue();
    expect(
      fixture.nativeElement.querySelector('[data-test="activity-list"]').textContent,
    ).toContain('SUPER_ADMIN.CONTROL.ACTIVITY_UNAVAILABLE');
  });

  it('uses registered canonical destinations and warns only for nonzero attention counts', () => {
    dashboard.getSummary.and.returnValue(of(fakeSummary()));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));
    setup();
    const dom: HTMLElement = fixture.nativeElement;
    const cards = Array.from(
      dom.querySelectorAll<HTMLAnchorElement>('[data-test="platform-strip"] a'),
    );
    expect(cards.length).toBe(4);
    expect(cards.every((card) => card.getAttribute('href') === '/super-admin/platform')).toBeTrue();
    expect(dom.querySelector('.audit-panel .audit-footer')?.getAttribute('href')).toContain(
      '/super-admin/audit-search?',
    );
    expect(cards.map((card) => card.classList.contains('warn'))).toEqual([
      false,
      true,
      true,
      false,
    ]);
    const clear = fakePlatformSummary();
    clear.actions.pendingIntegrations = 0;
    clear.actions.disabledLinks = 0;
    platform.getSummary.and.returnValue(of(clear));
    fixture.componentInstance.loadAll();
    fixture.detectChanges();
    expect(dom.querySelectorAll('.strip-card.warn').length).toBe(0);
  });

  it('shows unavailable rather than perpetual loading when a platform response lacks actions', () => {
    dashboard.getSummary.and.returnValue(of(fakeSummary()));
    platform.getSummary.and.returnValue(
      of({ modules: [], automationTasks: [] } as unknown as PlatformSummary),
    );
    setup();
    const panel: HTMLElement = fixture.nativeElement.querySelector('.platform-panel');
    expect(panel.textContent).toContain('SUPER_ADMIN.PLATFORM_UNAVAILABLE');
    expect(panel.textContent).not.toContain('COMMON.LOADING');
  });

  it('requests server-side routine-success suppression and includes routine reads only on request', () => {
    dashboard.getSummary.and.returnValue(of(fakeSummary()));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));
    const c = setup();
    expect(audits.search.calls.mostRecent().args[0].excludeRoutineSuccesses).toBeTrue();
    c.setAuditRoutine(true);
    expect(audits.search.calls.mostRecent().args[0].excludeRoutineSuccesses).toBeFalse();
    c.setAuditRoutine(false);
    expect(audits.search.calls.mostRecent().args[0].excludeRoutineSuccesses).toBeTrue();
  });

  it('distinguishes unavailable audit history from a successful empty search', () => {
    dashboard.getSummary.and.returnValue(of(fakeSummary()));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));
    const c = setup();
    expect(c.auditUnavailable()).toBeFalse();
    audits.search.and.returnValue(throwError(() => new Error('offline')));
    c.loadAudit();
    fixture.detectChanges();
    expect(c.auditUnavailable()).toBeTrue();
    expect(fixture.nativeElement.querySelector('.audit-list').textContent).toContain(
      'SUPER_ADMIN.AUDIT_OVERSIGHT.UNAVAILABLE',
    );
    expect(fixture.nativeElement.querySelector('.audit-list .activity-empty')).toBeNull();
  });

  it('reads an audit entity token in the reader language (D619)', () => {
    dashboard.getSummary.and.returnValue(
      of(
        fakeSummary({
          recentAuditEvents: [
            { ...auditEvent('unknown'), resourceName: 'Unknown Resource' },
            { ...auditEvent('invoice'), resourceName: 'BILLING_INVOICE' },
          ],
        }),
      ),
    );
    rows = [
      { ...auditEvent('unknown'), resourceName: 'Unknown Resource' },
      { ...auditEvent('invoice'), resourceName: 'BILLING_INVOICE' },
    ] as AuditSearchRow[];
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));
    const c = setup();
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', {
      PORTAL: { ENUM: { AUDIT_ENTITY_TYPE: { BILLING_INVOICE: 'Facture' } } },
    });
    translate.use('fr');
    expect(c.entityLabel('BILLING_INVOICE')).toBe('Facture');
    expect(c.entityLabel('Unknown Resource')).toBeNull();
    fixture.detectChanges();
    const details: NodeListOf<HTMLElement> =
      fixture.nativeElement.querySelectorAll('.audit-details');
    expect(details.length).toBe(1);
    expect(details[0].querySelector('code')?.textContent).toBe('Facture');
    expect(fixture.nativeElement.querySelector('.audit-event').textContent).not.toContain(
      'BILLING_INVOICE',
    );
  });
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

  it('loads audit history independently of the ten summary events', () => {
    dashboard.getSummary.and.returnValue(
      of(fakeSummary({ recentAuditEvents: [auditEvent('summary-only')] })),
    );
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));
    rows = [auditEvent('history-event')] as AuditSearchRow[];
    const c = setup();
    expect(c.displayedAudit().map((row) => row.id)).toEqual(['history-event']);
    expect(audits.search.calls.mostRecent().args[0].size).toBe(5);
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
    expect(c.platformUnavailable()).toBeTrue();
  });

  it('keeps refreshed totals, platform status and activity when old responses arrive late', () => {
    const oldSummary = new Subject<SuperAdminSummary>();
    const oldPlatform = new Subject<PlatformSummary>();
    const oldActivity = new Subject<SuperAdminRecentActivityBundle>();
    dashboard.getSummary.and.returnValue(oldSummary);
    platform.getSummary.and.returnValue(oldPlatform);
    dashboard.getRecentActivity.and.returnValue(oldActivity);
    const c = setup();
    dashboard.getSummary.and.returnValue(of(fakeSummary({ totalPatients: 99 })));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));
    dashboard.getRecentActivity.and.returnValue(of(bundleWith({ consultations: [{ id: 'new' }] })));
    c.loadAll();
    oldSummary.next(fakeSummary({ totalPatients: 1 }));
    oldPlatform.next({
      ...fakePlatformSummary(),
      actions: {
        totalIntegrations: 0,
        pendingIntegrations: 0,
        disabledLinks: 0,
        activeReleaseWindows: 0,
      },
    });
    oldActivity.next(bundleWith({ consultations: [{ id: 'old' }] }));
    expect(c.stats().find((stat) => stat.key === 'patients')?.value).toBe(99);
    expect(c.platformActions()?.totalIntegrations).toBe(12);
    expect(c.selectedActivityRows()[0].id).toBe('new');
    expect(c.activityLoading()).toBeFalse();
  });

  it('reports an unavailable network summary while retaining successful platform status', () => {
    dashboard.getSummary.and.returnValue(throwError(() => new Error('offline')));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));
    const c = setup();
    expect(c.summaryUnavailable()).toBeTrue();
    expect(c.platformUnavailable()).toBeFalse();
    expect(c.errored()).toBeFalse();
    expect(c.stats()).toEqual([]);
    expect(c.platformActions()?.pendingIntegrations).toBe(2);
    const dom: HTMLElement = fixture.nativeElement;
    const clinicalPanel = dom.querySelector('#clinical-heading')?.parentElement;
    const auditPanel = dom.querySelector('.audit-panel');
    expect(clinicalPanel?.textContent).toContain('SUPER_ADMIN.SUMMARY_UNAVAILABLE');
    expect(auditPanel?.textContent).toContain('SUPER_ADMIN.RECENT_AUDIT');
    expect(auditPanel?.textContent).toContain('SUPER_ADMIN.AUDIT_OVERSIGHT.EMPTY');
    expect(auditPanel?.querySelector('.audit-filters')).not.toBeNull();
    expect(dom.querySelector('.platform-strip')).not.toBeNull();
  });

  it('queries history for failures and carries the same filters into full audit search', () => {
    dashboard.getSummary.and.returnValue(of(fakeSummary()));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));
    const c = setup();
    rows = [auditEvent('failure-beyond-the-summary', 'FAILURE')] as AuditSearchRow[];
    c.setAuditFailures(true);
    expect(audits.search.calls.mostRecent().args[0].status).toBe('FAILURE');
    expect(c.displayedAudit()[0].id).toBe('failure-beyond-the-summary');
    expect(c.auditQueryParams().status).toBe('FAILURE');
    expect(c.auditQueryParams().fromDate).toBe(audits.search.calls.mostRecent().args[0].fromDate);
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
    // QA D992 class: the full id identifies the row; the screen never shows a slice of it
    // (was: rows[0].id === 'aaaa1111'). With no human code on the record the column is empty.
    expect(rows[0].id).toBe('aaaa1111-2222-3333-4444-555555555555');
    expect(rows[0].code).toBe('');
    expect(rows[0].summary).toContain('Amoxicillin');
    expect(rows[0].timestamp).toBe('2026-05-02T10:00:00Z');
  });

  it('shows the human code a record carries, never a slice of its id (QA D992)', () => {
    dashboard.getSummary.and.returnValue(of(fakeSummary()));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));
    dashboard.getRecentActivity.and.returnValue(
      of(
        bundleWith({
          labOrders: [
            {
              id: 'bbbb2222-2222-3333-4444-555555555555',
              labOrderCode: 'LAB-000042',
              patientFullName: 'Ada',
              createdAt: '2026-05-02T10:00:00Z',
            },
          ] as SuperAdminRecentItem[],
        }),
      ),
    );

    const c = setup();
    c.selectActivityTab('labOrders');

    expect(c.selectedActivityRows()[0].code).toBe('LAB-000042');
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
    { tab: 'consultations', field: 'requestedAt', bundleKey: 'consultations' },
    { tab: 'labTestDefinitions', field: 'createdAt', bundleKey: 'labTestDefinitions' },
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
      const stamp = tab === 'treatmentPlans' ? '2026-05-04' : '2026-05-04T10:00:00Z';
      const row: SuperAdminRecentItem = {
        id: 'aaaa1111-2222-3333-4444-555555555555',
        patientName: 'Ada',
        // Set the entity's clinical-time field — but NOT createdAt
        // (except for the prescriptions row, where `createdAt` IS
        // the clinical-time field by design — see the comment on
        // SuperAdminDashboardServiceImpl.getRecentPrescriptions).
        [field]: stamp,
      };
      dashboard.getRecentActivity.and.returnValue(
        of(bundleWith({ [bundleKey]: [row] } as Partial<SuperAdminRecentActivityBundle>)),
      );

      const c = setup();
      c.selectActivityTab(tab);

      const rows = c.selectedActivityRows();
      expect(rows.length).toBe(1);
      expect(rows[0].timestamp).toBe(stamp);
      expect(rows[0].dateOnly).toBe(tab === 'treatmentPlans');
      fixture.detectChanges();
      if (tab === 'treatmentPlans') {
        const value = fixture.nativeElement.querySelector('.activity-timestamp span:last-child');
        expect(value.textContent).not.toContain(':');
      }
      expect(rows[0].timestampLabel).toBe('SUPER_ADMIN.ACTIVITY.DATE_' + field);
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
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: DashboardService, useValue: dashboard },
        { provide: AuditSearchService, useValue: audits },
        { provide: RoleContextService, useValue: { selectedHospitalId: () => null } },
        { provide: PlatformService, useValue: platform },
        { provide: IntegrationHealthService, useValue: health },
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
    tabs[0].focus();
    tabs[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    fix.detectChanges();
    expect(document.activeElement).toBe(tabs[1]);
    expect(tabs[1].getAttribute('aria-selected')).toBe('true');
    tabs[1].dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true }));
    fix.detectChanges();
    expect(document.activeElement).toBe(tabs[tabs.length - 1]);
  });

  it('QA CX-006: a coded field of a recent-activity summary reads as a label, free text as typed', () => {
    dashboard.getSummary.and.returnValue(of(fakeSummary()));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));
    dashboard.getRecentActivity.and.returnValue(
      of(
        bundleWith({
          consultations: [
            {
              id: 'c1',
              patientName: 'Ada',
              specialtyRequested: 'ROUTINE_SPECIALTY',
              urgency: 'ROUTINE',
              status: 'CANCELLED',
              createdAt: '2026-05-02T10:00:00Z',
            },
          ] as SuperAdminRecentItem[],
        }),
      ),
    );
    const c = setup();
    c.selectActivityTab('consultations');
    const summary = c.selectedActivityRows()[0].summary;

    expect(summary).toBe('Ada · ROUTINE_SPECIALTY · Routine · Cancelled');
    expect(c.selectedActivityRows()[0].statusLabel).toBe('Cancelled');
  });

  it('QA CX-006: the codes the summaries show are translated in French and Spanish', () => {
    for (const locale of [fr, es]) {
      const labels = locale.PORTAL.ENUM as unknown as Record<string, Record<string, string>>;
      for (const [domain, code] of [
        ['CONSULTATION_URGENCY', 'ROUTINE'],
        ['CONSULTATION_URGENCY', 'URGENT'],
        ['CONSULTATION_STATUS', 'REQUESTED'],
        ['CONSULTATION_STATUS', 'CANCELLED'],
      ]) {
        expect(labels[domain][code]).withContext(`${domain}.${code}`).toBeTruthy();
        expect(labels[domain][code]).not.toBe(code);
      }
    }
  });
  it('renders structured oversight fields and the matching destination in every activity tab', () => {
    dashboard.getSummary.and.returnValue(of(fakeSummary()));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));
    const c = setup();
    const recent = Object.fromEntries(
      c.activityTabs.map((tab) => [
        tab.key,
        [
          {
            id: tab.key,
            patientName: 'Patient A',
            patientFullName: 'Patient A',
            name: 'Catalog A',
            patientMrn: 'MRN-0042',
            hospitalName: 'Hospital B',
            medicationName: 'Medicine A',
            specialtyRequested: 'Cardiology',
            chiefComplaint: 'Review',
            labTestName: 'Test A',
            receivingHospitalName: 'Receiving hospital',
            status: 'ACTIVE',
            createdAt: '2026-11-17T14:00:00Z',
          },
        ],
      ]),
    );
    dashboard.getRecentActivity.and.returnValue(of(bundleWith(recent)));
    c.loadAll();
    const destinations = [
      '/consultations',
      '/admissions',
      '/prescriptions',
      '/lab',
      '/lab-results',
      '/lab-test-config',
      '/treatment-plans',
      '/referrals',
    ];
    c.activityTabs.forEach((tab, index) => {
      c.selectActivityTab(tab.key);
      fixture.detectChanges();
      const row = c.selectedActivityRows()[0];
      expect(row.subject)
        .withContext(tab.key)
        .toBe(tab.key === 'labTestDefinitions' ? 'Catalog A' : 'Patient A');
      expect(row.hospital).withContext(tab.key).toBe('Hospital B');
      expect(row.timestampLabel).withContext(tab.key).toBe('SUPER_ADMIN.ACTIVITY.DATE_createdAt');
      const element: HTMLElement = fixture.nativeElement;
      expect(element.querySelector('.activity-toolbar a')?.getAttribute('href')).toBe(
        destinations[index],
      );
      expect(element.querySelector('.activity-subject')?.textContent).toContain(row.subject);
      expect(element.querySelector('.activity-timestamp .field-label')?.textContent).toContain(
        row.timestampLabel,
      );
    });
  });

  it('keeps audit below activity and requests a larger page without changing the history filters', () => {
    dashboard.getSummary.and.returnValue(of(fakeSummary()));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));
    const c = setup();
    const panels = fixture.nativeElement.querySelectorAll('.workspace-grid > section');
    expect(panels[1].classList.contains('audit-panel')).toBeTrue();
    const first = audits.search.calls.mostRecent().args[0];
    c.expandAudit();
    expect(audits.search.calls.mostRecent().args[0]).toEqual({ ...first, size: 10 });
    c.setAuditFailures(true);
    expect(audits.search.calls.mostRecent().args[0].size).toBe(5);
  });
  it('ignores an older audit response after a filter change', () => {
    dashboard.getSummary.and.returnValue(of(fakeSummary()));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));
    const older = new Subject<import('../services/audit-search.model').AuditSearchPage>();
    audits.search.and.returnValue(older);
    const c = setup();
    rows = [auditEvent('current')] as AuditSearchRow[];
    audits.search.and.callFake(() =>
      of({ content: rows, totalElements: 1, pageSize: 5, pageNumber: 0, totalPages: 1 }),
    );
    c.setAuditFailures(true);
    older.next({ content: [], totalElements: 0, pageSize: 5, pageNumber: 0, totalPages: 0 });
    expect(c.displayedAudit()[0].id).toBe('current');
  });

  it('shows recorded scope and localized targets without empty metadata separators', () => {
    dashboard.getSummary.and.returnValue(of(fakeSummary()));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));
    rows = [
      {
        ...auditEvent('read'),
        roleName: null,
        hospitalName: null,
        resourceName: '/treatment-plans',
      },
    ] as AuditSearchRow[];
    setup();
    const element: HTMLElement = fixture.nativeElement;
    expect(element.querySelector('.audit-scope')?.textContent).toContain(
      'SUPER_ADMIN.AUDIT_OVERSIGHT.SCOPE_UNKNOWN',
    );
    expect(element.querySelector('.audit-event')?.textContent).toContain(
      'SUPER_ADMIN.STAT.TREATMENT_PLANS',
    );
    expect(element.querySelector('.audit-row')?.textContent).not.toContain('? ? ?');
    expect(element.querySelector('details')?.open).toBeFalse();
  });

  it('shows the recorded lab release state without inventing one for an older payload', () => {
    dashboard.getSummary.and.returnValue(of(fakeSummary()));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));
    dashboard.getRecentActivity.and.returnValue(
      of(
        bundleWith({
          labResults: [
            { id: 'released', released: true },
            { id: 'pending', released: false },
            { id: 'unknown' },
          ],
        }),
      ),
    );
    const c = setup();
    c.selectActivityTab('labResults');
    expect(c.selectedActivityRows().map((row) => row.statusKey)).toEqual([
      'LAB_RESULTS.RELEASED',
      'LAB_RESULTS.PENDING_RELEASE',
      '',
    ]);
  });
});
