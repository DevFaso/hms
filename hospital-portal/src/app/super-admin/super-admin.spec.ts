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
    dashboard = jasmine.createSpyObj<DashboardService>('DashboardService', ['getSummary']);
    platform = jasmine.createSpyObj<PlatformService>('PlatformService', ['getSummary']);
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
});
