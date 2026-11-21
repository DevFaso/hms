import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Route } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { DashboardComponent } from './dashboard';
import { routes as appRoutes } from '../app.routes';
import { AuthService } from '../auth/auth.service';
import { IdleService } from '../core/idle.service';
import { NavAccessService } from '../core/nav-access.service';
import { RoleContextService } from '../core/role-context.service';
import { expandRoleEquivalents, roleSatisfies } from '../core/role-equivalence';
import { EmergencyBroadcastService } from '../services/emergency-broadcast.service';
import { NotificationService } from '../services/notification.service';
import { ShellComponent } from '../shell/shell';
import { SHELL_GUARD_ROLES } from '../shell/shell-startup-endpoints';

/**
 * QA r65 D2761 (class guard): every tile, counter, quick action and "view all"
 * link the dashboard renders leads to a page the role that sees it can use.
 *
 * An administrator (ROLE_ADMIN) was offered Rendez-vous, Facturation, Personnel
 * and Départements: routes that admit the role while every request behind them
 * is refused, so the click ended on a 403 page or on an empty page inviting
 * them to create a first department.
 *
 * For each role the real shell is booted (its sidebar is the answer to "can
 * this role use this module": the route's roles AND the backend's
 * permissions), then the real dashboard is rendered on the real route table,
 * and every link on the page is held to two rules:
 *   1. the sidebar lists the module the link belongs to (or it is a top-bar page);
 *   2. the route's own guard admits the role.
 */
