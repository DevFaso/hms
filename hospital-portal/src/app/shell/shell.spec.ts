import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { ShellComponent } from './shell';
import { AuthService } from '../auth/auth.service';
import { PermissionService } from '../core/permission.service';
import { RoleContextService } from '../core/role-context.service';
import { NotificationService } from '../services/notification.service';
import { ImpersonationService } from '../services/impersonation.service';
import { IdleService } from '../core/idle.service';
import { EmergencyBroadcastService } from '../services/emergency-broadcast.service';
import { DowntimeService } from '../services/downtime.service';
import { NavOrderService } from './nav-order.service';
import { navGroupForRoute } from './nav-groups';

interface NavItem {
  route: string;
  label: string;
}

/**
 * MVP-5: super-admin surface consolidation. The shell side-nav must drop
 * the Dashboard and Administration entries when the active role is
 * ROLE_SUPER_ADMIN — the Control Tower at /super-admin is the landing
 * page. Other roles still see Dashboard, and ADMIN / HOSPITAL_ADMIN
 * still see Administration.
 */
describe('ShellComponent — MVP-5 nav role filter', () => {
  function createComponent(opts: {
    activeRole: string | null;
    roles: string[];
    wildcardPermission: boolean;
    /** When set, hasPermission answers from this list instead of the wildcard. */
    permissions?: string[];
  }): { items: NavItem[]; component: ShellComponent } {
    const authStub = jasmine.createSpyObj<AuthService>('AuthService', [
      'getUserProfile',
      'hasAnyRole',
      'getSubject',
      'formatRole',
      'logout',
    ]);
    authStub.getUserProfile.and.returnValue(null);
    authStub.hasAnyRole.and.callFake((rs: string[]) => rs.some((r) => opts.roles.includes(r)));
    authStub.getSubject.and.returnValue(null);
    authStub.formatRole.and.callFake((r: string) => r);
    Object.defineProperty(authStub, 'currentProfile', { value: () => null });

    // Wildcard owners (super admin / admin) get every permission; other
    // roles get none — keeps the test focused on the nav role-filter logic.
    // Tests that need a realistic per-role set pass `permissions` instead.
    const permStub: Partial<PermissionService> = {
      hasPermission: (p: string) =>
        opts.permissions ? opts.permissions.includes(p) : opts.wildcardPermission,
      hasAnyPermission: (...ps: string[]) =>
        opts.permissions ? ps.some((p) => opts.permissions!.includes(p)) : opts.wildcardPermission,
    };

    const notifStub = jasmine.createSpyObj<NotificationService>('NotificationService', [
      'connectWebSocket',
      'disconnectWebSocket',
      'getNotifications',
      'getNotificationStream',
      'getReadStream',
      'getAllReadStream',
      'markAsReadAndNotify',
      'markAllReadAndNotify',
    ]);
    notifStub.getNotifications.and.returnValue(
      of({ content: [], totalElements: 0, totalPages: 0, size: 10, number: 0 }),
    );
    notifStub.getNotificationStream.and.returnValue(of() as never);
    notifStub.getReadStream.and.returnValue(of() as never);
    notifStub.getAllReadStream.and.returnValue(of() as never);

    const impersonationStub = jasmine.createSpyObj<ImpersonationService>('ImpersonationService', [
      'refreshActive',
    ]);
    impersonationStub.refreshActive.and.returnValue(of(null) as never);

    const idleStub = jasmine.createSpyObj<IdleService>('IdleService', ['start', 'stop']);

    TestBed.configureTestingModule({
      imports: [ShellComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AuthService, useValue: authStub },
        { provide: PermissionService, useValue: permStub },
        { provide: NotificationService, useValue: notifStub },
        { provide: ImpersonationService, useValue: impersonationStub },
        { provide: IdleService, useValue: idleStub },
      ],
    });

    const roleContext = TestBed.inject(RoleContextService);
    roleContext.setRoles(opts.roles);
    roleContext.activeRole = opts.activeRole;

    const fixture = TestBed.createComponent(ShellComponent);
    // Read the private baseNavItems computed without running ngOnInit (which
    // pulls in notification websockets and idle timers).
    const items = (
      fixture.componentInstance as unknown as { baseNavItems: () => NavItem[] }
    ).baseNavItems();
    // The component comes back too so the grouping and search tests below can
    // drive the real computeds without rendering the whole shell.
    return { items, component: fixture.componentInstance };
  }

  afterEach(() => TestBed.resetTestingModule());

  it('omits Dashboard and Administration when activeRole is ROLE_SUPER_ADMIN', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_SUPER_ADMIN',
      roles: ['ROLE_SUPER_ADMIN'],
      wildcardPermission: true,
    });

    const routes = items.map((i) => i.route);
    expect(routes).not.toContain('/dashboard');
    expect(routes).not.toContain('/admin');
    // Super-admin Control Tower should still be in the nav.
    expect(routes).toContain('/super-admin');
  });

  it('keeps Administration for ROLE_ADMIN active role (non-super)', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_ADMIN',
      roles: ['ROLE_ADMIN'],
      wildcardPermission: true,
    });

    const routes = items.map((i) => i.route);
    expect(routes).toContain('/dashboard');
    expect(routes).toContain('/admin');
    expect(routes).not.toContain('/super-admin');
  });

  it('keeps Dashboard for non-wildcard roles (e.g. ROLE_DOCTOR)', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_DOCTOR',
      roles: ['ROLE_DOCTOR'],
      wildcardPermission: false,
    });

    const routes = items.map((i) => i.route);
    expect(routes).toContain('/dashboard');
    expect(routes).not.toContain('/admin');
    expect(routes).not.toContain('/super-admin');
  });

  // PR #225 review (Copilot): a multi-role super admin who picked a non-super
  // active role at login must NOT see admin-tier sidebar entries — clicking
  // them would trigger a 403 from RoleGuard's active-role check. Before the
  // fix, the gate used `permissions.hasPermission('*')` which reads JWT roles
  // (always true while ROLE_SUPER_ADMIN is in the token) and bypassed the
  // active-role choice.
  it('hides admin-tier entries for multi-role SUPER_ADMIN+DOCTOR who picked DOCTOR active', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_DOCTOR',
      roles: ['ROLE_SUPER_ADMIN', 'ROLE_DOCTOR'],
      wildcardPermission: true,
    });

    const routes = items.map((i) => i.route);
    expect(routes).not.toContain('/admin');
    expect(routes).not.toContain('/organizations');
    expect(routes).not.toContain('/users');
    expect(routes).not.toContain('/roles');
    expect(routes).not.toContain('/platform');
    expect(routes).not.toContain('/super-admin');
    expect(routes).toContain('/dashboard');
  });

  // The /refills queue and its backend were complete, but nothing in the
  // portal linked to them — a prescriber notified about a refill had no way
  // to reach the approval screen. These pin the sidebar entry in place.

  it('shows the refill queue to a prescriber', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_DOCTOR',
      roles: ['ROLE_DOCTOR'],
      wildcardPermission: false,
    });

    expect(items.map((i) => i.route)).toContain('/refills');
  });

  it('shows the refill queue to a pharmacist', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_PHARMACIST',
      roles: ['ROLE_PHARMACIST'],
      wildcardPermission: false,
    });

    expect(items.map((i) => i.route)).toContain('/refills');
  });

  it('reaches the duplicate-patient panel from the sidebar', () => {
    // The EMPI panel and its whole backend shipped with no click anywhere
    // reaching them — an admin had to hand-type the URL. Same defect class as
    // the refill queue, which is why both are pinned here.
    const { items } = createComponent({
      activeRole: 'ROLE_RECEPTIONIST',
      roles: ['ROLE_RECEPTIONIST'],
      wildcardPermission: false,
    });

    expect(items.map((i) => i.route)).toContain('/reception/empi-candidates');
  });

  it('hides the duplicate-patient panel from roles outside its route guard', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_PHARMACIST',
      roles: ['ROLE_PHARMACIST'],
      wildcardPermission: false,
    });

    expect(items.map((i) => i.route)).not.toContain('/reception/empi-candidates');
  });

  // The accountant screenshot bug (2026-08-23): the sidebar offered Patients
  // and the click landed on the 403 page — the nav item was permission-gated
  // while the route guard is role-gated, and the two vocabularies disagreed
  // for ten roles. The item now carries the guard's role list too.
  it('accountant sees Billing but not Patients — nav mirrors the route guard', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_ACCOUNTANT',
      roles: ['ROLE_ACCOUNTANT'],
      wildcardPermission: false,
      permissions: [
        'View Dashboard',
        'View Billing',
        'View Billing Summary',
        'Record Payment',
        'View Billing Reports',
        'View Notifications',
      ],
    });

    const routes = items.map((i) => i.route);
    expect(routes).toContain('/billing');
    expect(routes).not.toContain('/patients');
    // Chat and Announcements are still reachable for this role — they moved
    // to the topbar, so they are no longer nav rows. Covered below.
    expect(routes).not.toContain('/chat');
    expect(routes).not.toContain('/announcements');
  });

  it('keeps Patients for a role inside the /patients route guard', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_NURSE',
      roles: ['ROLE_NURSE'],
      wildcardPermission: false,
      permissions: ['View Patient Records'],
    });

    expect(items.map((i) => i.route)).toContain('/patients');
  });

  // 2026-08-23 role audit: the same dead-nav defect class existed for ten
  // more roles. These pin the guard-mirroring role lists added in response.

  it('midwife sees the clinical entries whose guards now admit her', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_MIDWIFE',
      roles: ['ROLE_MIDWIFE'],
      wildcardPermission: false,
      permissions: [
        'View Prescriptions',
        'View Imaging Studies',
        'Create Treatment Plans',
        'Create Referrals',
      ],
    });

    const routes = items.map((i) => i.route);
    expect(routes).toContain('/prescriptions');
    expect(routes).toContain('/imaging');
    expect(routes).toContain('/treatment-plans');
    expect(routes).toContain('/referrals');
  });

  it('radiologist no longer sees dead Appointments/Lab entries', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_RADIOLOGIST',
      roles: ['ROLE_RADIOLOGIST'],
      wildcardPermission: false,
      permissions: ['View Appointments', 'View Lab', 'View Imaging Studies', 'View Notifications'],
    });

    const routes = items.map((i) => i.route);
    expect(routes).not.toContain('/appointments');
    expect(routes).not.toContain('/lab');
    expect(routes).not.toContain('/lab-results');
    expect(routes).toContain('/imaging');
    // Chat remains open to every authenticated user (decision C3); it is
    // reached from the topbar now rather than the side-nav.
    expect(routes).not.toContain('/chat');
  });

  it('pharmacy verifier reaches Dispensing and Stock Routing but not Prescriptions', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_PHARMACY_VERIFIER',
      roles: ['ROLE_PHARMACY_VERIFIER'],
      wildcardPermission: false,
      permissions: ['View Prescriptions'],
    });

    const routes = items.map((i) => i.route);
    expect(routes).toContain('/pharmacy/dispensing');
    expect(routes).toContain('/pharmacy/stock-routing');
    expect(routes).not.toContain('/prescriptions');
  });

  it('claims reviewer reaches Pharmacy Claims — its entire purpose', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_CLAIMS_REVIEWER',
      roles: ['ROLE_CLAIMS_REVIEWER'],
      wildcardPermission: false,
      permissions: ['View Billing'],
    });

    const routes = items.map((i) => i.route);
    expect(routes).toContain('/pharmacy/claims');
    expect(routes).not.toContain('/billing');
  });

  // Role audit decision C2: physicians/surgeons are doctor-equivalent —
  // every nav gate naming ROLE_DOCTOR admits them.
  it('physician sees the doctor nav entries via role equivalence', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_PHYSICIAN',
      roles: ['ROLE_PHYSICIAN'],
      wildcardPermission: false,
      permissions: ['View Appointments', 'Create Encounters', 'View Prescriptions'],
    });

    const routes = items.map((i) => i.route);
    expect(routes).toContain('/appointments');
    expect(routes).toContain('/encounters');
    expect(routes).toContain('/prescriptions');
  });

  // Role audit decision C1: ADMIN is back-office operations, not platform IT.
  it('admin sees Users/Administration/Patients/Audit Logs but no platform-IT entries', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_ADMIN',
      roles: ['ROLE_ADMIN'],
      wildcardPermission: false,
      permissions: [
        'View Dashboard',
        'View Patient Records',
        'View Audit Logs',
        'View Notifications',
      ],
    });

    const routes = items.map((i) => i.route);
    expect(routes).toContain('/users');
    expect(routes).toContain('/admin');
    expect(routes).toContain('/patients');
    expect(routes).toContain('/audit-logs');
    expect(routes).not.toContain('/organizations');
    expect(routes).not.toContain('/roles');
    expect(routes).not.toContain('/platform');
    expect(routes).not.toContain('/analytics');
    expect(routes).not.toContain('/feature-flags');
    expect(routes).not.toContain('/bed-management');
  });

  // Role audit decision C6: lab leadership reads the audit trail.
  it('quality manager reaches Audit Logs', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_QUALITY_MANAGER',
      roles: ['ROLE_QUALITY_MANAGER'],
      wildcardPermission: false,
      permissions: ['View Audit Logs'],
    });

    expect(items.map((i) => i.route)).toContain('/audit-logs');
  });

  it('nurse reaches the eMAR from the sidebar', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_NURSE',
      roles: ['ROLE_NURSE'],
      wildcardPermission: false,
      permissions: [],
    });

    expect(items.map((i) => i.route)).toContain('/emar');
  });

  it('reaches the duplicate-patient panel as a NURSE with no reception role', () => {
    // The regression the receptionist test above could never catch: the entry
    // used to be pushed inside a receptionist/admin-only block, so its own
    // roles list was never consulted for a nurse or doctor — they were in the
    // route guard and locked out of the sidebar (found 2026-08-21).
    const { items } = createComponent({
      activeRole: 'ROLE_NURSE',
      roles: ['ROLE_NURSE'],
      wildcardPermission: false,
    });

    expect(items.map((i) => i.route)).toContain('/reception/empi-candidates');
  });

  it('reaches the duplicate-patient panel as a DOCTOR with no reception role', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_DOCTOR',
      roles: ['ROLE_DOCTOR'],
      wildcardPermission: false,
    });

    expect(items.map((i) => i.route)).toContain('/reception/empi-candidates');
  });

  it('hides the refill queue from roles that cannot decide a refill', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_RECEPTIONIST',
      roles: ['ROLE_RECEPTIONIST'],
      wildcardPermission: false,
    });

    expect(items.map((i) => i.route)).not.toContain('/refills');
  });

  it('shows patient parity nav entries on patient active role', () => {
    const { items } = createComponent({
      activeRole: 'ROLE_PATIENT',
      roles: ['ROLE_PATIENT'],
      wildcardPermission: false,
    });

    const routes = items.map((i) => i.route);
    expect(routes).toContain('/my-pharmacy-invoices');
    expect(routes).toContain('/my-care-team');
    expect(routes).toContain('/my-family-access');
    expect(routes).toContain('/my-documents');
    // Notifications is no longer a patient nav row — it moved to the topbar
    // bell, which serves patients the same rows /my-notifications did (both
    // endpoints call notificationService.getNotificationsForUser). Neither
    // spelling should appear in the side-nav.
    expect(routes).not.toContain('/my-notifications');
    expect(routes).not.toContain('/notifications');
  });

  // ── Grouping and module search ───────────────────────────────────────

  it('files every nav entry a role can reach under a real group', () => {
    // The enforcement. A route missing from NAV_GROUP_BY_ROUTE still renders
    // — under "More" — rather than vanishing, because a nav entry that
    // disappears for want of one line in a lookup table is the
    // built-but-unreachable failure this codebase keeps producing. That
    // fallback is a safety net, not a place for things to live, so anything
    // landing in it fails here.
    //
    // Super-admin with wildcard permissions plus the patient nav together
    // reach every entry the shell can build.
    // TestBed allows one configuration per instantiation, so each pass resets
    // before the next builds its own component.
    const staff = createComponent({
      activeRole: 'ROLE_SUPER_ADMIN',
      roles: ['ROLE_SUPER_ADMIN'],
      wildcardPermission: true,
    }).items;
    TestBed.resetTestingModule();
    const admin = createComponent({
      activeRole: 'ROLE_ADMIN',
      roles: ['ROLE_ADMIN'],
      wildcardPermission: true,
    }).items;
    TestBed.resetTestingModule();
    const patient = createComponent({
      activeRole: 'ROLE_PATIENT',
      roles: ['ROLE_PATIENT'],
      wildcardPermission: false,
    }).items;

    const ungrouped = [...staff, ...admin, ...patient]
      .map((i) => i.route)
      .filter((route) => navGroupForRoute(route) === 'OTHER');

    expect(ungrouped)
      .withContext(
        `these routes have no entry in NAV_GROUP_BY_ROUTE and fell back to "More": ${[
          ...new Set(ungrouped),
        ].join(', ')}`,
      )
      .toEqual([]);
  });

  it('groups the nav and drops groups the role holds nothing in', () => {
    const { items, component } = createComponent({
      activeRole: 'ROLE_PATIENT',
      roles: ['ROLE_PATIENT'],
      wildcardPermission: false,
    });
    // navItems is filled by a constructor effect, and these tests skip
    // detectChanges on purpose (ngOnInit opens websockets and idle timers).
    // Seeding it drives the grouping computed directly.
    component.navItems.set(items as never);

    const ids = component.navGroups().map((g) => g.id);
    expect(ids).toContain('MY_HEALTH');
    // A patient holds no staff modules, so those headings must not render as
    // titles over empty space.
    expect(ids).not.toContain('PHARMACY');
    expect(ids).not.toContain('PLATFORM');
    expect(component.navGroups().every((g) => g.entries.length > 0)).toBeTrue();
  });

  it('keeps flatIndex pointing at the real position in navItems', () => {
    // Reorder persists a flat route order, so the handlers must be given the
    // flat index. Handing them the template's group-relative $index would
    // scramble the saved order the first time anyone dragged anything —
    // silently, and only for users who had reordered.
    const { items, component } = createComponent({
      activeRole: 'ROLE_ADMIN',
      roles: ['ROLE_ADMIN'],
      wildcardPermission: true,
    });
    component.navItems.set(items as never);

    const flat = component.navItems();
    for (const group of component.navGroups()) {
      for (const entry of group.entries) {
        expect(flat[entry.flatIndex]?.route).toBe(entry.item.route);
      }
    }
  });

  it('narrows the nav to the modules that match', () => {
    const { items, component } = createComponent({
      activeRole: 'ROLE_ADMIN',
      roles: ['ROLE_ADMIN'],
      wildcardPermission: true,
    });
    component.navItems.set(items as never);

    // "admin" rather than a clinical term: this role's nav is the one the
    // harness can build with wildcard permissions, and a search that matched
    // nothing would pass a weaker assertion by accident.
    component.navSearch.set('admin');
    const routes = component.navGroups().flatMap((g) => g.entries.map((e) => e.item.route));

    expect(routes.length).toBeGreaterThan(0);
    expect(routes).toContain('/admin');
    // Narrowing, not merely reordering — the point of the box. Both of these
    // are in this role's nav and neither matches, so their absence is the
    // filter working rather than a permission hiding them.
    expect(routes).not.toContain('/dashboard');
    expect(routes).not.toContain('/patients');
  });

  it('reports no match rather than rendering an empty sidebar', () => {
    // An empty nav with no explanation reads as a broken app, and the state is
    // easy to reach by typo.
    const { items, component } = createComponent({
      activeRole: 'ROLE_ADMIN',
      roles: ['ROLE_ADMIN'],
      wildcardPermission: true,
    });
    component.navItems.set(items as never);

    component.navSearch.set('zzzzz-no-such-module');
    expect(component.navGroups()).toEqual([]);
    expect(component.navSearchEmpty()).toBeTrue();

    component.clearNavSearch();
    expect(component.navSearchEmpty()).toBeFalse();
    expect(component.navGroups().length).toBeGreaterThan(0);
  });
});

