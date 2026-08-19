import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, UrlTree } from '@angular/router';
import { provideRouter } from '@angular/router';

import { RoleGuard } from './role.guard';
import { AuthService } from './auth.service';
import { RoleContextService } from '../core/role-context.service';

function snapshot(roles: string[]): ActivatedRouteSnapshot {
  return { data: { roles } } as unknown as ActivatedRouteSnapshot;
}

describe('RoleGuard — /super-admin route protection', () => {
  let router: Router;
  let auth: jasmine.SpyObj<AuthService>;
  let roleContext: RoleContextService;

  beforeEach(() => {
    auth = jasmine.createSpyObj<AuthService>('AuthService', ['hasAnyRole']);
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: AuthService, useValue: auth }],
    });
    router = TestBed.inject(Router);
    roleContext = TestBed.inject(RoleContextService);
  });

  afterEach(() => TestBed.resetTestingModule());

  it('allows a user whose active role is ROLE_SUPER_ADMIN', () => {
    roleContext.setRoles(['ROLE_SUPER_ADMIN']);
    roleContext.activeRole = 'ROLE_SUPER_ADMIN';
    auth.hasAnyRole.and.returnValue(true);

    const result = TestBed.runInInjectionContext(() =>
      RoleGuard(snapshot(['ROLE_SUPER_ADMIN']), {} as never),
    );

    expect(result).toBeTrue();
  });

  it('denies a hospital-admin and returns the /error/403 UrlTree', () => {
    roleContext.setRoles(['ROLE_HOSPITAL_ADMIN']);
    roleContext.activeRole = 'ROLE_HOSPITAL_ADMIN';
    auth.hasAnyRole.and.returnValue(false);

    const result = TestBed.runInInjectionContext(() =>
      RoleGuard(snapshot(['ROLE_SUPER_ADMIN']), {} as never),
    );

    expect(result).toEqual(jasmine.any(UrlTree));
    expect(router.serializeUrl(result as UrlTree)).toBe('/error/403');
  });

  it('denies a multi-role user who picked a non-super active role', () => {
    roleContext.setRoles(['ROLE_SUPER_ADMIN', 'ROLE_DOCTOR']);
    roleContext.activeRole = 'ROLE_DOCTOR';
    auth.hasAnyRole.and.returnValue(true);

    const result = TestBed.runInInjectionContext(() =>
      RoleGuard(snapshot(['ROLE_SUPER_ADMIN']), {} as never),
    );

    expect(result).toEqual(jasmine.any(UrlTree));
    expect(router.serializeUrl(result as UrlTree)).toBe('/error/403');
  });
});
