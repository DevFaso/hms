import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { DashboardComponent } from './dashboard';
import { routes as appRoutes } from '../app.routes';
import { AuthService } from '../auth/auth.service';
import { IdleService } from '../core/idle.service';
import { PermissionService } from '../core/permission.service';
import { RoleContextService } from '../core/role-context.service';
import { EmergencyBroadcastService } from '../services/emergency-broadcast.service';
import { NotificationService } from '../services/notification.service';
import { ShellComponent } from '../shell/shell';

/**
 * Platform admin rules 2026-11-20, rule 1b (review of #1246): the organisation's
 * administrator (ROLE_ADMIN) reads no patient or clinical data. Its dashboard
 * is staff registration, assignments and its organisation's totals: it asks
 * the server for no patient, no hospital-admin summary and no audit row, and
 * renders no counter, list or panel about a patient's activity.
 */
describe('Dashboard — the organisation admin reads no patient data (platform admin rules, rule 1b)', () => {
  function render(role: string) {
    const roles = [role];
    const auth = jasmine.createSpyObj<AuthService>('AuthService', [
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
    auth.isAuthenticated.and.returnValue(true);
    auth.isExpired.and.returnValue(false);
    auth.getUserProfile.and.returnValue({
      id: 'u1',
      username: 'someone',
      firstName: 'Test',
      lastName: 'Utilisateur',
      roles,
      active: true,
    } as never);
    auth.getRoles.and.returnValue(roles);
    auth.getToken.and.returnValue('fake-token');
    auth.getUserId.and.returnValue('u1');
    auth.hasAnyRole.and.callFake((asked: string[]) => asked.some((r) => roles.includes(r)));
    auth.getSubject.and.returnValue(null);
    Object.defineProperty(auth, 'currentProfile', { value: () => null });
    Object.defineProperty(auth, 'getHospitalId', { value: () => 'h1' });

    TestBed.configureTestingModule({
      imports: [ShellComponent, DashboardComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter(appRoutes),
        { provide: AuthService, useValue: auth },
        {
          provide: IdleService,
          useValue: { start: () => undefined, stop: () => undefined, locked: () => false },
        },
      ],
    });
    const notifications = TestBed.inject(NotificationService);
    spyOn(notifications, 'connectWebSocket');
    spyOn(notifications, 'disconnectWebSocket');
    const broadcast = TestBed.inject(EmergencyBroadcastService);
    spyOn(broadcast, 'connect');
    spyOn(broadcast, 'disconnect');
    const roleContext = TestBed.inject(RoleContextService);
    roleContext.setRoles(roles);
    roleContext.activeRole = role;
    roleContext.markSessionResolved();

    // The real shell publishes the sidebar the dashboard's links are held to.
    TestBed.createComponent(ShellComponent);
    TestBed.tick();
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.loading.set(false);
    fixture.detectChanges();

    const http = TestBed.inject(HttpTestingController);
    const asked = http.match(() => true).map((request) => request.request.url);
    const host = fixture.nativeElement as HTMLElement;
    return {
      asked,
      host,
      links: Array.from(host.querySelectorAll<HTMLAnchorElement>('a[href]')).map(
        (a) => (a.getAttribute('href') ?? '').split(/[?#]/)[0],
      ),
    };
  }

  afterEach(() => TestBed.resetTestingModule());

  it('the static permissions of ROLE_ADMIN hold no patient record, audit trail or imaging', () => {
    const { host } = render('ROLE_ADMIN');
    const permissions = TestBed.inject(PermissionService);
    for (const refused of [
      'View Patient Records',
      'View Audit Logs',
      'View Imaging Studies',
      'View Appointments',
      'View Billing',
    ]) {
      expect(permissions.hasPermission(refused)).withContext(refused).toBeFalse();
    }
    expect(permissions.hasPermission('View Dashboard')).toBeTrue();
    expect(host).toBeTruthy();
  });

  it('asks for its organisation’s totals, and for no patient, hospital-admin summary or appointment', () => {
    const { asked } = render('ROLE_ADMIN');

    expect(asked.some((url) => url.endsWith('/organization-admin/summary'))).toBeTrue();
    for (const refused of [
      '/dashboard/hospital-admin/summary',
      '/patients',
      '/appointments',
      '/audit-logs',
      '/reception',
      '/imaging',
    ]) {
      expect(asked.filter((url) => url.includes(refused)))
        .withContext(`a request under ${refused}`)
        .toEqual([]);
    }
  });

  it('shows its two actions and the totals card: no patient counter, list or panel', () => {
    const { host, links } = render('ROLE_ADMIN');

    expect(host.querySelector('[data-testid="org-admin-actions"]')).not.toBeNull();
    expect(links).toContain('/users');
    expect(links).toContain('/admin-assignments');
    expect(host.querySelector('app-organisation-totals')).not.toBeNull();
    // Nothing of the hospital admin's view: its counters, « Patients récents », the schedule, the audit trend.
    expect(host.querySelector('.stat-strip')).toBeNull();
    expect(host.querySelector('.admin-workspace')).toBeNull();
    expect(host.querySelector('.schedule-panel')).toBeNull();
    for (const refused of ['/patients', '/audit-logs', '/appointments', '/imaging', '/reception']) {
      expect(links).withContext(refused).not.toContain(refused);
    }
  });

  it('a hospital admin keeps its own view: the summary is asked for, and no organisation totals', () => {
    const { asked, host } = render('ROLE_HOSPITAL_ADMIN');

    expect(asked.some((url) => url.includes('/dashboard/hospital-admin/summary'))).toBeTrue();
    expect(asked.some((url) => url.includes('/organization-admin/'))).toBeFalse();
    expect(host.querySelector('.admin-workspace')).not.toBeNull();
    expect(host.querySelector('app-organisation-totals')).toBeNull();
    expect(host.querySelector('[data-testid="org-admin-actions"]')).toBeNull();
  });
});
