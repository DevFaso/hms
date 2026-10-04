import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { MfaChallengeComponent } from './mfa-challenge';
import { AuthService } from '../auth/auth.service';
import { MfaService, MfaVerifyLoginResponse } from '../auth/mfa.service';
import { RoleContextService } from '../core/role-context.service';
import { SessionScopeService } from '../core/session-scope.service';

/**
 * The second factor at sign-in. Asserted through the rendered DOM in French,
 * the shape #661 settled on: a message only counts once the page shows it in
 * the user's language, not when a signal holds a key.
 */
describe('MfaChallengeComponent', () => {
  let fixture: ComponentFixture<MfaChallengeComponent>;
  let mfa: jasmine.SpyObj<MfaService>;
  let auth: jasmine.SpyObj<AuthService>;
  let roleContext: jasmine.SpyObj<RoleContextService>;
  let sessionScope: jasmine.SpyObj<SessionScopeService>;
  let router: { getCurrentNavigation: jasmine.Spy; navigateByUrl: jasmine.Spy };

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const text = (selector: string): string =>
    host().querySelector(selector)?.textContent?.trim() ?? '';

  function create(state: Record<string, unknown> | undefined): void {
    router.getCurrentNavigation.and.returnValue(state ? { extras: { state } } : null);
    fixture = TestBed.createComponent(MfaChallengeComponent);
    fixture.detectChanges();
  }

  function submit(code: string): void {
    fixture.componentInstance.code = code;
    host().querySelector('form')!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();
  }

  beforeEach(() => {
    mfa = jasmine.createSpyObj<MfaService>('MfaService', ['verifyLogin']);
    auth = jasmine.createSpyObj<AuthService>('AuthService', [
      'setToken',
      'setRefreshToken',
      'getRoles',
      'resolveLandingPath',
      'setUserProfile',
    ]);
    auth.getRoles.and.returnValue(['ROLE_DOCTOR']);
    auth.resolveLandingPath.and.returnValue('/dashboard');
    roleContext = jasmine.createSpyObj<RoleContextService>('RoleContextService', ['setRoles']);
    sessionScope = jasmine.createSpyObj<SessionScopeService>('SessionScopeService', [
      'hydrate',
      'applyScope',
    ]);
    router = {
      getCurrentNavigation: jasmine.createSpy('getCurrentNavigation'),
      navigateByUrl: jasmine.createSpy('navigateByUrl'),
    };

    TestBed.configureTestingModule({
      imports: [MfaChallengeComponent, TranslateModule.forRoot()],
      providers: [
        { provide: MfaService, useValue: mfa },
        { provide: AuthService, useValue: auth },
        { provide: RoleContextService, useValue: roleContext },
        { provide: SessionScopeService, useValue: sessionScope },
        { provide: Router, useValue: router },
      ],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setFallbackLang('fr');
    translate.use('fr');
    translate.setTranslation('fr', {
      MFA: {
        CHALLENGE_TITLE: 'Vérification en deux étapes',
        CHALLENGE_SIGNED_IN_AS: 'Connecté en tant que {{username}}',
        CHALLENGE_NOT_ENROLLED: 'La double authentification n’est pas configurée.',
        CHALLENGE_INVALID_CODE: 'Saisissez un code à 6 chiffres.',
        CHALLENGE_WRONG_CODE: 'Code incorrect.',
      },
    });
  });

  it('sends anyone who arrived without an MFA token back to sign-in', () => {
    create(undefined);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/login');
  });

  it('shows the translated title and who is signing in', () => {
    create({ mfaToken: 'mfa-1', mfaEnrolled: true, username: 'awa' });
    expect(router.navigateByUrl).not.toHaveBeenCalled();
    expect(text('h1')).toBe('Vérification en deux étapes');
    expect(text('.subtitle')).toBe('Connecté en tant que awa');
    expect(host().querySelector('input[name="mfaCode"]')).not.toBeNull();
  });

  it('offers only the way back when the account has no second factor enrolled', () => {
    create({ mfaToken: 'mfa-1', mfaEnrolled: false });
    expect(host().querySelector('form')).toBeNull();
    expect(host().textContent).toContain('La double authentification n’est pas configurée.');
  });

  it('refuses a short code on screen without calling the server', () => {
    create({ mfaToken: 'mfa-1', mfaEnrolled: true });
    submit('123');
    expect(mfa.verifyLogin).not.toHaveBeenCalled();
    expect(text('[role="alert"]')).toBe('Saisissez un code à 6 chiffres.');
  });

  it('shows the translated fallback when the server refuses without a message', () => {
    mfa.verifyLogin.and.returnValue(throwError(() => ({ status: 401 })));
    create({ mfaToken: 'mfa-1', mfaEnrolled: true });
    submit('123456');
    expect(mfa.verifyLogin).toHaveBeenCalledWith('mfa-1', '123456');
    expect(text('[role="alert"]')).toBe('Code incorrect.');
    expect(fixture.componentInstance.loading()).toBeFalse();
  });

  it('completes the sign-in on a good code and lands on the role home', () => {
    const res: MfaVerifyLoginResponse = { accessToken: 'jwt', refreshToken: 'r' };
    mfa.verifyLogin.and.returnValue(of(res));
    sessionScope.hydrate.and.returnValue(of(null));
    create({ mfaToken: 'mfa-1', mfaEnrolled: true });
    submit('123456');

    expect(auth.setToken).toHaveBeenCalledWith('jwt', true);
    expect(auth.setRefreshToken).toHaveBeenCalledWith('r', true);
    expect(roleContext.setRoles).toHaveBeenCalledWith(['ROLE_DOCTOR']);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/dashboard');
    expect(host().querySelector('[role="alert"]')).toBeNull();
  });

  it('sends a user who must still change credentials to account setup', () => {
    mfa.verifyLogin.and.returnValue(of({ accessToken: 'jwt', forcePasswordChange: true }));
    sessionScope.hydrate.and.returnValue(of(null));
    create({ mfaToken: 'mfa-1', mfaEnrolled: true });
    submit('123456');
    expect(router.navigateByUrl).toHaveBeenCalledWith('/account-setup');
  });
});
