import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, UrlTree } from '@angular/router';

import { AuthGuard } from './auth.guard';
import { AuthService } from './auth.service';

describe('AuthGuard', () => {
  let router: Router;
  let auth: jasmine.SpyObj<AuthService>;

  beforeEach(() => {
    auth = jasmine.createSpyObj<AuthService>('AuthService', ['isAuthenticated']);
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: AuthService, useValue: auth }],
    });
    router = TestBed.inject(Router);
  });

  afterEach(() => TestBed.resetTestingModule());

  const run = (url = '/') =>
    TestBed.runInInjectionContext(() => AuthGuard({} as never, { url } as never));

  it('admits an authenticated caller', () => {
    auth.isAuthenticated.and.returnValue(true);
    expect(run()).toBeTrue();
  });

  it('redirects an anonymous caller to /login instead of returning false', () => {
    // Returning false would strand the user on a blank route with no way
    // back — the guard hands back a UrlTree so the router navigates.
    auth.isAuthenticated.and.returnValue(false);

    const result = run();

    expect(result).toEqual(jasmine.any(UrlTree));
    expect(router.serializeUrl(result as UrlTree)).toBe('/login?returnUrl=%2F');
  });

  it('carries the attempted URL so an emailed deep link survives login', () => {
    // Every deep link this app emits arrives by email, so the recipient is
    // usually signed out when they click. Dropping the target here is what
    // made those links look like they did nothing.
    auth.isAuthenticated.and.returnValue(false);

    const result = run('/my-appointments?cancel=appt-1');

    expect(router.serializeUrl(result as UrlTree)).toBe(
      '/login?returnUrl=%2Fmy-appointments%3Fcancel%3Dappt-1',
    );
  });
});
