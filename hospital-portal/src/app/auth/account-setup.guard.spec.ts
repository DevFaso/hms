import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, UrlTree } from '@angular/router';

import { AccountSetupGuard } from './account-setup.guard';
import { AuthService, LoginUserProfile } from './auth.service';

function profile(overrides: Partial<LoginUserProfile> = {}): LoginUserProfile {
  return {
    id: 'u1',
    username: 'testuser',
    email: 'test@example.com',
    roles: ['ROLE_DOCTOR'],
    active: true,
    ...overrides,
  };
}

describe('AccountSetupGuard', () => {
  let router: Router;
  let auth: jasmine.SpyObj<AuthService>;

  beforeEach(() => {
    auth = jasmine.createSpyObj<AuthService>('AuthService', ['getUserProfile']);
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: AuthService, useValue: auth }],
    });
    router = TestBed.inject(Router);
  });

  afterEach(() => TestBed.resetTestingModule());

  const run = () =>
    TestBed.runInInjectionContext(() => AccountSetupGuard({} as never, {} as never));

  function expectRedirectToSetup() {
    const result = run();
    expect(result).toEqual(jasmine.any(UrlTree));
    expect(router.serializeUrl(result as UrlTree)).toBe('/account-setup');
  }

  it('admits a user with neither flag set', () => {
    auth.getUserProfile.and.returnValue(profile());
    expect(run()).toBeTrue();
  });

  it('diverts a user who must change their password', () => {
    auth.getUserProfile.and.returnValue(profile({ forcePasswordChange: true }));
    expectRedirectToSetup();
  });

  it('diverts a user who must change their username', () => {
    auth.getUserProfile.and.returnValue(profile({ forceUsernameChange: true }));
    expectRedirectToSetup();
  });

  it('admits when there is no profile yet', () => {
    // A null profile means the session has not resolved one; AuthGuard owns
    // the unauthenticated case, so this guard must not also redirect or the
    // two fight over the same navigation.
    auth.getUserProfile.and.returnValue(null);
    expect(run()).toBeTrue();
  });
});
