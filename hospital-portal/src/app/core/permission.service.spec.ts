import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { PermissionService } from './permission.service';
import { AuthService } from '../auth/auth.service';

describe('PermissionService', () => {
  let service: PermissionService;
  let authStub: jasmine.SpyObj<AuthService>;

  function setup(roles: string[], authenticated = true): void {
    authStub = jasmine.createSpyObj('AuthService', ['getRoles', 'isAuthenticated']);
    authStub.getRoles.and.returnValue(roles);
    authStub.isAuthenticated.and.returnValue(authenticated);

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        PermissionService,
        { provide: AuthService, useValue: authStub },
      ],
    });
    service = TestBed.inject(PermissionService);
  }

  // ── Nurse Station RBAC ──────────────────────────────────────────

  it('ROLE_NURSE should have Access Nurse Station', () => {
    setup(['ROLE_NURSE']);
    expect(service.hasPermission('Access Nurse Station')).toBeTrue();
  });

  it('ROLE_MIDWIFE should have Access Nurse Station', () => {
    setup(['ROLE_MIDWIFE']);
    expect(service.hasPermission('Access Nurse Station')).toBeTrue();
  });

  it('ROLE_HOSPITAL_ADMIN should have Access Nurse Station', () => {
    setup(['ROLE_HOSPITAL_ADMIN']);
    expect(service.hasPermission('Access Nurse Station')).toBeTrue();
  });

  it('ROLE_DOCTOR should NOT have Access Nurse Station', () => {
    setup(['ROLE_DOCTOR']);
    expect(service.hasPermission('Access Nurse Station')).toBeFalse();
  });

  it('ROLE_PHYSICIAN should NOT have Access Nurse Station', () => {
    setup(['ROLE_PHYSICIAN']);
    expect(service.hasPermission('Access Nurse Station')).toBeFalse();
  });

  it('ROLE_SURGEON should NOT have Access Nurse Station', () => {
    setup(['ROLE_SURGEON']);
    expect(service.hasPermission('Access Nurse Station')).toBeFalse();
  });

  it('ROLE_RECEPTIONIST should NOT have Access Nurse Station', () => {
    setup(['ROLE_RECEPTIONIST']);
    expect(service.hasPermission('Access Nurse Station')).toBeFalse();
  });

  // ── Doctor still has nursing-note documentation permission ──────

  it('ROLE_DOCTOR should still have Document Nursing Notes', () => {
    setup(['ROLE_DOCTOR']);
    expect(service.hasPermission('Document Nursing Notes')).toBeTrue();
  });

  // ── Quick-action permissions for doctors ────────────────────────

  it('ROLE_DOCTOR should have Create Encounters', () => {
    setup(['ROLE_DOCTOR']);
    expect(service.hasPermission('Create Encounters')).toBeTrue();
  });

  it('ROLE_DOCTOR should have View Lab', () => {
    setup(['ROLE_DOCTOR']);
    expect(service.hasPermission('View Lab')).toBeTrue();
  });

  it('ROLE_DOCTOR should have Create Referrals', () => {
    setup(['ROLE_DOCTOR']);
    expect(service.hasPermission('Create Referrals')).toBeTrue();
  });

  // ── Super admin wildcard grants everything ──────────────────────

  it('ROLE_SUPER_ADMIN should have Access Nurse Station via wildcard', () => {
    setup(['ROLE_SUPER_ADMIN']);
    expect(service.hasPermission('Access Nurse Station')).toBeTrue();
  });

  // ── Lab Manager RBAC (Gap 1 regression guard) ──────────────────

  it('ROLE_LAB_MANAGER should have View Dashboard', () => {
    setup(['ROLE_LAB_MANAGER']);
    expect(service.hasPermission('View Dashboard')).toBeTrue();
  });

  it('ROLE_LAB_MANAGER should have View Lab', () => {
    setup(['ROLE_LAB_MANAGER']);
    expect(service.hasPermission('View Lab')).toBeTrue();
  });

  it('ROLE_LAB_MANAGER should have Process Lab Tests', () => {
    setup(['ROLE_LAB_MANAGER']);
    expect(service.hasPermission('Process Lab Tests')).toBeTrue();
  });

  it('ROLE_LAB_MANAGER should have View Patient Records', () => {
    setup(['ROLE_LAB_MANAGER']);
    expect(service.hasPermission('View Patient Records')).toBeTrue();
  });

  it('ROLE_LAB_MANAGER should have View Staff', () => {
    setup(['ROLE_LAB_MANAGER']);
    expect(service.hasPermission('View Staff')).toBeTrue();
  });

  it('ROLE_LAB_MANAGER should have View Staff Schedules', () => {
    setup(['ROLE_LAB_MANAGER']);
    expect(service.hasPermission('View Staff Schedules')).toBeTrue();
  });

  it('ROLE_LAB_MANAGER should have View Departments', () => {
    setup(['ROLE_LAB_MANAGER']);
    expect(service.hasPermission('View Departments')).toBeTrue();
  });

  it('ROLE_LAB_MANAGER should have View Notifications', () => {
    setup(['ROLE_LAB_MANAGER']);
    expect(service.hasPermission('View Notifications')).toBeTrue();
  });

  it('ROLE_LAB_MANAGER should NOT have Access Nurse Station', () => {
    setup(['ROLE_LAB_MANAGER']);
    expect(service.hasPermission('Access Nurse Station')).toBeFalse();
  });

  // ── Backend-driven permissions (/me/dashboard-config) ──────────

  describe('loadFromBackend', () => {
    function httpMock(): HttpTestingController {
      return TestBed.inject(HttpTestingController);
    }

    it('unions backend grants with the fallback map', () => {
      setup(['ROLE_DOCTOR']);
      expect(service.hasPermission('Sign Medical Reports')).toBeFalse();

      service.loadFromBackend();
      httpMock()
        .expectOne('/me/dashboard-config')
        .flush({ mergedPermissions: ['Sign Medical Reports'] });

      expect(service.hasPermission('Sign Medical Reports')).toBeTrue();
      // Fallback map still applies alongside backend grants.
      expect(service.hasPermission('Create Encounters')).toBeTrue();
    });

    it('grants permissions to a role missing from the fallback map', () => {
      setup(['ROLE_SOME_FUTURE_ROLE']);
      expect(service.hasPermission('View Patient Records')).toBeFalse();

      service.loadFromBackend();
      httpMock()
        .expectOne('/me/dashboard-config')
        .flush({ mergedPermissions: ['View Patient Records'] });

      expect(service.hasPermission('View Patient Records')).toBeTrue();
    });

    it('expands backend names to frontend aliases (View Lab Orders → View Lab)', () => {
      setup(['ROLE_STAFF']);
      expect(service.hasPermission('View Lab')).toBeFalse();

      service.loadFromBackend();
      httpMock()
        .expectOne('/me/dashboard-config')
        .flush({ mergedPermissions: ['View Lab Orders'] });

      expect(service.hasPermission('View Lab')).toBeTrue();
      expect(service.hasPermission('View Lab Orders')).toBeTrue();
    });

    it('keeps the fallback map when the request fails', () => {
      setup(['ROLE_NURSE']);
      service.loadFromBackend();
      httpMock()
        .expectOne('/me/dashboard-config')
        .flush('boom', { status: 500, statusText: 'Server Error' });

      expect(service.hasPermission('Access Nurse Station')).toBeTrue();
    });

    it('does not call the backend when unauthenticated', () => {
      setup(['ROLE_NURSE'], false);
      service.loadFromBackend();
      httpMock().expectNone('/me/dashboard-config');
      expect(service.hasPermission('Access Nurse Station')).toBeTrue();
    });

    it('clear() drops backend grants but keeps the fallback map', () => {
      setup(['ROLE_NURSE']);
      service.loadFromBackend();
      httpMock()
        .expectOne('/me/dashboard-config')
        .flush({ mergedPermissions: ['Sign Medical Reports'] });
      expect(service.hasPermission('Sign Medical Reports')).toBeTrue();

      service.clear();

      expect(service.hasPermission('Sign Medical Reports')).toBeFalse();
      expect(service.hasPermission('Access Nurse Station')).toBeTrue();
    });
  });
});
