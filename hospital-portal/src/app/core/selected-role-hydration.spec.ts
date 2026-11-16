import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, UrlTree, provideRouter } from '@angular/router';
import { AuthService, SessionBootstrapResponse } from '../auth/auth.service';
import { SuperAdminRedirectGuard } from '../auth/super-admin-redirect.guard';
import { superAdminPathRewriteGuard } from '../auth/super-admin-path-rewrite.guard';
import { RoleGuard } from '../auth/role.guard';
import { RoleContextService } from './role-context.service';
import { SessionScopeService } from './session-scope.service';

describe('D1973: selected internal roles survive real session hydration', () => {
  afterEach(() => {
    TestBed.inject(AuthService).clearToken();
    TestBed.inject(AuthService).clearUserProfile();
    TestBed.inject(HttpTestingController).verify();
  });

  for (const role of [
    'ROLE_DOCTOR',
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_RECEPTIONIST',
    'ROLE_SUPER_ADMIN',
  ]) {
    it(`retains ${role}, the matching landing and tenant scope after account-role bootstrap`, () => {
      TestBed.configureTestingModule({
        providers: [provideHttpClient(withXhr()), provideHttpClientTesting(), provideRouter([])],
      });
      const auth = TestBed.inject(AuthService);
      const context = TestBed.inject(RoleContextService);
      const session = TestBed.inject(SessionScopeService);
      const http = TestBed.inject(HttpTestingController);
      const router = TestBed.inject(Router);
      const token = `e30.${btoa(JSON.stringify({ roles: [role], exp: Math.floor(Date.now() / 1000) + 3600 }))}.signature`;
      auth.setToken(token, false);
      context.setRoles(['ROLE_SUPER_ADMIN']);
      context.markSuperAdminGlobalDefaults();
      context.setRoles(auth.getRoles());
      session.hydrate().subscribe();
      http.expectOne('auth/session/bootstrap').flush({
        userId: 'synthetic-user',
        username: 'synthetic.test',
        email: 'synthetic@example.test',
        authSource: 'internal',
        roles: ['ROLE_SUPER_ADMIN'],
        superAdmin: true,
        hospitalAdmin: false,
        primaryHospitalId: 'hospital-a',
        permittedHospitalIds: ['hospital-a', 'hospital-b'],
      } satisfies SessionBootstrapResponse);
      const superAdmin = role === 'ROLE_SUPER_ADMIN';
      expect(context.activeRoles).toEqual([role]);
      expect(context.activeRole).toBe(role);
      expect(context.globalView()).toBe(superAdmin);
      expect(context.effectiveHospitalIdForRequest()).toBe(superAdmin ? null : 'hospital-a');
      expect(auth.getUserProfile()?.roles).toEqual([role]);
      expect(auth.resolveLandingPath()).toBe(superAdmin ? '/super-admin' : '/dashboard');
      const root = TestBed.runInInjectionContext(() =>
        SuperAdminRedirectGuard({} as never, {} as never),
      );
      const legacy = TestBed.runInInjectionContext(() =>
        superAdminPathRewriteGuard('/super-admin/platform')({} as never, {} as never),
      );
      if (superAdmin) {
        expect(router.serializeUrl(root as UrlTree)).toBe('/super-admin');
        expect(router.serializeUrl(legacy as UrlTree)).toBe('/super-admin/platform');
      } else {
        expect(root).toBeTrue();
        expect(legacy).toBeTrue();
        const forbidden = TestBed.runInInjectionContext(() =>
          RoleGuard({ data: { roles: ['ROLE_SUPER_ADMIN'] } } as never, {} as never),
        );
        expect(router.serializeUrl(forbidden as UrlTree)).toBe('/error/403');
      }
    });
  }
});
