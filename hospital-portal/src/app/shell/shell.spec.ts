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
