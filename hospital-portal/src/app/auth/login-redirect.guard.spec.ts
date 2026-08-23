import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, UrlTree } from '@angular/router';

import { LoginRedirectGuard } from './login-redirect.guard';
import { AuthService } from './auth.service';

describe('LoginRedirectGuard', () => {
  let router: Router;
  let auth: jasmine.SpyObj<AuthService>;

  beforeEach(() => {
    auth = jasmine.createSpyObj<AuthService>('AuthService', [
      'isAuthenticated',
      'resolveLandingPath',
    ]);
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: AuthService, useValue: auth }],
    });
    router = TestBed.inject(Router);
  });

  afterEach(() => TestBed.resetTestingModule());

  const run = () =>
    TestBed.runInInjectionContext(() => LoginRedirectGuard({} as never, {} as never));

  it('lets an anonymous caller reach the login page', () => {
    auth.isAuthenticated.and.returnValue(false);
    expect(run()).toBeTrue();
    expect(auth.resolveLandingPath).not.toHaveBeenCalled();
  });

  it('bounces a signed-in caller to their own landing path, not a fixed route', () => {
    // Roles land in different places (super admins on the control tower,
    // patients on their portal), so the destination comes from the service
    // rather than being hard-coded here.
    auth.isAuthenticated.and.returnValue(true);
    auth.resolveLandingPath.and.returnValue('/super-admin');

    const result = run();

    expect(result).toEqual(jasmine.any(UrlTree));
    expect(router.serializeUrl(result as UrlTree)).toBe('/super-admin');
  });

  it('honours a patient landing path just as readily', () => {
    auth.isAuthenticated.and.returnValue(true);
    auth.resolveLandingPath.and.returnValue('/dashboard');

    expect(router.serializeUrl(run() as UrlTree)).toBe('/dashboard');
  });
});
