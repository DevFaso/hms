import { HttpErrorResponse } from '@angular/common/http';
import { fakeAsync, TestBed, tick } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { AuthService, LoginUserProfile } from '../auth/auth.service';
import { ProfileService } from '../services/profile.service';
import { AccountSetupComponent, SETUP_SIGN_OUT_DELAY_MS } from './account-setup';

function profile(overrides: Partial<LoginUserProfile> = {}): LoginUserProfile {
  return {
    id: 'u1',
    username: 'invitee-tmp',
    email: 'invitee@example.com',
    roles: ['ROLE_NURSE'],
    active: true,
    ...overrides,
  };
}

describe('AccountSetupComponent', () => {
  let auth: jasmine.SpyObj<AuthService>;
  let profiles: jasmine.SpyObj<ProfileService>;
  let router: Router;

  function create(p: LoginUserProfile | null) {
    auth.getUserProfile.and.returnValue(p);
    const fixture = TestBed.createComponent(AccountSetupComponent);
    fixture.detectChanges();
    return fixture;
  }

  function fill(
    c: AccountSetupComponent,
    values: Partial<
      Pick<
        AccountSetupComponent,
        'newUsername' | 'currentPassword' | 'newPassword' | 'confirmPassword'
      >
    >,
  ) {
    Object.assign(c, values);
  }

  beforeEach(() => {
    auth = jasmine.createSpyObj<AuthService>('AuthService', [
      'getUserProfile',
      'logout',
      'resolveLandingPath',
    ]);
    auth.resolveLandingPath.and.returnValue('/dashboard');
    profiles = jasmine.createSpyObj<ProfileService>('ProfileService', [
      'completeSetup',
      'changeUsername',
      'changePassword',
    ]);
    TestBed.configureTestingModule({
      imports: [AccountSetupComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: auth },
        { provide: ProfileService, useValue: profiles },
      ],
    });
    router = TestBed.inject(Router);
    spyOn(router, 'navigateByUrl').and.resolveTo(true);
  });

  afterEach(() => TestBed.resetTestingModule());

  it('D17: shows ONE save button when both the username and the password are forced', () => {
    const fixture = create(profile({ forceUsernameChange: true, forcePasswordChange: true }));
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelectorAll('button[type="submit"]').length).toBe(1);
    expect(el.querySelector('#newUsername')).not.toBeNull();
    expect(el.querySelector('#newPassword')).not.toBeNull();
  });

  it('D17: sends the username and the password in one call, then signs out', fakeAsync(() => {
    profiles.completeSetup.and.returnValue(of({ message: 'ok' }));
    const fixture = create(profile({ forceUsernameChange: true, forcePasswordChange: true }));
    const c = fixture.componentInstance;
    fill(c, {
      newUsername: '  awa.traore ',
      currentPassword: 'Temp-Pass-1!',
      newPassword: 'Brand-New-2!',
      confirmPassword: 'Brand-New-2!',
    });

    c.submit();

    expect(profiles.completeSetup).toHaveBeenCalledOnceWith({
      newUsername: 'awa.traore',
      currentPassword: 'Temp-Pass-1!',
      newPassword: 'Brand-New-2!',
    });
    expect(profiles.changeUsername).not.toHaveBeenCalled();
    expect(profiles.changePassword).not.toHaveBeenCalled();
    expect(c.done).toBeTrue();
    expect(c.currentPassword).toBe('');
    expect(auth.logout).not.toHaveBeenCalled();

    tick(SETUP_SIGN_OUT_DELAY_MS);
    expect(auth.logout).toHaveBeenCalled();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/login');
  }));

  it('sends no username when only the password is forced', () => {
    profiles.completeSetup.and.returnValue(of({ message: 'ok' }));
    const c = create(profile({ forcePasswordChange: true })).componentInstance;
    fill(c, {
      newUsername: 'ignored',
      currentPassword: 'Temp-Pass-1!',
      newPassword: 'Brand-New-2!',
      confirmPassword: 'Brand-New-2!',
    });

    c.submit();

    expect(profiles.completeSetup).toHaveBeenCalledOnceWith({
      newUsername: undefined,
      currentPassword: 'Temp-Pass-1!',
      newPassword: 'Brand-New-2!',
    });
  });

  it('refuses mismatched passwords before calling the server', () => {
    const c = create(profile({ forcePasswordChange: true })).componentInstance;
    fill(c, {
      currentPassword: 'Temp-Pass-1!',
      newPassword: 'Brand-New-2!',
      confirmPassword: 'Brand-New-3!',
    });

    c.submit();

    expect(profiles.completeSetup).not.toHaveBeenCalled();
    expect(c.error).toBe('SETUP.ERR_PASSWORD_MISMATCH');
  });

  it('D22: a refused hospital scope shows the translated "role not confirmed" guidance', () => {
    profiles.completeSetup.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 403,
            error: {
              code: 'hospital_scope_refused',
              reason: 'NO_LONGER_PERMITTED',
              message: 'Access Denied: You do not have an active role in the requested hospital.',
            },
          }),
      ),
    );
    const fixture = create(profile({ forcePasswordChange: true }));
    const c = fixture.componentInstance;
    fill(c, {
      currentPassword: 'Temp-Pass-1!',
      newPassword: 'Brand-New-2!',
      confirmPassword: 'Brand-New-2!',
    });

    c.submit();
    fixture.detectChanges();

    expect(c.roleNotConfirmed).toBeTrue();
    expect(c.error).toBe('SETUP.ROLE_NOT_CONFIRMED');
    expect(fixture.nativeElement.textContent).not.toContain('Access Denied');
  });

  it('shows the server sentence for a 400 (wrong current password)', () => {
    profiles.completeSetup.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 400,
            error: { message: 'Le mot de passe actuel est incorrect.' },
          }),
      ),
    );
    const c = create(profile({ forcePasswordChange: true })).componentInstance;
    fill(c, {
      currentPassword: 'Wrong-1!',
      newPassword: 'Brand-New-2!',
      confirmPassword: 'Brand-New-2!',
    });

    c.submit();

    expect(c.error).toBe('Le mot de passe actuel est incorrect.');
    expect(c.done).toBeFalse();
  });

  it('falls back to a translated generic error', () => {
    profiles.completeSetup.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 500, error: { message: 'boom' } })),
    );
    const c = create(profile({ forceUsernameChange: true })).componentInstance;
    fill(c, { newUsername: 'awa.traore', currentPassword: 'Temp-Pass-1!' });

    c.submit();

    expect(c.error).toBe('SETUP.ERR_GENERIC');
  });

  it('validates the username', () => {
    const c = create(profile({ forceUsernameChange: true })).componentInstance;
    fill(c, { currentPassword: 'x', newUsername: 'ab' });
    expect(c.validationError()).toBe('SETUP.ERR_USERNAME_LENGTH');
    c.newUsername = 'bad name';
    expect(c.validationError()).toBe('SETUP.ERR_USERNAME_CHARS');
    c.newUsername = 'good.name';
    expect(c.validationError()).toBeNull();
    c.currentPassword = '';
    expect(c.validationError()).toBe('SETUP.ERR_CURRENT_REQUIRED');
  });

  it('validates the new password length and difference', () => {
    const c = create(profile({ forcePasswordChange: true })).componentInstance;
    fill(c, { currentPassword: 'Same-Pass-1!', newPassword: 'short', confirmPassword: 'short' });
    expect(c.validationError()).toBe('SETUP.ERR_PASSWORD_LENGTH');
    fill(c, { newPassword: 'Same-Pass-1!', confirmPassword: 'Same-Pass-1!' });
    expect(c.validationError()).toBe('SETUP.ERR_PASSWORD_SAME');
  });

  it('canSubmit needs every required field', () => {
    const c = create(
      profile({ forceUsernameChange: true, forcePasswordChange: true }),
    ).componentInstance;
    expect(c.canSubmit).toBeFalse();
    fill(c, { currentPassword: 'a', newUsername: 'u', newPassword: 'b' });
    expect(c.canSubmit).toBeFalse();
    c.confirmPassword = 'b';
    expect(c.canSubmit).toBeTrue();
  });

  it('goes to the landing page when nothing is forced, and to login with no profile', () => {
    create(profile());
    expect(router.navigateByUrl).toHaveBeenCalledWith('/dashboard');
    create(null);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/login');
  });
});
