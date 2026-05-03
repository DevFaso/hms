import { TestBed } from '@angular/core/testing';
import { Router, UrlTree, provideRouter } from '@angular/router';

import { SuperAdminRedirectGuard } from './super-admin-redirect.guard';
import { AuthService } from './auth.service';
import { RoleContextService } from '../core/role-context.service';

describe('SuperAdminRedirectGuard — MVP-5 surface consolidation', () => {
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

  it('redirects a super-admin active role to /super-admin', () => {
    roleContext.setRoles(['ROLE_SUPER_ADMIN']);
    roleContext.activeRole = 'ROLE_SUPER_ADMIN';

    const result = TestBed.runInInjectionContext(() =>
      SuperAdminRedirectGuard({} as never, {} as never),
    );

    expect(result).toEqual(jasmine.any(UrlTree));
    expect(router.serializeUrl(result as UrlTree)).toBe('/super-admin');
  });

  it('falls back to AuthService.hasAnyRole when no active role is set', () => {
    auth.hasAnyRole.and.returnValue(true);

    const result = TestBed.runInInjectionContext(() =>
      SuperAdminRedirectGuard({} as never, {} as never),
    );

    expect(auth.hasAnyRole).toHaveBeenCalledWith(['ROLE_SUPER_ADMIN']);
    expect(result).toEqual(jasmine.any(UrlTree));
    expect(router.serializeUrl(result as UrlTree)).toBe('/super-admin');
  });

  it('lets a hospital-admin active role through to /dashboard', () => {
    roleContext.setRoles(['ROLE_HOSPITAL_ADMIN']);
    roleContext.activeRole = 'ROLE_HOSPITAL_ADMIN';

    const result = TestBed.runInInjectionContext(() =>
      SuperAdminRedirectGuard({} as never, {} as never),
    );

    expect(result).toBeTrue();
  });

  it('lets a multi-role super admin who picked a non-super active role through', () => {
    roleContext.setRoles(['ROLE_SUPER_ADMIN', 'ROLE_DOCTOR']);
    roleContext.activeRole = 'ROLE_DOCTOR';

    const result = TestBed.runInInjectionContext(() =>
      SuperAdminRedirectGuard({} as never, {} as never),
    );

    expect(result).toBeTrue();
  });
});
