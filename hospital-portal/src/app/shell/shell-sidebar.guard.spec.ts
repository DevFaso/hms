import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Route } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { ShellComponent } from './shell';
import { routes as appRoutes } from '../app.routes';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { IdleService } from '../core/idle.service';
import { EmergencyBroadcastService } from '../services/emergency-broadcast.service';
import { NotificationService } from '../services/notification.service';
import { expandRoleEquivalents } from '../core/role-equivalence';
import { NavAccessService } from '../core/nav-access.service';
import {
  CLINICAL_WORK_GROUPS,
  SUPER_ADMIN_HIDDEN_WORKSTATIONS,
  SUPER_ADMIN_KEPT_WORK_QUEUES,
  navGroupForRoute,
} from './nav-groups';
import { SHELL_GUARD_ROLES } from './shell-startup-endpoints';

interface NavItem {
  route: string;
  label: string;
}

interface Booted {
  /** Every entry of the role's nav model. */
  model: string[];
  /** What the sidebar lists (no search typed). */
  listed: string[];
  /** What the sidebar lists while `query` is typed in its search box. */
  search: (query: string) => string[];
  /** What the shell published for the pages that link to other modules. */
  published: NavAccessService;
}

/**
 * QA r65 D2760 / D2777 (class guards): what a sidebar lists.
 *
 * Boots the real shell once per role (real permission map, real nav builder;
 * only the sockets and the idle timer are stubbed), as
 * `shell-startup-endpoints.spec.ts` does, and reads the sidebar it renders.
 */
