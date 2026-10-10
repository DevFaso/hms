import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, UrlTree, provideRouter } from '@angular/router';
import { Observable, of } from 'rxjs';

import { ProviderContextService } from '../core/provider-context.service';
import { RoleContextService } from '../core/role-context.service';
import { ProviderSettings } from '../services/provider.model';
import { settingsFixture } from '../testing/provider-fixtures';
import { FacilityTypeGuard, ProviderHomeRedirectGuard } from './facility-type.guard';

describe('FacilityTypeGuard and ProviderHomeRedirectGuard', () => {
  let settings: ProviderSettings | null;
  let activeRole: string | null;

  beforeEach(() => {
    settings = settingsFixture();
    activeRole = null;
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: ProviderContextService, useValue: { load: () => of(settings) } },
        {
          provide: RoleContextService,
          useValue: {
            get activeRole() {
              return activeRole;
            },
          },
        },
      ],
    });
  });

  function run(data: Record<string, unknown>): boolean | UrlTree {
    const route = { data } as unknown as ActivatedRouteSnapshot;
    let result: boolean | UrlTree = false;
    TestBed.runInInjectionContext(() => {
      (FacilityTypeGuard(route, {} as never) as Observable<boolean | UrlTree>).subscribe(
        (r) => (result = r),
      );
    });
    return result;
  }

  function redirect(): boolean | UrlTree {
    let result: boolean | UrlTree = false;
    TestBed.runInInjectionContext(() => {
      (
        ProviderHomeRedirectGuard({} as ActivatedRouteSnapshot, {} as never) as Observable<
          boolean | UrlTree
        >
      ).subscribe((r) => (result = r));
    });
    return result;
  }

  function path(result: boolean | UrlTree): string {
    return result instanceof UrlTree ? TestBed.inject(Router).serializeUrl(result) : String(result);
  }

  it('admits a provider user of a listed type', () => {
    expect(run({ facilityTypes: ['PHARMACY', 'LABORATORY'] })).toBeTrue();
  });

  it('sends anyone who acts at no provider facility to the not-found page', () => {
    settings = null;
    expect(path(run({ facilityTypes: ['PHARMACY'] }))).toBe('/error/404');
  });

  it('refuses a facility type the route does not list', () => {
    settings = settingsFixture({ facilityType: 'LABORATORY' });
    expect(path(run({ facilityTypes: ['PHARMACY'] }))).toBe('/error/404');
  });

  it('keeps the admin pages for the facility admin', () => {
    expect(run({ facilityTypes: ['PHARMACY'], providerAdmin: true })).toBeTrue();
    settings = settingsFixture({ providerAdmin: false });
    expect(path(run({ facilityTypes: ['PHARMACY'], providerAdmin: true }))).toBe('/error/404');
  });

  it('a route without a type list admits any provider facility', () => {
    settings = settingsFixture({ facilityType: 'LABORATORY' });
    expect(run({})).toBeTrue();
  });

  it('redirects a provider user from the dashboard to the provider home', () => {
    expect(path(redirect())).toBe('/provider');
  });

  it('leaves the dashboard to everyone else, and to a provider user acting as a patient', () => {
    activeRole = 'ROLE_PATIENT';
    expect(redirect()).toBeTrue();
    activeRole = null;
    settings = null;
    expect(redirect()).toBeTrue();
  });
});