describe('Dashboard links (QA r65 D2761 class guard)', () => {
  /** A hospital-admin summary with every section filled, so every card of the admin view renders. */
  const fullSummary = {
    appointments: { today: 4, completed: 2, noShows: 1, cancelled: 0, pending: 1, inProgress: 0 },
    admissions: { active: 3, admittedToday: 1, dischargedToday: 1 },
    consultations: { requested: 2, acknowledged: 1, overdue: 1 },
    staffing: { activeStaff: 12, onShiftToday: 5, staffOnLeaveToday: 1 },
    licenseAlerts: [],
    billing: { overdueInvoices: 2, openBalanceTotal: 15000 },
    invoiceAging: {
      current: { label: 'Current', count: 1, total: 15000 },
      days1to30: { label: '1-30 days', count: 1, total: 2500 },
      days31to60: { label: '31-60 days', count: 0, total: 0 },
      days61to90: { label: '61-90 days', count: 0, total: 0 },
      over90: { label: '90+ days', count: 0, total: 0 },
    },
    recentAuditEvents: [
      {
        id: 'e1',
        eventType: 'DATA_CREATE',
        status: 'SUCCESS',
        entityType: 'PATIENT',
        resourceName: 'Patient',
        eventTimestamp: '2026-11-20T08:00:00',
      },
    ],
    leaveMetrics: { onLeaveToday: 1, upcomingLeaveNext7Days: 2 },
    writeOffs: { cancelledCount: 1, cancelledTotal: 500, writtenOffCount: 0, writtenOffTotal: 0 },
    auditTrend: [{ date: '2026-11-20', count: 3 }],
  };

  /** Every `data.roles` list on the chain of routes that `path` matches; undefined when nothing matches. */
  function guardChain(path: string, table: Route[] = appRoutes): string[][] | undefined {
    const segments = path.split('/').filter(Boolean);
    return walk(table, segments);
  }

  function walk(table: Route[], segments: string[]): string[][] | undefined {
    for (const route of table) {
      const declared = (route.path ?? '').split('/').filter(Boolean);
      if (declared.length > segments.length) continue;
      if (!declared.every((s, i) => s.startsWith(':') || s === segments[i])) continue;
      const roles = route.data?.['roles'];
      const own: string[][] = Array.isArray(roles) && roles.length > 0 ? [roles as string[]] : [];
      const rest = segments.slice(declared.length);
      if (rest.length === 0 && declared.length > 0) return own;
      if (route.children) {
        const child = walk(route.children, rest);
        if (child) return [...own, ...child];
      }
    }
    return undefined;
  }

  /** The links of the dashboard `role` sees, and the dead ones among them, each with why it is dead. */
  function scan(role: string): { dead: string[]; paths: string[] } {
    const roles = [role];
    const authStub = jasmine.createSpyObj<AuthService>('AuthService', [
      'getUserProfile',
      'hasAnyRole',
      'getSubject',
      'getRoles',
      'getToken',
      'getUserId',
      'isAuthenticated',
      'isExpired',
      'logout',
    ]);
    authStub.isAuthenticated.and.returnValue(true);
    authStub.isExpired.and.returnValue(false);
    authStub.getUserProfile.and.returnValue({
      id: 'u1',
      username: 'someone',
      firstName: 'Test',
      lastName: 'Utilisateur',
      roles,
      staffId: 's1',
      active: true,
    } as never);
    authStub.getRoles.and.returnValue(roles);
    authStub.getToken.and.returnValue('fake-token');
    authStub.getUserId.and.returnValue('u1');
    authStub.hasAnyRole.and.callFake((rs: string[]) =>
      rs.some((r) => expandRoleEquivalents(roles).includes(r)),
    );
    authStub.getSubject.and.returnValue(null);
    Object.defineProperty(authStub, 'currentProfile', { value: () => null });
    Object.defineProperty(authStub, 'getHospitalId', { value: () => 'h1' });

    TestBed.configureTestingModule({
      imports: [ShellComponent, DashboardComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter(appRoutes),
        { provide: AuthService, useValue: authStub },
        {
          provide: IdleService,
          useValue: { start: () => undefined, stop: () => undefined, locked: () => false },
        },
      ],
    });
    const notif = TestBed.inject(NotificationService);
    spyOn(notif, 'connectWebSocket');
    spyOn(notif, 'disconnectWebSocket');
    const broadcast = TestBed.inject(EmergencyBroadcastService);
    spyOn(broadcast, 'connect');
    spyOn(broadcast, 'disconnect');
    const roleContext = TestBed.inject(RoleContextService);
    roleContext.setRoles(roles);
    roleContext.activeRole = role;
    roleContext.markSessionResolved();

    // The real shell: its constructor effects build the sidebar and publish it.
    TestBed.createComponent(ShellComponent);
    TestBed.tick();
    const navAccess = TestBed.inject(NavAccessService);
    expect(navAccess.snapshot())
      .withContext(`${role}: the shell published no sidebar, the guard reads nothing`)
      .not.toBeNull();

    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges(); // ngOnInit; then pin a fully loaded page
    const page = fixture.componentInstance;
    page.hospitalAdminSummary.set(fullSummary as never);
    page.loading.set(false);
    fixture.detectChanges();

    const dead: string[] = [];
    const paths: string[] = [];
    const anchors = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLAnchorElement>('a[href]'),
    );
    for (const anchor of anchors) {
      const path = (anchor.getAttribute('href') ?? '').split(/[?#]/)[0];
      if (!path.startsWith('/')) continue;
      paths.push(path);
      const label = (anchor.textContent ?? '').replace(/\s+/g, ' ').trim().slice(0, 40);
      if (!navAccess.canOpen(path)) {
        dead.push(`${path} (« ${label} »): no sidebar entry for this role`);
        continue;
      }
      const chain = guardChain(path);
      if (!chain) {
        dead.push(`${path} (« ${label} »): no such route`);
      } else if (!chain.every((required) => roleSatisfies(required, role))) {
        dead.push(`${path} (« ${label} »): the route guard refuses this role`);
      }
    }
    fixture.destroy();
    return { dead, paths };
  }

  afterEach(() => TestBed.resetTestingModule());

  for (const role of SHELL_GUARD_ROLES) {
    it(`${role}: every dashboard link leads to a page the role can use`, () => {
      expect(scan(role).dead).withContext(role).toEqual([]);
    });
  }

  it('the organisation admin is offered its two pages, and no patient or clinical module', () => {
    // The guard above must not pass by having removed everything.
    const { dead, paths } = scan('ROLE_ADMIN');
    expect(dead).toEqual([]);
    expect(paths).toContain('/users');
    expect(paths).toContain('/admin-assignments');
    // Platform admin rules 2026-11-20, rule 1b: no patient, no audit trail, no clinical page.
    for (const refused of [
      '/patients',
      '/audit-logs',
      '/imaging',
      '/reception',
      '/appointments',
      '/billing',
      '/staff',
      '/departments',
    ]) {
      expect(paths).withContext(refused).not.toContain(refused);
    }
  });

  it('a hospital admin, who has those modules, keeps every one of those links', () => {
    const { dead, paths } = scan('ROLE_HOSPITAL_ADMIN');
    expect(dead).toEqual([]);
    for (const own of ['/appointments', '/billing', '/staff', '/departments', '/patients']) {
      expect(paths).withContext(own).toContain(own);
    }
    // QA r65 D2778: « Demander des examens d'imagerie » is not an administrator's quick action.
    expect(paths).not.toContain('/imaging');
  });
});
