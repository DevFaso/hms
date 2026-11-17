import { Subject } from 'rxjs';
import { Location } from '@angular/common';
import { NavigationStart, NavigationEnd, Event as RouterEvent } from '@angular/router';
import { Component, ChangeDetectionStrategy } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, provideRouter } from '@angular/router';

import {
  HospitalScopeGateService,
  GLOBAL_HOSPITAL_HISTORY_STATE,
  routeRequiresHospitalScope,
} from './hospital-scope-gate.service';
import { RoleContextService } from './role-context.service';

@Component({ standalone: true, changeDetection: ChangeDetectionStrategy.Eager, template: '' })
class BlankComponent {}

const HOSPITAL_A = '11111111-1111-4111-8111-111111111111';
const HOSPITAL_B = '22222222-2222-4222-8222-222222222222';

/** Lets a navigation the gate started from its NavigationEnd handler finish. */
async function settle(router: Router): Promise<void> {
  for (let i = 0; i < 5 && router.getCurrentNavigation(); i++) {
    await new Promise((resolve) => setTimeout(resolve));
  }
  await new Promise((resolve) => setTimeout(resolve));
}

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
            path: 'network',
            component: BlankComponent,
            data: { requiresHospitalScope: true, supportsGlobalHospitalView: true },
          },
          {
            path: 'parent',
            data: { roles: ['ROLE_SUPER_ADMIN'] },
            children: [
              {
                path: 'child',
                component: BlankComponent,
                data: {
                  requiresHospitalScope: true,
                  hospitalNetworkTitleKey: 'ORDER_SETS.ADMIN_TITLE',
                },
              },
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
  });

  it('blocks a super-admin in global view on a gated route', async () => {
    asSuperAdminInGlobalView();
    await router.navigateByUrl('/gated');
    expect(gate.required()).toBeTrue();
    expect(gate.blocked()).toBeTrue();
    expect(gate.showNetworkDirectory()).toBeTrue();
    expect(gate.showChip()).toBeTrue();
  });

  it('never blocks staff: their assignment is the scope, and they get no chip', async () => {
    roleContext.setRoles(['ROLE_NURSE']);
    roleContext.activeHospitalId = 'h1';
    await router.navigateByUrl('/gated');
    expect(gate.required()).toBeTrue();
    expect(gate.blocked()).toBeFalse();
    expect(gate.showChip()).toBeFalse();
    expect(gate.showNetworkDirectory()).toBeFalse();
  });

  it('allows an explicitly supported global read without weakening facility-only routes', async () => {
    asSuperAdminInGlobalView();
    await router.navigateByUrl('/network');
    expect(gate.blocked()).toBeFalse();
    expect(gate.allowsGlobal()).toBeTrue();
    expect(gate.showChip()).toBeTrue();
    await router.navigateByUrl('/gated');
    expect(gate.allowsGlobal()).toBeFalse();
    expect(gate.blocked()).toBeTrue();
  });

  it('waits for explicit global mode when a super-admin has no resolved hospital', async () => {
    roleContext.setRoles(['ROLE_SUPER_ADMIN']);
    await router.navigateByUrl('/network');
    expect(roleContext.globalView()).toBeFalse();
    expect(gate.blocked()).toBeTrue();
    roleContext.markSuperAdminGlobalDefaults();
    expect(gate.blocked()).toBeFalse();
  });

  it('does not grant an unassigned non-super-admin the global capability', async () => {
    roleContext.setRoles(['ROLE_NURSE']);
    await router.navigateByUrl('/network');
    expect(gate.blocked()).toBeTrue();
  });

  it('rebuilds the outlet between a network overview and a selected hospital', async () => {
    asSuperAdminInGlobalView();
    await router.navigateByUrl('/network');
    TestBed.tick();
    const networkKey = gate.outletKey();
    roleContext.scopeToHospital('h1');
    TestBed.tick();
    expect(gate.outletKey()).not.toBe(networkKey);
    const scopedKey = gate.outletKey();
    roleContext.enableGlobalView();
    TestBed.tick();
    expect(gate.outletKey()).not.toBe(scopedKey);
    expect(gate.blocked()).toBeFalse();
  });

  it('reads the flag from a child route under an unflagged parent', async () => {
    asSuperAdminInGlobalView();
    await router.navigateByUrl('/parent/child');
    expect(gate.required()).toBeTrue();
    expect(gate.blocked()).toBeTrue();
    expect(gate.moduleTitleKey()).toBe('ORDER_SETS.ADMIN_TITLE');
    await router.navigateByUrl('/open');
    expect(gate.moduleTitleKey()).toBeNull();
  });

  it('pins the super-admin from ?hospitalId= so a shared link opens scoped', async () => {
    asSuperAdminInGlobalView();
    await router.navigateByUrl(`/gated?hospitalId=${HOSPITAL_B}`);
    expect(roleContext.effectiveHospitalIdForRequest()).toBe(HOSPITAL_B);
    expect(gate.blocked()).toBeFalse();
  });

  describe('QA D2121: the URL, the scope and the view agree after a reload or deep link', () => {
    it('a super-admin reload keeps the URL hospital when the session bootstrap answers late', async () => {
      // app.component on a hard refresh: token roles, stored profile, defaults.
      asSuperAdminInGlobalView();
      await router.navigateByUrl(`/gated?hospitalId=${HOSPITAL_B}`);
      // The GET /auth/session/bootstrap reply lands after the first navigation.
      roleContext.markSuperAdminGlobalDefaults();
      expect(roleContext.globalView()).toBeFalse();
      expect(roleContext.effectiveHospitalIdForRequest()).toBe(HOSPITAL_B);
      expect(gate.blocked()).toBeFalse();
      expect(gate.showNetworkDirectory()).toBeFalse();
      expect(router.url).toBe(`/gated?hospitalId=${HOSPITAL_B}`);
    });

    it('moving between scoped links in one tab follows each link', async () => {
      asSuperAdminInGlobalView();
      await router.navigateByUrl(`/gated?hospitalId=${HOSPITAL_A}`);
      await router.navigateByUrl(`/parent/child?hospitalId=${HOSPITAL_B}`);
      roleContext.markSuperAdminGlobalDefaults();
      expect(roleContext.effectiveHospitalIdForRequest()).toBe(HOSPITAL_B);
      expect(gate.showNetworkDirectory()).toBeFalse();
    });

    it('a super-admin link with a malformed hospital id is dropped and the directory stays', async () => {
      asSuperAdminInGlobalView();
      await router.navigateByUrl('/gated?hospitalId=not-a-hospital&filter=x');
      await settle(router);
      expect(router.url).toBe('/gated?filter=x');
      expect(roleContext.globalView()).toBeTrue();
      expect(roleContext.effectiveHospitalIdForRequest()).toBeNull();
      expect(gate.showNetworkDirectory()).toBeTrue();
    });

    it('a hospital-scoped account keeps a link naming its own hospital', async () => {
      roleContext.setRoles(['ROLE_HOSPITAL_ADMIN']);
      roleContext.activeHospitalId = HOSPITAL_A;
      await router.navigateByUrl(`/gated?hospitalId=${HOSPITAL_A}`);
      await settle(router);
      expect(router.url).toBe(`/gated?hospitalId=${HOSPITAL_A}`);
      expect(roleContext.effectiveHospitalIdForRequest()).toBe(HOSPITAL_A);
    });

    for (const path of ['/gated', '/open']) {
      it(`a hospital-scoped account never keeps another hospital in the URL (${path})`, async () => {
        roleContext.setRoles(['ROLE_HOSPITAL_ADMIN']);
        roleContext.setPermittedHospitalIds([HOSPITAL_A, HOSPITAL_B]);
        roleContext.activeHospitalId = HOSPITAL_A;
        const scope = spyOn(roleContext, 'scopeToHospital').and.callThrough();
        await router.navigateByUrl(`${path}?hospitalId=${HOSPITAL_B}&tab=2`);
        await settle(router);
        expect(router.url).toBe(`${path}?tab=2`);
        expect(scope).not.toHaveBeenCalled();
        expect(roleContext.effectiveHospitalIdForRequest()).toBe(HOSPITAL_A);
        expect(gate.blocked()).toBeFalse();
      });
    }

    it('leaves the URL alone before anyone is signed in', async () => {
      await router.navigateByUrl(`/open?hospitalId=${HOSPITAL_B}`);
      await settle(router);
      expect(router.url).toBe(`/open?hospitalId=${HOSPITAL_B}`);
    });
  });

  it('keeps the current pick when the URL carries no hospital, so one pick serves every gated page', async () => {
    asSuperAdminInGlobalView();
    roleContext.scopeToHospital('h3');
    await router.navigateByUrl('/gated');
    expect(roleContext.effectiveHospitalIdForRequest()).toBe('h3');
    expect(gate.blocked()).toBeFalse();
  });

  it('unblocks on a pick and blocks again when the scope is cleared', async () => {
    asSuperAdminInGlobalView();
    await router.navigateByUrl('/gated');
    expect(gate.blocked()).toBeTrue();
    roleContext.scopeToHospital('h4');
    expect(gate.blocked()).toBeFalse();
    roleContext.enableGlobalView();
    expect(gate.blocked()).toBeTrue();
  });

  it('re-keys the outlet on a scope change while gated, never on navigation alone', async () => {
    roleContext.setRoles(['ROLE_SUPER_ADMIN']);
    roleContext.activeHospitalId = 'primary';
    roleContext.markSuperAdminGlobalDefaults();
    roleContext.scopeToHospital('h1');
    TestBed.tick();
    const initial = gate.outletKey();

    await router.navigateByUrl('/gated');
    await router.navigateByUrl('/open');
    await router.navigateByUrl('/parent/child');
    TestBed.tick();
    expect(gate.outletKey())
      .withContext('navigation between gated and ungated routes')
      .toBe(initial);

    roleContext.scopeToHospital('h2');
    TestBed.tick();
    expect(gate.outletKey()).withContext('a pick on a gated route').not.toBe(initial);
    const afterPick = gate.outletKey();

    await router.navigateByUrl('/open');
    roleContext.scopeToHospital('h3');
    TestBed.tick();
    expect(gate.outletKey())
      .withContext('a pick on an ungated route, which reloads itself')
      .toBe(afterPick);
  });

  it('restores global view on browser history navigation without a hospital query', async () => {
    asSuperAdminInGlobalView();
    await router.navigateByUrl('/gated');
    roleContext.scopeToHospital('h2');
    const events = router.events as Subject<RouterEvent>;
    events.next(
      new NavigationStart(100, '/gated', 'popstate', {
        navigationId: 1,
        [GLOBAL_HOSPITAL_HISTORY_STATE]: true,
      }),
    );
    events.next(new NavigationEnd(100, '/gated', '/gated'));
    expect(roleContext.globalView()).toBeTrue();
    expect(gate.showNetworkDirectory()).toBeTrue();
  });

  for (const marker of [undefined, false]) {
    it(`keeps a session hospital on Back/Forward with history marker ${marker}`, async () => {
      asSuperAdminInGlobalView();
      roleContext.scopeToHospital('h2');
      await router.navigateByUrl('/gated');
      await router.navigateByUrl('/parent/child');
      const events = router.events as Subject<RouterEvent>;
      events.next(
        new NavigationStart(100, '/gated', 'popstate', {
          navigationId: 1,
          [GLOBAL_HOSPITAL_HISTORY_STATE]: marker,
        }),
      );
      events.next(new NavigationEnd(100, '/gated', '/gated'));
      expect(roleContext.effectiveHospitalIdForRequest()).toBe('h2');
      expect(gate.showNetworkDirectory()).toBeFalse();
    });
  }

  it('stamps global and scoped entries without losing caller navigation state', async () => {
    asSuperAdminInGlobalView();
    await router.navigateByUrl('/gated?filter=active#section', { state: { caller: 'retained' } });
    const location = TestBed.inject(Location);
    expect(location.path(true)).toBe('/gated?filter=active#section');
    expect(location.getState()).toEqual(
      jasmine.objectContaining({
        caller: 'retained',
        [GLOBAL_HOSPITAL_HISTORY_STATE]: true,
      }),
    );
    await router.navigateByUrl(`/gated?hospitalId=${HOSPITAL_B}`);
    expect(location.getState()).toEqual(
      jasmine.objectContaining({
        [GLOBAL_HOSPITAL_HISTORY_STATE]: false,
      }),
    );
  });

  it('ignores even a global history marker for staff', async () => {
    roleContext.setRoles(['ROLE_NURSE']);
    roleContext.activeHospitalId = 'h1';
    await router.navigateByUrl('/gated');
    const global = spyOn(roleContext, 'enableGlobalView');
    const events = router.events as Subject<RouterEvent>;
    events.next(
      new NavigationStart(100, '/gated', 'popstate', {
        navigationId: 1,
        [GLOBAL_HOSPITAL_HISTORY_STATE]: true,
      }),
    );
    events.next(new NavigationEnd(100, '/gated', '/gated'));
    expect(global).not.toHaveBeenCalled();
    expect(roleContext.effectiveHospitalIdForRequest()).toBe('h1');
  });
  it('does not offer the network directory to a staff account without an active hospital', async () => {
    roleContext.setRoles(['ROLE_NURSE']);
    roleContext.activeHospitalId = null;
    await router.navigateByUrl('/gated');
    expect(gate.blocked()).toBeTrue();
    expect(gate.showNetworkDirectory()).toBeFalse();
  });

  it('routeRequiresHospitalScope accepts only a literal true', () => {
    const snapshot = {
      data: { requiresHospitalScope: 'yes' },
      firstChild: null,
    } as unknown as ActivatedRouteSnapshot;
    expect(routeRequiresHospitalScope(snapshot)).toBeFalse();
  });
});
