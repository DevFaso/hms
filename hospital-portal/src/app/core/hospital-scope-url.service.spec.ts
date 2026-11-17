import { Routes } from '@angular/router';
import { routes } from '../app.routes';
import { Location } from '@angular/common';
import { Component, ChangeDetectionStrategy } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { HospitalScopeUrlService } from './hospital-scope-url.service';
import { GLOBAL_HOSPITAL_HISTORY_STATE } from './hospital-scope-gate.service';
import { RoleContextService } from './role-context.service';

@Component({ standalone: true, template: '', changeDetection: ChangeDetectionStrategy.Eager })
class ScopePage {}

describe('HospitalScopeUrlService history intent', () => {
  let service: HospitalScopeUrlService;
  let ctx: RoleContextService;
  let location: Location;
  let router: Router;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([{ path: 'workspace', component: ScopePage }])],
    });
    service = TestBed.inject(HospitalScopeUrlService);
    ctx = TestBed.inject(RoleContextService);
    location = TestBed.inject(Location);
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(false); // Router skips an identical URL: no NavigationEnd.
  });
  it('records an explicit global choice even if removing hospitalId changes no URL', () => {
    ctx.setRoles(['ROLE_SUPER_ADMIN']);
    ctx.markSuperAdminGlobalDefaults();
    location.replaceState('/workspace?filter=active#ward', '', {
      navigationId: 22,
      caller: 'retained',
      [GLOBAL_HOSPITAL_HISTORY_STATE]: false,
    });
    service.syncScopeToUrl(TestBed.inject(ActivatedRoute), null);
    expect(location.path(true)).toBe('/workspace?filter=active#ward');
    expect(location.getState()).toEqual(
      jasmine.objectContaining({
        navigationId: 22,
        caller: 'retained',
        [GLOBAL_HOSPITAL_HISTORY_STATE]: true,
      }),
    );
  });
  it('records a scoped choice without losing unrelated history state', () => {
    ctx.setRoles(['ROLE_SUPER_ADMIN']);
    ctx.markSuperAdminGlobalDefaults();
    ctx.scopeToHospital('h2');
    location.replaceState('/workspace', '', {
      navigationId: 23,
      caller: 'retained',
      [GLOBAL_HOSPITAL_HISTORY_STATE]: true,
    });
    service.syncScopeToUrl(TestBed.inject(ActivatedRoute), 'h2');
    expect(location.getState()).toEqual(
      jasmine.objectContaining({
        navigationId: 23,
        caller: 'retained',
        [GLOBAL_HOSPITAL_HISTORY_STATE]: false,
      }),
    );
  });
  const gatedPaths: string[] = [];
  function collect(entries: Routes, prefix = ''): void {
    for (const route of entries) {
      const path = `${prefix}/${route.path ?? ''}`.replace(/\/+/g, '/');
      if (route.data?.['requiresHospitalScope']) gatedPaths.push(path);
      if (route.children) collect(route.children, path);
    }
  }
  collect(routes);
  for (const path of gatedPaths) {
    it(`retains explicit global intent on the hospital-gated route ${path}`, () => {
      ctx.setRoles(['ROLE_SUPER_ADMIN']);
      ctx.markSuperAdminGlobalDefaults();
      location.replaceState(path, '', { navigationId: 25, [GLOBAL_HOSPITAL_HISTORY_STATE]: false });
      service.syncScopeToUrl(TestBed.inject(ActivatedRoute), null);
      expect(location.getState()).toEqual(
        jasmine.objectContaining({ [GLOBAL_HOSPITAL_HISTORY_STATE]: true }),
      );
    });
  }

  it('does not add super-admin history state to staff navigation', () => {
    ctx.setRoles(['ROLE_NURSE']);
    ctx.activeHospitalId = 'h1';
    location.replaceState('/workspace', '', { navigationId: 24, caller: 'retained' });
    service.syncScopeToUrl(TestBed.inject(ActivatedRoute), 'h1');
    expect(location.getState()).toEqual({ navigationId: 24, caller: 'retained' });
  });
});