/**
 * v1.0 row 11 finish — sidebar Alt+ArrowUp/Down keyboard reorder.
 *
 * Tests target the pure-data path on `onNavKeydown`: pressing the
 * shortcut while focused on a nav item swaps that item with its
 * neighbor and persists the new order. The DOM focus restoration
 * (queueMicrotask + ViewChildren refocus) is covered end-to-end by
 * e2e/keyboard-nav.spec.ts; covering it here would require booting
 * the full ngOnInit (websockets, idle timers) which the existing
 * baseNavItems tests intentionally avoid.
 */
describe('ShellComponent — onNavKeydown (row 11 keyboard reorder)', () => {
  interface ShellHandle {
    onNavKeydown: (event: KeyboardEvent, index: number) => void;
    navItems: { (): { route: string }[]; set: (v: { route: string }[]) => void };
  }

  function createShell(): { shell: ShellHandle; persistedOrders: string[][] } {
    const persistedOrders: string[][] = [];
    const navOrderStub = {
      load: () => null,
      save: (routes: string[]) => persistedOrders.push([...routes]),
      applyOrder: <T>(items: T[], _saved: string[]) => items,
    };

    // Minimal wiring — only what the ShellComponent constructor pulls in
    // before we drive the public method directly. A focused test would
    // ideally not stand up the full component, but ShellComponent injects
    // through inject() in field initializers so we need TestBed.
    const authStub = jasmine.createSpyObj<AuthService>('AuthService', [
      'getUserProfile',
      'hasAnyRole',
      'getSubject',
      'formatRole',
      'logout',
    ]);
    authStub.getUserProfile.and.returnValue(null);
    authStub.hasAnyRole.and.returnValue(false);
    authStub.getSubject.and.returnValue(null);
    authStub.formatRole.and.callFake((r: string) => r);
    Object.defineProperty(authStub, 'currentProfile', { value: () => null });

    const permStub: Partial<PermissionService> = {
      hasPermission: () => false,
      hasAnyPermission: () => false,
    };
    const notifStub = jasmine.createSpyObj<NotificationService>('NotificationService', [
      'connectWebSocket',
      'disconnectWebSocket',
      'getNotifications',
      'getNotificationStream',
      'getReadStream',
      'getAllReadStream',
      'markAsReadAndNotify',
      'markAllReadAndNotify',
    ]);
    notifStub.getNotifications.and.returnValue(
      of({ content: [], totalElements: 0, totalPages: 0, size: 10, number: 0 }),
    );
    notifStub.getNotificationStream.and.returnValue(of() as never);
    notifStub.getReadStream.and.returnValue(of() as never);
    notifStub.getAllReadStream.and.returnValue(of() as never);
    const impersonationStub = jasmine.createSpyObj<ImpersonationService>('ImpersonationService', [
      'refreshActive',
    ]);
    impersonationStub.refreshActive.and.returnValue(of(null) as never);
    const idleStub = jasmine.createSpyObj<IdleService>('IdleService', ['start', 'stop']);

    TestBed.configureTestingModule({
      imports: [ShellComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AuthService, useValue: authStub },
        { provide: PermissionService, useValue: permStub },
        { provide: NotificationService, useValue: notifStub },
        { provide: ImpersonationService, useValue: impersonationStub },
        { provide: IdleService, useValue: idleStub },
        // Replace NavOrderService with the in-memory stub so we can
        // assert on what the shell tries to persist.
        { provide: NavOrderService, useValue: navOrderStub },
      ],
    });

    const fixture = TestBed.createComponent(ShellComponent);
    const shell = fixture.componentInstance as unknown as ShellHandle;
    // Seed three nav items directly — bypasses the permission filtering
    // we don't care about here.
    shell.navItems.set([
      { route: '/a' } as never,
      { route: '/b' } as never,
      { route: '/c' } as never,
    ]);

    return { shell, persistedOrders };
  }

  afterEach(() => TestBed.resetTestingModule());

  it('Alt+ArrowDown swaps the focused item with the one below and persists the new order', () => {
    const { shell, persistedOrders } = createShell();
    const event = new KeyboardEvent('keydown', { key: 'ArrowDown', altKey: true });

    shell.onNavKeydown(event, 0);

    expect(shell.navItems().map((i) => i.route)).toEqual(['/b', '/a', '/c']);
    expect(persistedOrders.length).toBe(1);
    expect(persistedOrders[0]).toEqual(['/b', '/a', '/c']);
  });

  it('Alt+ArrowUp at index 0 is a clamped no-op (no swap, no persistence)', () => {
    const { shell, persistedOrders } = createShell();
    const event = new KeyboardEvent('keydown', { key: 'ArrowUp', altKey: true });

    shell.onNavKeydown(event, 0);

    expect(shell.navItems().map((i) => i.route)).toEqual(['/a', '/b', '/c']);
    expect(persistedOrders).toEqual([]);
  });

  it('plain ArrowDown without Alt is ignored (no swap)', () => {
    const { shell, persistedOrders } = createShell();
    const event = new KeyboardEvent('keydown', { key: 'ArrowDown' });

    shell.onNavKeydown(event, 0);

    expect(shell.navItems().map((i) => i.route)).toEqual(['/a', '/b', '/c']);
    expect(persistedOrders).toEqual([]);
  });

  it('Alt+ArrowDown at the last index is a clamped no-op', () => {
    const { shell, persistedOrders } = createShell();
    const event = new KeyboardEvent('keydown', { key: 'ArrowDown', altKey: true });

    shell.onNavKeydown(event, 2);

    expect(shell.navItems().map((i) => i.route)).toEqual(['/a', '/b', '/c']);
    expect(persistedOrders).toEqual([]);
  });

  // ── Now that the nav renders in groups ───────────────────────────────
  //
  // The tests above use /a /b /c, which are in no group and therefore share
  // the fallback one — so they never exercise a boundary. These use real
  // routes from different groups.

  it('stops at the group boundary instead of moving an item nothing can see move', () => {
    // A group is decided by the route, not by position, so crossing the
    // boundary in the flat array would reorder the data and change nothing on
    // screen. The keystroke would read as broken.
    const { shell, persistedOrders } = createShell();
    shell.navItems.set([
      { route: '/dashboard' } as never, // MY_DAY
      { route: '/appointments' } as never, // MY_DAY
      { route: '/patients' } as never, // PATIENTS_FLOW
    ]);

    // Last item of MY_DAY, pressed downward.
    shell.onNavKeydown(new KeyboardEvent('keydown', { key: 'ArrowDown', altKey: true }), 1);

    expect(shell.navItems().map((i) => i.route)).toEqual([
      '/dashboard',
      '/appointments',
      '/patients',
    ]);
    expect(persistedOrders).toEqual([]);
  });

  it('moves to the next item of the same group, stepping over another group', () => {
    const { shell, persistedOrders } = createShell();
    shell.navItems.set([
      { route: '/dashboard' } as never, // MY_DAY
      { route: '/patients' } as never, // PATIENTS_FLOW
      { route: '/appointments' } as never, // MY_DAY
    ]);

    shell.onNavKeydown(new KeyboardEvent('keydown', { key: 'ArrowDown', altKey: true }), 0);

    expect(shell.navItems().map((i) => i.route)).toEqual([
      '/patients',
      '/appointments',
      '/dashboard',
    ]);
    expect(persistedOrders.length).toBe(1);
  });

  it('still persists a flat route order, which is what NavOrderService replays', () => {
    const { shell, persistedOrders } = createShell();
    shell.navItems.set([
      { route: '/dashboard' } as never,
      { route: '/appointments' } as never,
      { route: '/patients' } as never,
    ]);

    shell.onNavKeydown(new KeyboardEvent('keydown', { key: 'ArrowDown', altKey: true }), 0);

    // Grouping is applied at render time, so persistence stays a flat list —
    // no migration of anything already in localStorage.
    expect(persistedOrders[0]).toEqual(['/appointments', '/dashboard', '/patients']);
  });
});

