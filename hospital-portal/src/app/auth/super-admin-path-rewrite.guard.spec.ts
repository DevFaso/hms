import { TestBed } from '@angular/core/testing';
import { Router, UrlTree, provideRouter } from '@angular/router';

import { superAdminPathRewriteGuard } from './super-admin-path-rewrite.guard';
import { AuthService } from './auth.service';
import { RoleContextService } from '../core/role-context.service';

describe('superAdminPathRewriteGuard — MVP-5b', () => {
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

  it('rewrites a super-admin click on /feature-flags to /super-admin/feature-flags', () => {
    roleContext.setRoles(['ROLE_SUPER_ADMIN']);
    roleContext.activeRole = 'ROLE_SUPER_ADMIN';
    const guard = superAdminPathRewriteGuard('/super-admin/feature-flags');

    const result = TestBed.runInInjectionContext(() => guard({} as never, {} as never));

    expect(result).toEqual(jasmine.any(UrlTree));
    expect(router.serializeUrl(result as UrlTree)).toBe('/super-admin/feature-flags');
  });

  it('falls back to AuthService.hasAnyRole when no active role is set', () => {
    auth.hasAnyRole.and.returnValue(true);
    const guard = superAdminPathRewriteGuard('/super-admin/audit-logs');

    const result = TestBed.runInInjectionContext(() => guard({} as never, {} as never));

    expect(auth.hasAnyRole).toHaveBeenCalledWith(['ROLE_SUPER_ADMIN']);
    expect(result).toEqual(jasmine.any(UrlTree));
    expect(router.serializeUrl(result as UrlTree)).toBe('/super-admin/audit-logs');
  });

  it('lets a hospital-admin click on /audit-logs through to the legacy URL', () => {
    roleContext.setRoles(['ROLE_HOSPITAL_ADMIN']);
    roleContext.activeRole = 'ROLE_HOSPITAL_ADMIN';
    const guard = superAdminPathRewriteGuard('/super-admin/audit-logs');

    const result = TestBed.runInInjectionContext(() => guard({} as never, {} as never));

    expect(result).toBeTrue();
  });

  it('lets a multi-role super admin who picked ROLE_DOCTOR through unchanged', () => {
    roleContext.setRoles(['ROLE_SUPER_ADMIN', 'ROLE_DOCTOR']);
    roleContext.activeRole = 'ROLE_DOCTOR';
    const guard = superAdminPathRewriteGuard('/super-admin/platform');

    const result = TestBed.runInInjectionContext(() => guard({} as never, {} as never));

    expect(result).toBeTrue();
  });
});