describe('Sidebar entries (QA r65 D2760 / D2777 class guards)', () => {
  function bootAs(role: string): Booted {
    const roles = [role];
    const authStub = jasmine.createSpyObj<AuthService>('AuthService', [
      'getUserProfile',
      'hasAnyRole',
      'getSubject',
      'getRoles',
      'isAuthenticated',
      'logout',
    ]);
    authStub.isAuthenticated.and.returnValue(true);
    authStub.getUserProfile.and.returnValue(null);
    authStub.getRoles.and.returnValue(roles);
    authStub.hasAnyRole.and.callFake((rs: string[]) =>
      rs.some((r) => expandRoleEquivalents(roles).includes(r)),
    );
    authStub.getSubject.and.returnValue(null);
    Object.defineProperty(authStub, 'currentProfile', { value: () => null });

    TestBed.configureTestingModule({
      imports: [ShellComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
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

    const fixture = TestBed.createComponent(ShellComponent);
    const shell = fixture.componentInstance as unknown as {
      navItems: () => NavItem[];
      navGroups: () => { entries: { item: NavItem }[] }[];
      navSearch: { set: (value: string) => void };
    };
    // The constructor's effects build the nav; ngOnInit (sockets, polling) is never run.
    TestBed.tick();

    const listedNow = () => shell.navGroups().flatMap((g) => g.entries.map((e) => e.item.route));
    return {
      model: shell.navItems().map((item) => item.route),
      listed: listedNow(),
      search: (query: string) => {
        shell.navSearch.set(query);
        const found = listedNow();
        shell.navSearch.set('');
        return found;
      },
      published: TestBed.inject(NavAccessService),
    };
  }

  afterEach(() => TestBed.resetTestingModule());

  /** The route-table entry a sidebar route opens, by walking the real routes. */
  function routeFor(path: string, table: Route[] = appRoutes): Route | undefined {
    const segments = path.split('/').filter(Boolean).join('/');
    for (const route of table) {
      if (route.path === segments && (route.loadComponent || route.redirectTo)) return route;
      if (route.children && (route.path ?? '') === '') {
        const found = routeFor(path, route.children);
        if (found) return found;
      }
    }
    return undefined;
  }

  /** The page a route loads, as its source says it (`import('./x/y').then(m => m.Z)`). */
  function pageOf(path: string): string {
    return String(routeFor(path)?.loadComponent ?? path).replace(/\s+/g, '');
  }

  // ── D2777: one page, one entry ──────────────────────────────────────

  for (const role of SHELL_GUARD_ROLES) {
    it(`${role}: no two sidebar entries open the same page`, () => {
      const { listed } = bootAs(role);
      expect(listed.length)
        .withContext('the sidebar lists nothing: the guard reads nothing')
        .toBeGreaterThan(0);
      const byPage = new Map<string, string[]>();
      for (const route of new Set(listed)) {
        byPage.set(pageOf(route), [...(byPage.get(pageOf(route)) ?? []), route]);
      }
      const twice = [...byPage.values()].filter((routesOfPage) => routesOfPage.length > 1);
      expect(twice)
        .withContext('each of these pages is listed under more than one route (QA r65 D2777)')
        .toEqual([]);
    });
  }

  it('the super-admin has one entry per platform page, at the address the rewrite guard lands on', () => {
    const { listed } = bootAs('ROLE_SUPER_ADMIN');
    // Feature flags and analytics were each reachable by two routes.
    expect(listed).toContain('/super-admin/feature-flags');
    expect(listed).not.toContain('/feature-flags');
    expect(listed).toContain('/super-admin/analytics');
    expect(listed).not.toContain('/analytics');
    // The two audit screens are two tabs of one entry (AuditTabsComponent).
    expect(listed).toContain('/super-admin/audit-logs');
    expect(listed).not.toContain('/super-admin/audit-search');
    expect(listed).not.toContain('/audit-logs');
  });

  // ── D2777: no clinical workstation in the super-admin's sidebar ─────

  it('the super-admin sidebar lists no clinical workstation, and the search still finds each one', () => {
    const superAdmin = bootAs('ROLE_SUPER_ADMIN');
    for (const route of SUPER_ADMIN_HIDDEN_WORKSTATIONS) {
      expect(superAdmin.listed).withContext(route).not.toContain(route);
    }
    // Not removed, only not listed: the sidebar search finds a module by its name.
    expect(superAdmin.search('emar')).toContain('/emar');
    expect(superAdmin.search('prescriptions')).toContain('/prescriptions');
    // And the page a hidden entry opens still exists.
    for (const route of SUPER_ADMIN_HIDDEN_WORKSTATIONS) {
      expect(routeFor(route)).withContext(route).toBeDefined();
    }
  });

  it('every workstation left out is one a hospital admin has no entry for either', () => {
    const hospitalAdmin = bootAs('ROLE_HOSPITAL_ADMIN');
    for (const route of SUPER_ADMIN_HIDDEN_WORKSTATIONS) {
      expect(hospitalAdmin.model)
        .withContext(
          `${route} is a hospital admin's module: it is not a clinician-only workstation`,
        )
        .not.toContain(route);
    }
  });

  it('every clinical-group entry a super-admin has that a hospital admin lacks is left out, or a recorded exception', () => {
    const superAdmin = bootAs('ROLE_SUPER_ADMIN').model;
    TestBed.resetTestingModule();
    const hospitalAdmin = new Set(bootAs('ROLE_HOSPITAL_ADMIN').model);
    const unexplained = superAdmin
      .filter((route) => CLINICAL_WORK_GROUPS.includes(navGroupForRoute(route)))
      .filter((route) => !hospitalAdmin.has(route))
      .filter(
        (route) =>
          !SUPER_ADMIN_HIDDEN_WORKSTATIONS.has(route) && !(route in SUPER_ADMIN_KEPT_WORK_QUEUES),
      );
    expect(unexplained)
      .withContext(
        'a clinical module only the super-admin lists: add it to SUPER_ADMIN_HIDDEN_WORKSTATIONS, ' +
          'or to SUPER_ADMIN_KEPT_WORK_QUEUES with the reason they work in it',
      )
      .toEqual([]);
  });

  // ── D2760: one landing page for an administrator ────────────────────

  it('an administrator (ROLE_ADMIN) lands on the dashboard and has no second landing entry', () => {
    const { listed } = bootAs('ROLE_ADMIN');
    expect(listed).toContain('/dashboard');
    expect(listed).not.toContain('/admin');
    const admin = routeFor('/admin');
    expect(admin?.redirectTo).toBe('dashboard');
    expect(admin?.loadComponent).toBeUndefined();
  });

  // ── Platform admin rules 2026-11-20, rule 1b: no patient or clinical page ──

  it('class guard: ROLE_ADMIN, the organisation admin, is admitted by a recorded list of routes only', () => {
    // A route that admits the role is a decision: record it here with the reason it shows no patient.
    const recorded = new Set([
      'users', // the staff directory and « Inscrire »
      'admin-assignments', // the staff assignments of its organisation
      'staff', // listed for the role since the 2026-08-23 role audit; the server refuses it (no sidebar entry)
      'scheduling', // same
      'departments', // same
    ]);
    const admitting: string[] = [];
    const visit = (table: Route[], prefix: string) => {
      for (const route of table) {
        const path = [prefix, route.path].filter(Boolean).join('/');
        const roles = route.data?.['roles'] as string[] | undefined;
        if (roles?.includes('ROLE_ADMIN')) admitting.push(path);
        if (route.children) visit(route.children, path);
      }
    };
    visit(appRoutes, '');
    expect(admitting.length).toBeGreaterThan(0);
    expect(admitting.filter((path) => !recorded.has(path)))
      .withContext(
        'a route admits ROLE_ADMIN, the organisation admin, who reads no patient or clinical data: ' +
          'remove the role (a hospital’s own administrator is ROLE_HOSPITAL_ADMIN) or record why here',
      )
      .toEqual([]);
  });

  it('the organisation admin’s sidebar lists its pages and no patient or clinical module', () => {
    const { listed } = bootAs('ROLE_ADMIN');
    expect(listed).toContain('/users');
    expect(listed).toContain('/admin-assignments');
    for (const refused of [
      '/patients',
      '/audit-logs',
      '/imaging',
      '/reception',
      '/patient-tracker',
      '/mortality',
    ]) {
      expect(listed).withContext(refused).not.toContain(refused);
    }
  });

  // ── D2761: the shell says what the role can open ────────────────────

  it('publishes the sidebar, so a page linking to another module can ask before offering the link', () => {
    const { published, model } = bootAs('ROLE_ADMIN');
    expect(published.snapshot()).toEqual(new Set(model));
    // The organisation admin's own modules, and anything under them.
    expect(published.canOpen('/users')).toBeTrue();
    expect(published.canOpen('/admin-assignments')).toBeTrue();
    // The modules whose every request is refused to the role (QA r65 D2761), and, since the
    // platform admin rules of 2026-11-20 (rule 1b), every patient and clinical page.
    for (const refused of [
      '/patients',
      '/patients/new',
      '/audit-logs',
      '/imaging',
      '/reception',
      '/patient-tracker',
      '/mortality',
      '/appointments',
      '/billing',
      '/staff',
      '/departments',
      '/admissions',
    ]) {
      expect(published.canOpen(refused)).withContext(refused).toBeFalse();
    }
    // The top bar's pages are never in the sidebar and always open.
    expect(published.canOpen('/notifications')).toBeTrue();
    expect(published.canOpen('/chat')).toBeTrue();
  });
});
