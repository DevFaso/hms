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
import { NavOrderService } from './nav-order.service';

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
  }): { items: NavItem[] } {
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
    const permStub: Partial<PermissionService> = {
      hasPermission: () => opts.wildcardPermission,
      hasAnyPermission: () => opts.wildcardPermission,
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
    return { items };
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
    expect(routes).toContain('/my-notifications');
    expect(routes).not.toContain('/notifications');
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
});
