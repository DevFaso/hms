import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { SuperAdminComponent } from './super-admin';
import {
  DashboardService,
  RecentAuditEvent,
  SuperAdminRecentItem,
  SuperAdminSummary,
} from '../services/dashboard.service';
import { PlatformService, PlatformSummary } from '../services/platform.service';

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

  function stubAllRecent(empty: SuperAdminRecentItem[] = []): void {
    dashboard.getRecentConsultations.and.returnValue(of(empty));
    dashboard.getRecentLabOrders.and.returnValue(of(empty));
    dashboard.getRecentLabResults.and.returnValue(of(empty));
    dashboard.getRecentLabTestDefinitions.and.returnValue(of(empty));
    dashboard.getRecentAdmissions.and.returnValue(of(empty));
    dashboard.getRecentPrescriptions.and.returnValue(of(empty));
    dashboard.getRecentTreatmentPlans.and.returnValue(of(empty));
    dashboard.getRecentReferrals.and.returnValue(of(empty));
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
      'getRecentConsultations',
      'getRecentLabOrders',
      'getRecentLabResults',
      'getRecentLabTestDefinitions',
      'getRecentAdmissions',
      'getRecentPrescriptions',
      'getRecentTreatmentPlans',
      'getRecentReferrals',
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

  it('fetches all 8 recent activity endpoints on init', () => {
    dashboard.getSummary.and.returnValue(of(fakeSummary()));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));

    setup();

    expect(dashboard.getRecentConsultations).toHaveBeenCalledTimes(1);
    expect(dashboard.getRecentLabOrders).toHaveBeenCalledTimes(1);
    expect(dashboard.getRecentLabResults).toHaveBeenCalledTimes(1);
    expect(dashboard.getRecentLabTestDefinitions).toHaveBeenCalledTimes(1);
    expect(dashboard.getRecentAdmissions).toHaveBeenCalledTimes(1);
    expect(dashboard.getRecentPrescriptions).toHaveBeenCalledTimes(1);
    expect(dashboard.getRecentTreatmentPlans).toHaveBeenCalledTimes(1);
    expect(dashboard.getRecentReferrals).toHaveBeenCalledTimes(1);
  });

  it('renders selected activity tab rows from the response', () => {
    dashboard.getSummary.and.returnValue(of(fakeSummary()));
    platform.getSummary.and.returnValue(of(fakePlatformSummary()));
    dashboard.getRecentPrescriptions.and.returnValue(
      of([
        {
          id: 'aaaa1111-2222-3333-4444-555555555555',
          medicationName: 'Amoxicillin',
          patientName: 'Ada',
          createdAt: '2026-05-02T10:00:00Z',
        },
      ] as SuperAdminRecentItem[]),
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
});
