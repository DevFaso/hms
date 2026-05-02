import { TestBed } from '@angular/core/testing';
import { RoleContextService } from './role-context.service';

describe('RoleContextService', () => {
  let svc: RoleContextService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    svc = TestBed.inject(RoleContextService);
  });

  // ── isReceptionist (the bug fix this branch is built around) ──

  it('isReceptionist() returns true for the prefixed JWT shape (ROLE_RECEPTIONIST)', () => {
    svc.setRoles(['ROLE_RECEPTIONIST']);
    expect(svc.isReceptionist()).toBeTrue();
  });

  it('isReceptionist() returns true for the bare legacy shape (RECEPTIONIST)', () => {
    svc.setRoles(['RECEPTIONIST']);
    expect(svc.isReceptionist()).toBeTrue();
  });

  it('isReceptionist() returns false when neither shape is present', () => {
    svc.setRoles(['ROLE_DOCTOR', 'ROLE_NURSE']);
    expect(svc.isReceptionist()).toBeFalse();
  });

  it('isReceptionist() returns false on an empty role set', () => {
    svc.setRoles([]);
    expect(svc.isReceptionist()).toBeFalse();
  });

  // ── isSuperAdmin signal ─────────────────────────────────────

  it('isSuperAdmin computes true for ROLE_SUPER_ADMIN', () => {
    svc.setRoles(['ROLE_SUPER_ADMIN']);
    expect(svc.isSuperAdmin()).toBeTrue();
  });

  it('isSuperAdmin computes false for non-admin roles', () => {
    svc.setRoles(['ROLE_DOCTOR']);
    expect(svc.isSuperAdmin()).toBeFalse();
  });

  // ── activeRole auto-set on single-role users ────────────────

  it('setRoles auto-selects activeRole when exactly one role is provided', () => {
    svc.setRoles(['ROLE_NURSE']);
    expect(svc.activeRole).toBe('ROLE_NURSE');
  });

  it('setRoles does not auto-select activeRole for multi-role users', () => {
    svc.setRoles(['ROLE_DOCTOR', 'ROLE_NURSE']);
    expect(svc.activeRole).toBeNull();
  });

  it('activeRole setter and getter round-trip', () => {
    svc.activeRole = 'ROLE_DOCTOR';
    expect(svc.activeRole).toBe('ROLE_DOCTOR');
    svc.activeRole = null;
    expect(svc.activeRole).toBeNull();
  });

  // ── activeHospitalId setter/getter ──────────────────────────

  it('activeHospitalId setter and getter round-trip', () => {
    expect(svc.activeHospitalId).toBeNull();
    svc.activeHospitalId = 'h-1';
    expect(svc.activeHospitalId).toBe('h-1');
    expect(svc.activeHospitalIdSignal()).toBe('h-1');
  });

  // ── permittedHospitalIds ────────────────────────────────────

  it('setPermittedHospitalIds is reflected by the getter', () => {
    expect(svc.permittedHospitalIds).toEqual([]);
    svc.setPermittedHospitalIds(['h-1', 'h-2']);
    expect(svc.permittedHospitalIds).toEqual(['h-1', 'h-2']);
  });

  // ── activeRoles getter ──────────────────────────────────────

  it('activeRoles getter returns the current role list', () => {
    svc.setRoles(['ROLE_DOCTOR', 'ROLE_HOSPITAL_ADMIN']);
    expect(svc.activeRoles).toEqual(['ROLE_DOCTOR', 'ROLE_HOSPITAL_ADMIN']);
  });

  // ── hasRole ─────────────────────────────────────────────────

  it('hasRole returns true for a present role and false otherwise', () => {
    svc.setRoles(['ROLE_DOCTOR']);
    expect(svc.hasRole('ROLE_DOCTOR')).toBeTrue();
    expect(svc.hasRole('ROLE_NURSE')).toBeFalse();
  });
});
