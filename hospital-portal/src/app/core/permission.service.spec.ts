import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { PermissionService } from './permission.service';
import { AuthService } from '../auth/auth.service';

describe('PermissionService', () => {
  let service: PermissionService;
  let authStub: jasmine.SpyObj<AuthService>;

  function setup(roles: string[]): void {
    authStub = jasmine.createSpyObj('AuthService', ['getRoles']);
    authStub.getRoles.and.returnValue(roles);

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
});
