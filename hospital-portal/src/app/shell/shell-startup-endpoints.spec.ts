import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { ShellComponent } from './shell';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { IdleService } from '../core/idle.service';
import { EmergencyBroadcastService } from '../services/emergency-broadcast.service';
import { NotificationService } from '../services/notification.service';
import { expandRoleEquivalents } from '../core/role-equivalence';
import { EMPI_MATCHER_ROLES } from '../reception/empi-status.service';
import {
  ANY_AUTHENTICATED,
  SHELL_GUARD_ROLES,
  SHELL_STARTUP_ENDPOINTS,
} from './shell-startup-endpoints';

/**
 * QA D1012 (class guard): the shell and the app-wide services it starts send
 * no request the signed-in role cannot use. The D987 duplicate-patient probe
 * went out on every page load for every role, and the server refused it to
 * every patient, pharmacist and lab user.
 *
 * Boots the real shell (real permission, EMPI, impersonation, downtime and
 * notification-list services; only the sockets and the idle timer are
 * stubbed) once per role, after the session bootstrap has resolved, and
 * reads every request it sent.
 */
describe('Shell startup requests (QA D1012 class guard)', () => {
  function bootAs(role: string): string[] {
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
    fixture.componentInstance.ngOnInit();
    TestBed.tick();

    const http = TestBed.inject(HttpTestingController);
    const sent = http.match(() => true).map((r) => `${r.request.method} ${r.request.url}`);
    fixture.destroy();
    return sent;
  }

  it('the EMPI roles of the table are the ones the service gates on', () => {
    expect([...(SHELL_STARTUP_ENDPOINTS['GET /empi/candidates/status'] as readonly string[])])
      .withContext('keep the table and EMPI_MATCHER_ROLES equal')
      .toEqual([...EMPI_MATCHER_ROLES]);
  });

  for (const role of SHELL_GUARD_ROLES) {
    it(`${role}: every request is listed, and admitted for the role`, () => {
      const sent = bootAs(role);
      expect(sent.length)
        .withContext('the boot sent nothing: the guard reads nothing')
        .toBeGreaterThan(0);
      for (const request of sent) {
        const admitted = SHELL_STARTUP_ENDPOINTS[request];
        expect(admitted)
          .withContext(
            `${request} is not in SHELL_STARTUP_ENDPOINTS: list it with the roles the server admits`,
          )
          .toBeDefined();
        if (admitted && admitted !== ANY_AUTHENTICATED) {
          expect(expandRoleEquivalents([role]).some((r) => admitted.includes(r)))
            .withContext(`${role} sends ${request}, which the server refuses it (QA D1012)`)
            .toBeTrue();
        }
      }
    });
  }

  it('a patient sends no EMPI probe at all (QA D1012)', () => {
    expect(bootAs('ROLE_PATIENT')).not.toContain('GET /empi/candidates/status');
  });

  it('a receptionist still asks whether the matcher is on (QA D987)', () => {
    expect(bootAs('ROLE_RECEPTIONIST')).toContain('GET /empi/candidates/status');
  });
});