/**
 * Inbox surfaces moved out of the side-nav (Notifications, Messages,
 * Announcements). The side-nav lists places you GO; these three are
 * things that ARRIVE, and Notifications was duplicated — a topbar bell
 * with a live badge AND a redundant nav row that carried no count.
 */
describe('ShellComponent — inbox surfaces live in the topbar', () => {
  function build(roles: string[], activeRole: string) {
    // Same shape as the harness above: ngOnInit calls getUserProfile and
    // formatRole, so a partial stub blows up on the first detectChanges.
    const authStub = jasmine.createSpyObj<AuthService>('AuthService', [
      'getUserProfile',
      'hasAnyRole',
      'getSubject',
      'formatRole',
      'logout',
    ]);
    authStub.getUserProfile.and.returnValue(null);
    authStub.hasAnyRole.and.callFake((rs: string[]) => rs.some((r) => roles.includes(r)));
    // null subject skips the websocket branch in ngOnInit.
    authStub.getSubject.and.returnValue(null);
    authStub.formatRole.and.callFake((r: string) => r);
    Object.defineProperty(authStub, 'currentProfile', { value: () => null });

    // Unlike the harness above, these tests DO run ngOnInit (they assert on
    // rendered DOM), so the stub also needs what ngOnInit calls.
    const permStub: Partial<PermissionService> = {
      hasPermission: () => true,
      hasAnyPermission: () => true,
      loadFromBackend: () => undefined,
    };
    const notifStub = jasmine.createSpyObj<NotificationService>('NotificationService', [
      'connectWebSocket',
      'disconnectWebSocket',
      'getNotifications',
      'getNotificationStream',
      'getReadStream',
      'getAllReadStream',
      'markAsReadAndNotify',
      'markAllReadAndNotify',
    ]);
    notifStub.getNotifications.and.returnValue(
      of({ content: [], totalElements: 0, totalPages: 0, size: 10, number: 0 }),
    );
    notifStub.getNotificationStream.and.returnValue(of() as never);
    notifStub.getReadStream.and.returnValue(of() as never);
    notifStub.getAllReadStream.and.returnValue(of() as never);

    // These tests render the WHOLE shell, so the three banner children mount
    // too and read signals off their own services. Each stub therefore needs
    // its signals callable, not just the methods ngOnInit calls.
    const impersonationStub = jasmine.createSpyObj<ImpersonationService>('ImpersonationService', [
      'refreshActive',
      'stop',
      'forceStop',
    ]);
    impersonationStub.refreshActive.and.returnValue(of(null) as never);
    Object.defineProperty(impersonationStub, 'active', { value: () => null });
    Object.defineProperty(impersonationStub, 'nearingExpiry', { value: () => false });
    Object.defineProperty(impersonationStub, 'remainingMs', { value: () => 0 });

    const idleStub = jasmine.createSpyObj<IdleService>('IdleService', ['start', 'stop']);
    // idle.locked() decides whether the lock screen paints over the shell.
    Object.defineProperty(idleStub, 'locked', { value: () => false });

    const broadcastStub = jasmine.createSpyObj<EmergencyBroadcastService>(
      'EmergencyBroadcastService',
      ['connect', 'disconnect', 'dismiss'],
    );
    Object.defineProperty(broadcastStub, 'latest', { value: () => null });

    const downtimeStub = jasmine.createSpyObj<DowntimeService>('DowntimeService', [
      'startPolling',
      'stopPolling',
    ]);
    Object.defineProperty(downtimeStub, 'status', { value: () => null });

    TestBed.configureTestingModule({
      imports: [ShellComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AuthService, useValue: authStub },
        { provide: PermissionService, useValue: permStub },
        { provide: NotificationService, useValue: notifStub },
        { provide: ImpersonationService, useValue: impersonationStub },
        { provide: IdleService, useValue: idleStub },
        { provide: EmergencyBroadcastService, useValue: broadcastStub },
        { provide: DowntimeService, useValue: downtimeStub },
      ],
    });

    const roleContext = TestBed.inject(RoleContextService);
    roleContext.setRoles(roles);
    roleContext.activeRole = activeRole;
    return TestBed.createComponent(ShellComponent);
  }

  it('drops all three from the side-nav for staff', () => {
    const fixture = build(['ROLE_DOCTOR'], 'ROLE_DOCTOR');
    const routes = (fixture.componentInstance as unknown as { baseNavItems: () => NavItem[] })
      .baseNavItems()
      .map((i) => i.route);

    expect(routes).not.toContain('/notifications');
    expect(routes).not.toContain('/chat');
    expect(routes).not.toContain('/announcements');
  });

  it('drops both from the patient side-nav — the topbar bell serves patients too', () => {
    // /notifications and the patient portal's own endpoint both call
    // notificationService.getNotificationsForUser(username), so the bell
    // shows a patient the same rows /my-notifications did.
    const fixture = build(['ROLE_PATIENT'], 'ROLE_PATIENT');
    const routes = (fixture.componentInstance as unknown as { baseNavItems: () => NavItem[] })
      .baseNavItems()
      .map((i) => i.route);

    expect(routes).not.toContain('/my-notifications');
    expect(routes).not.toContain('/chat');
  });

  it('renders Messages and Announcements links in the topbar', () => {
    const fixture = build(['ROLE_DOCTOR'], 'ROLE_DOCTOR');
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    const hrefs = Array.from(host.querySelectorAll('.topbar-right a.icon-btn')).map((a) =>
      a.getAttribute('href'),
    );
    expect(hrefs).toContain('/chat');
    expect(hrefs).toContain('/announcements');
  });

  it('shows the unread-message badge and clamps past 99', () => {
    const fixture = build(['ROLE_DOCTOR'], 'ROLE_DOCTOR');
    // detectChanges FIRST: it runs ngOnInit, whose loadUnreadMessages()
    // resets the count to 0 before its request resolves. Setting the value
    // beforehand would be overwritten.
    fixture.detectChanges();
    fixture.componentInstance.chatUnread.set(120);
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    const badges = Array.from(host.querySelectorAll('.topbar-right .badge')).map((b) =>
      b.textContent?.trim(),
    );
    expect(badges).toContain('99+');
  });

  it('hides the badge at zero rather than rendering a 0', () => {
    const fixture = build(['ROLE_DOCTOR'], 'ROLE_DOCTOR');
    fixture.detectChanges();
    fixture.componentInstance.chatUnread.set(0);
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    const messagesLink = host.querySelector('.topbar-right a.icon-btn[href="/chat"]');
    expect(messagesLink?.querySelector('.badge')).toBeNull();
  });
});
