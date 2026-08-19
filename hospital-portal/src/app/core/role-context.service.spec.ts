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

  // ── Cross-tenant scope (super-admin global view) ────────────
  //   docs/super-admin-cross-tenant-design.md

  it('starts with globalView=false and selectedHospitalId=null by default', () => {
    expect(svc.globalView()).toBeFalse();
    expect(svc.selectedHospitalId()).toBeNull();
  });

  it('markSuperAdminGlobalDefaults flips globalView for SUPER_ADMIN only', () => {
    svc.setRoles(['ROLE_SUPER_ADMIN']);
    svc.markSuperAdminGlobalDefaults();
    expect(svc.globalView()).toBeTrue();
    expect(svc.selectedHospitalId()).toBeNull();
  });

  it('markSuperAdminGlobalDefaults is a no-op for non-super-admin roles', () => {
    svc.setRoles(['ROLE_DOCTOR']);
    svc.markSuperAdminGlobalDefaults();
    expect(svc.globalView()).toBeFalse();
  });

  it('scopeToHospital pins the user to a hospital and clears globalView', () => {
    svc.setRoles(['ROLE_SUPER_ADMIN']);
    svc.markSuperAdminGlobalDefaults();
    svc.scopeToHospital('hosp-42');
    expect(svc.globalView()).toBeFalse();
    expect(svc.selectedHospitalId()).toBe('hosp-42');
  });

  it('scopeToHospital ignores empty hospital IDs', () => {
    svc.setRoles(['ROLE_SUPER_ADMIN']);
    svc.scopeToHospital('hosp-42');
    svc.scopeToHospital('');
    expect(svc.selectedHospitalId()).toBe('hosp-42');
  });

  it('enableGlobalView clears the selected hospital', () => {
    svc.setRoles(['ROLE_SUPER_ADMIN']);
    svc.scopeToHospital('hosp-42');
    svc.enableGlobalView();
    expect(svc.globalView()).toBeTrue();
    expect(svc.selectedHospitalId()).toBeNull();
  });

  // ── effectiveHospitalIdForRequest (auth interceptor contract) ──
  //   The interceptor uses this signal as the X-Hospital-Id header
  //   value. A null result means "omit the header entirely".

  it('effectiveHospitalIdForRequest is null for super-admin in global view', () => {
    svc.setRoles(['ROLE_SUPER_ADMIN']);
    svc.markSuperAdminGlobalDefaults();
    expect(svc.effectiveHospitalIdForRequest()).toBeNull();
  });

  it('effectiveHospitalIdForRequest returns the selected hospital for scoped super-admin', () => {
    svc.setRoles(['ROLE_SUPER_ADMIN']);
    svc.scopeToHospital('hosp-42');
    expect(svc.effectiveHospitalIdForRequest()).toBe('hosp-42');
  });

  it('effectiveHospitalIdForRequest returns activeHospitalId for non-super-admin', () => {
    svc.setRoles(['ROLE_DOCTOR']);
    svc.activeHospitalId = 'hosp-7';
    expect(svc.effectiveHospitalIdForRequest()).toBe('hosp-7');
  });

  it('effectiveHospitalIdForRequest never leaks selectedHospitalId for non-super-admin', () => {
    // Defensive: even if selectedHospitalId somehow got set on a non-super-admin
    // (it shouldn't — `scopeToHospital` is gated by the chip, which only renders
    // for super-admins — but the signal's mutator doesn't itself check the role),
    // the computed must still return the user's regular activeHospitalId.
    svc.setRoles(['ROLE_NURSE']);
    svc.activeHospitalId = 'hosp-7';
    svc.scopeToHospital('hosp-99');
    expect(svc.effectiveHospitalIdForRequest()).toBe('hosp-7');
  });
});
