import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, provideRouter } from '@angular/router';

import {
  HospitalScopeGateService,
  routeRequiresHospitalScope,
} from './hospital-scope-gate.service';
import { RoleContextService } from './role-context.service';

@Component({ standalone: true, template: '' })
class BlankComponent {}

describe('HospitalScopeGateService', () => {
  let router: Router;
  let roleContext: RoleContextService;
  let gate: HospitalScopeGateService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'open', component: BlankComponent },
          { path: 'gated', component: BlankComponent, data: { requiresHospitalScope: true } },
          {
            path: 'parent',
            data: { roles: ['ROLE_SUPER_ADMIN'] },
            children: [
              { path: 'child', component: BlankComponent, data: { requiresHospitalScope: true } },
            ],
          },
        ]),
      ],
    });
    router = TestBed.inject(Router);
    roleContext = TestBed.inject(RoleContextService);
    gate = TestBed.inject(HospitalScopeGateService);
  });

  function asSuperAdminInGlobalView(): void {
    roleContext.setRoles(['ROLE_SUPER_ADMIN']);
    roleContext.markSuperAdminGlobalDefaults();
  }

  it('is inert on a route without the flag', async () => {
    asSuperAdminInGlobalView();
    await router.navigateByUrl('/open');
    expect(gate.required()).toBeFalse();
    expect(gate.blocked()).toBeFalse();
    expect(gate.showChip()).toBeFalse();
    expect(gate.outletKey()).toBe('static');
  });

  it('blocks a super-admin in global view on a gated route', async () => {
    asSuperAdminInGlobalView();
    await router.navigateByUrl('/gated');
    expect(gate.required()).toBeTrue();
    expect(gate.blocked()).toBeTrue();
    expect(gate.showChip()).toBeTrue();
  });

  it('never blocks staff: their assignment is the scope, and they get no chip', async () => {
    roleContext.setRoles(['ROLE_NURSE']);
    roleContext.activeHospitalId = 'h1';
    await router.navigateByUrl('/gated');
    expect(gate.required()).toBeTrue();
    expect(gate.blocked()).toBeFalse();
    expect(gate.showChip()).toBeFalse();
    expect(gate.outletKey()).toBe('h1');
  });

  it('reads the flag from a child route under an unflagged parent', async () => {
    asSuperAdminInGlobalView();
    await router.navigateByUrl('/parent/child');
    expect(gate.required()).toBeTrue();
    expect(gate.blocked()).toBeTrue();
  });

  it('pins the super-admin from ?hospitalId= so a shared link opens scoped', async () => {
    asSuperAdminInGlobalView();
    await router.navigateByUrl('/gated?hospitalId=h2');
    expect(roleContext.effectiveHospitalIdForRequest()).toBe('h2');
    expect(gate.blocked()).toBeFalse();
    expect(gate.outletKey()).toBe('h2');
  });

  it('keeps the current pick when the URL carries no hospital, so one pick serves every gated page', async () => {
    asSuperAdminInGlobalView();
    roleContext.scopeToHospital('h3');
    await router.navigateByUrl('/gated');
    expect(roleContext.effectiveHospitalIdForRequest()).toBe('h3');
    expect(gate.blocked()).toBeFalse();
  });

  it('unblocks and re-keys the outlet on a pick, and blocks again when the scope is cleared', async () => {
    asSuperAdminInGlobalView();
    await router.navigateByUrl('/gated');
    expect(gate.blocked()).toBeTrue();
    roleContext.scopeToHospital('h4');
    expect(gate.blocked()).toBeFalse();
    expect(gate.outletKey()).toBe('h4');
    roleContext.enableGlobalView();
    expect(gate.blocked()).toBeTrue();
  });

  it('routeRequiresHospitalScope accepts only a literal true', () => {
    const snapshot = {
      data: { requiresHospitalScope: 'yes' },
      firstChild: null,
    } as unknown as ActivatedRouteSnapshot;
    expect(routeRequiresHospitalScope(snapshot)).toBeFalse();
  });
});
