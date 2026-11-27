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
      auth.setToken(token);
      context.setRoles(['ROLE_SUPER_ADMIN']);
      context.markSuperAdminGlobalDefaults();
      context.setRoles(auth.getRoles());
      session.hydrate().subscribe();
      http.expectOne('auth/session/bootstrap').flush({
        userId: 'synthetic-user',
        username: 'synthetic.test',
        email: 'synthetic@example.test',
        authSource: 'internal',
        roles: [...new Set([role, 'ROLE_SUPER_ADMIN'])],
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

  // QA D3300 (r70): the picker offers « Médecin » to a surgeon and to a
  // physician (the doctor equivalence); the bootstrap lists only the held role.
  for (const held of ['ROLE_SURGEON', 'ROLE_PHYSICIAN']) {
    it(`keeps a ${held} who signed in as « Médecin » signed in (D3300)`, () => {
      TestBed.configureTestingModule({
        providers: [provideHttpClient(withXhr()), provideHttpClientTesting(), provideRouter([])],
      });
      const auth = TestBed.inject(AuthService);
      const context = TestBed.inject(RoleContextService);
      const session = TestBed.inject(SessionScopeService);
      const http = TestBed.inject(HttpTestingController);
      const token = `e30.${btoa(JSON.stringify({ roles: ['ROLE_DOCTOR'], exp: Math.floor(Date.now() / 1000) + 3600 }))}.signature`;
      auth.setToken(token);
      context.setRoles(auth.getRoles());
      session.hydrate().subscribe();
      http.expectOne('auth/session/bootstrap').flush({
        userId: 'synthetic-user',
        username: 'synthetic.test',
        authSource: 'internal',
        email: 'synthetic@example.test',
        roles: [held],
        superAdmin: false,
        hospitalAdmin: false,
        primaryHospitalId: 'hospital-a',
        permittedHospitalIds: ['hospital-a'],
      } satisfies SessionBootstrapResponse);
      // No logout call: afterEach's verify() fails on an unexpected one.
      expect(auth.isAuthenticated()).toBeTrue();
      expect(context.activeRoles).toEqual(['ROLE_DOCTOR']);
      expect(context.activeRole).toBe('ROLE_DOCTOR');
      expect(context.effectiveHospitalIdForRequest()).toBe('hospital-a');
    });
  }

  it('still ends a « Chirurgien(ne) » session whose only live role is doctor (D3300: one way only)', () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting(), provideRouter([])],
    });
    const auth = TestBed.inject(AuthService);
    const context = TestBed.inject(RoleContextService);
    const session = TestBed.inject(SessionScopeService);
    const http = TestBed.inject(HttpTestingController);
    const token = `e30.${btoa(JSON.stringify({ roles: ['ROLE_SURGEON'], exp: Math.floor(Date.now() / 1000) + 3600 }))}.signature`;
    auth.setToken(token);
    context.setRoles(auth.getRoles());
    session.hydrate().subscribe();
    http.expectOne('auth/session/bootstrap').flush({
      userId: 'synthetic-user',
      username: 'synthetic.test',
      authSource: 'internal',
      email: 'synthetic@example.test',
      roles: ['ROLE_DOCTOR'],
      superAdmin: false,
      hospitalAdmin: false,
      permittedHospitalIds: ['hospital-a'],
    } satisfies SessionBootstrapResponse);
    http.expectOne((req) => req.url.endsWith('/api/auth/logout')).flush({});
    expect(auth.isAuthenticated()).toBeFalse();
    expect(context.activeRoles).toEqual([]);
  });

  for (const liveRoles of [[], ['ROLE_DOCTOR']]) {
    it(`ends a revoked selected-role session with live roles ${JSON.stringify(liveRoles)}`, () => {
      TestBed.configureTestingModule({
        providers: [provideHttpClient(withXhr()), provideHttpClientTesting(), provideRouter([])],
      });
      const auth = TestBed.inject(AuthService);
      const context = TestBed.inject(RoleContextService);
      const session = TestBed.inject(SessionScopeService);
      const http = TestBed.inject(HttpTestingController);
      const token = `e30.${btoa(JSON.stringify({ roles: ['ROLE_HOSPITAL_ADMIN'], exp: Math.floor(Date.now() / 1000) + 3600 }))}.signature`;
      auth.setToken(token);
      context.setRoles(auth.getRoles());
      session.hydrate().subscribe();
      http.expectOne('auth/session/bootstrap').flush({
        userId: 'synthetic-user',
        username: 'synthetic.test',
        authSource: 'internal',
        email: 'synthetic@example.test',
        roles: liveRoles,
        superAdmin: false,
        hospitalAdmin: false,
        permittedHospitalIds: ['hospital-a'],
      } satisfies SessionBootstrapResponse);
      const logout = http.expectOne((req) => req.url.endsWith('/api/auth/logout'));
      expect(logout.request.headers.get('Authorization')).toBe(`Bearer ${token}`);
      logout.flush({});
      expect(auth.isAuthenticated()).toBeFalse();
      expect(auth.getUserProfile()).toBeNull();
      expect(context.activeRoles).toEqual([]);
      expect(context.activeRole).toBeNull();
      expect(context.globalView()).toBeFalse();
      expect(context.effectiveHospitalIdForRequest()).toBeNull();
      session.applyStoredProfile();
      const forbidden = TestBed.runInInjectionContext(() =>
        RoleGuard({ data: { roles: ['ROLE_HOSPITAL_ADMIN'] } } as never, {} as never),
      );
      expect(TestBed.inject(Router).serializeUrl(forbidden as UrlTree)).toBe('/error/403');
    });
  }
});
