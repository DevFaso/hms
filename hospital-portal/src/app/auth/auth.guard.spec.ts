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

  const run = () => TestBed.runInInjectionContext(() => AuthGuard({} as never, {} as never));

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
    expect(router.serializeUrl(result as UrlTree)).toBe('/login');
  });
});
