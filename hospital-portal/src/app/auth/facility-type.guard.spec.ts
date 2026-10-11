import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { WritableSignal, signal } from '@angular/core';
import { ActivatedRouteSnapshot, Router, UrlTree, provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, of, throwError } from 'rxjs';

import { ProviderContextService } from '../core/provider-context.service';
import { RoleContextService } from '../core/role-context.service';
import { ProviderSettings } from '../services/provider.model';
import { settingsFixture } from '../testing/provider-fixtures';
import { FacilityTypeGuard, ProviderHomeRedirectGuard } from './facility-type.guard';

describe('FacilityTypeGuard and ProviderHomeRedirectGuard', () => {
  let settings: ProviderSettings | null;
  let activeRole: string | null;
  let resolved: WritableSignal<boolean>;
  let providerUser: WritableSignal<boolean>;
  let load: jasmine.Spy<() => Observable<ProviderSettings | null>>;
  let reportUnavailable: jasmine.Spy;

  beforeEach(() => {
    settings = settingsFixture();
    activeRole = null;
    resolved = signal(true);
    providerUser = signal(true);
    // The real context answers null until the session has resolved.
    load = jasmine.createSpy('load').and.callFake(() => of(resolved() ? settings : null));
    reportUnavailable = jasmine.createSpy('reportUnavailable');
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: ProviderContextService, useValue: { load, reportUnavailable } },
        {
          provide: RoleContextService,
          useFactory: () => ({
            sessionResolved: resolved,
            providerUser: () => providerUser(),
            get activeRole() {
              return activeRole;
            },
          }),
        },
      ],
    });
  });

  function run(data: Record<string, unknown>): { result: () => boolean | UrlTree | undefined } {
    const route = { data } as unknown as ActivatedRouteSnapshot;
    let result: boolean | UrlTree | undefined;
    TestBed.runInInjectionContext(() => {
      (FacilityTypeGuard(route, {} as never) as Observable<boolean | UrlTree>).subscribe(
        (r) => (result = r),
      );
    });
    return { result: () => result };
  }

  function redirect(): { result: () => boolean | UrlTree | undefined } {
    let result: boolean | UrlTree | undefined;
    TestBed.runInInjectionContext(() => {
      (
        ProviderHomeRedirectGuard({} as ActivatedRouteSnapshot, {} as never) as Observable<
          boolean | UrlTree
        >
      ).subscribe((r) => (result = r));
    });
    return { result: () => result };
  }

  function path(result: boolean | UrlTree | undefined): string {
    return result instanceof UrlTree ? TestBed.inject(Router).serializeUrl(result) : String(result);
  }

  it('admits a provider user of a listed type', () => {
    expect(run({ facilityTypes: ['PHARMACY', 'LABORATORY'] }).result()).toBeTrue();
  });

  it('a deep link opened before the session resolves waits for it, then admits', () => {
    resolved.set(false);
    const guard = run({ facilityTypes: ['PHARMACY'], providerAdmin: true });
    TestBed.tick();

    // Not refused while the scope is unknown: still waiting.
    expect(guard.result()).toBeUndefined();
    expect(load).not.toHaveBeenCalled();

    resolved.set(true);
    TestBed.tick();

    expect(load).toHaveBeenCalledTimes(1);
    expect(guard.result()).toBeTrue();
  });

  it('a transient failure is not a "no": the page opens (it shows the retry), never /error/404', () => {
    load.and.returnValue(throwError(() => new HttpErrorResponse({ status: 503 })));
    expect(run({ facilityTypes: ['PHARMACY'], providerAdmin: true }).result()).toBeTrue();
  });

  it('a provider user the server cannot answer for now goes to the provider home, not the hospital dashboard', () => {
    load.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
    expect(path(redirect().result())).toBe('/provider');
  });

  it('a session that never resolves in time opens the page in its retry state, never /error/404', fakeAsync(() => {
    resolved.set(false);
    const guard = run({ facilityTypes: ['PHARMACY'], providerAdmin: true });
    TestBed.tick();
    expect(guard.result()).toBeUndefined();

    tick(15001);

    expect(guard.result()).toBeTrue();
    expect(reportUnavailable).toHaveBeenCalledTimes(1);
    expect(load).not.toHaveBeenCalled();
  }));

  it('a session timeout redirects nobody: who the user is is unknown, so /dashboard opens', fakeAsync(() => {
    // Nearly every user is hospital staff: a nurse on a slow network must not
    // be stranded on the provider page's "not available" screen.
    resolved.set(false);
    const guard = redirect();
    TestBed.tick();
    expect(guard.result()).toBeUndefined();

    tick(15001);

    expect(guard.result()).toBeTrue();
    expect(reportUnavailable).not.toHaveBeenCalled();
    expect(load).not.toHaveBeenCalled();
  }));

  it('sends anyone who acts at no provider facility to the not-found page', () => {
    settings = null;
    expect(path(run({ facilityTypes: ['PHARMACY'] }).result())).toBe('/error/404');
  });

  it('refuses a facility type the route does not list', () => {
    settings = settingsFixture({ facilityType: 'LABORATORY' });
    expect(path(run({ facilityTypes: ['PHARMACY'] }).result())).toBe('/error/404');
  });

  it('keeps the admin pages for the facility admin', () => {
    expect(run({ facilityTypes: ['PHARMACY'], providerAdmin: true }).result()).toBeTrue();
    settings = settingsFixture({ providerAdmin: false });
    expect(path(run({ facilityTypes: ['PHARMACY'], providerAdmin: true }).result())).toBe(
      '/error/404',
    );
  });

  it('a route without a type list admits any provider facility', () => {
    settings = settingsFixture({ facilityType: 'LABORATORY' });
    expect(run({}).result()).toBeTrue();
  });

  it('redirects a provider user from the dashboard to the provider home', () => {
    expect(path(redirect().result())).toBe('/provider');
  });

  it('a provider user landing before the session resolves still reaches the provider home', () => {
    resolved.set(false);
    const guard = redirect();
    TestBed.tick();
    expect(guard.result()).toBeUndefined();

    resolved.set(true);
    TestBed.tick();

    expect(path(guard.result())).toBe('/provider');
  });

  it('never asks the server for a user the bootstrap did not mark as a provider user', () => {
    // A hospital pharmacist or lab user: straight to the dashboard, no request.
    providerUser.set(false);
    expect(redirect().result()).toBeTrue();
    expect(load).not.toHaveBeenCalled();
  });

  it('leaves the dashboard to a provider user acting as a patient, and to one with no seat', () => {
    activeRole = 'ROLE_PATIENT';
    expect(redirect().result()).toBeTrue();
    expect(load).not.toHaveBeenCalled();
    activeRole = null;
    settings = null;
    expect(redirect().result()).toBeTrue();
  });
});
