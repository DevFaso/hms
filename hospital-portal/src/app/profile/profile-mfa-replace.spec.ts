import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { ProfileComponent } from './profile';
import { AuthService } from '../auth/auth.service';
import { MfaEnrollmentResponse, MfaService } from '../auth/mfa.service';
import { OidcAuthService } from '../auth/oidc-auth.service';
import { ToastService } from '../core/toast.service';
import { CredentialHealth, ProfileService, UserProfile } from '../services/profile.service';

const user: UserProfile = {
  id: 'u1',
  username: 'awa',
  email: 'awa@example.test',
  firstName: 'Awa',
  lastName: 'Traore',
  active: true,
  roles: [],
};

const health = (verifiedTotp: boolean): CredentialHealth => ({
  userId: 'u1',
  username: 'awa',
  email: 'awa@example.test',
  active: true,
  forcePasswordChange: false,
  forceUsernameChange: false,
  mfaEnrolledCount: verifiedTotp ? 1 : 0,
  verifiedMfaCount: verifiedTotp ? 1 : 0,
  hasPrimaryMfa: verifiedTotp,
  recoveryContactCount: 0,
  verifiedRecoveryContacts: 0,
  hasPrimaryRecoveryContact: false,
  mfaEnrollments: verifiedTotp
    ? [
        {
          id: 'm1',
          method: 'TOTP',
          enabled: true,
          primaryFactor: true,
          lastVerifiedAt: '2026-10-01T10:00:00',
          active: true,
        },
      ]
    : [],
  recoveryContacts: [],
});

const enrollment: MfaEnrollmentResponse = {
  secret: 'NEWSECRET',
  otpauthUri: 'otpauth://totp/HMS:awa?secret=NEWSECRET',
  qrCodeDataUrl: 'data:image/png;base64,x',
  backupCodes: ['11112222'],
};

const refused = (code: string): HttpErrorResponse =>
  new HttpErrorResponse({ status: 403, error: { code, message: 'server text' } });

describe('ProfileComponent — replacing the authenticator', () => {
  let fixture: ComponentFixture<ProfileComponent>;
  let component: ProfileComponent;
  let mfa: jasmine.SpyObj<MfaService>;
  let profiles: jasmine.SpyObj<ProfileService>;

  async function setUp(verifiedTotp: boolean): Promise<void> {
    profiles = jasmine.createSpyObj<ProfileService>('ProfileService', [
      'getUserProfile',
      'getCredentialHealth',
      'getAssignments',
    ]);
    profiles.getUserProfile.and.returnValue(of(user));
    profiles.getCredentialHealth.and.returnValue(of(health(verifiedTotp)));
    profiles.getAssignments.and.returnValue(of([]));
    mfa = jasmine.createSpyObj<MfaService>('MfaService', ['enroll', 'verifyEnrollment']);
    const auth = jasmine.createSpyObj<AuthService>('AuthService', [
      'getUserId',
      'getUserProfile',
      'getRoles',
      'hasAnyRole',
      'setUserProfile',
    ]);
    auth.getUserId.and.returnValue('u1');
    auth.getRoles.and.returnValue([]);
    auth.hasAnyRole.and.returnValue(false);

    await TestBed.configureTestingModule({
      imports: [ProfileComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: ProfileService, useValue: profiles },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error', 'info', 'warning']),
        },
        { provide: AuthService, useValue: auth },
        { provide: MfaService, useValue: mfa },
        { provide: OidcAuthService, useValue: { authenticated: signal(false) } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ProfileComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component.switchTab('security');
    fixture.detectChanges();
  }

  const el = (selector: string): HTMLElement | null =>
    fixture.nativeElement.querySelector(selector);

  it('offers to replace an active authenticator, never to set one up again', async () => {
    await setUp(true);

    expect(el('[data-testid="mfa-replace"]')).not.toBeNull();
    expect(el('.mfa-setup-btn')).toBeNull();
  });

  it('offers Replace from the server rule only, not from the other fields', async () => {
    await setUp(true);
    const looksActive = health(true);
    looksActive.mfaEnrollments[0].active = false;
    component.credentials.set(looksActive);
    fixture.detectChanges();

    expect(component.hasActiveTotp()).toBeFalse();
    expect(el('[data-testid="mfa-replace"]')).toBeNull();
  });

  it('an inactive authenticator offers set-up, not Replace, and sends no step-up', async () => {
    await setUp(true);
    const inactive = health(true);
    inactive.mfaEnrollments[0].active = false;
    inactive.mfaEnrollments[0].enabled = false;
    component.credentials.set(inactive);
    fixture.detectChanges();
    mfa.enroll.and.returnValue(of(enrollment));

    expect(el('[data-testid="mfa-replace"]')).toBeNull();
    (el('[data-testid="mfa-setup"]') as HTMLButtonElement).click();

    expect(mfa.enroll).toHaveBeenCalledOnceWith(undefined);
  });

  it('without an authenticator there is nothing to replace: setting one up sends no step-up', async () => {
    await setUp(false);
    mfa.enroll.and.returnValue(of(enrollment));

    expect(el('[data-testid="mfa-replace"]')).toBeNull();
    component.startMfaEnrollment();

    expect(mfa.enroll).toHaveBeenCalledOnceWith(undefined);
    expect(component.mfaReplacing()).toBeFalse();
  });

  it('asks for a current code or a backup code first, and sends it as the step-up', async () => {
    await setUp(true);
    mfa.enroll.and.returnValue(of(enrollment));

    (el('[data-testid="mfa-replace"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el('[data-testid="mfa-stepup"]')).not.toBeNull();
    expect(mfa.enroll).not.toHaveBeenCalled();

    component.mfaStepUpCode = ' 12345678 ';
    component.submitMfaStepUp();
    fixture.detectChanges();

    expect(mfa.enroll).toHaveBeenCalledOnceWith('12345678');
    expect(component.mfaStep()).toBe('qr');
    // The current authenticator keeps working until the new one is confirmed.
    expect(fixture.nativeElement.textContent).toContain('PROFILE.MFA_REPLACE_PENDING');
  });

  it('an empty step-up is not sent', async () => {
    await setUp(true);
    component.startMfaReplacement();
    component.submitMfaStepUp();

    expect(mfa.enroll).not.toHaveBeenCalled();
    expect(component.mfaError()).toBe('PROFILE.MFA_STEPUP_REQUIRED');
  });

  it('shows why a step-up was refused (403 reason code), and stays on the step-up', async () => {
    await setUp(true);
    mfa.enroll.and.returnValue(throwError(() => refused('mfa.stepup.invalid')));
    component.startMfaReplacement();
    component.mfaStepUpCode = '000000';

    component.submitMfaStepUp();

    expect(component.mfaError()).toBe('PROFILE.MFA_STEPUP_INVALID');
    expect(component.mfaStep()).toBe('stepup');
  });

  it('a locked step-up says so in the user language', async () => {
    await setUp(true);
    mfa.enroll.and.returnValue(throwError(() => refused('mfa.stepup.locked')));
    component.startMfaReplacement();
    component.mfaStepUpCode = '123456';

    component.submitMfaStepUp();

    expect(component.mfaError()).toBe('PROFILE.MFA_STEPUP_LOCKED');
  });

  it('cancelling the replacement forgets the code and goes back', async () => {
    await setUp(true);
    component.startMfaReplacement();
    component.mfaStepUpCode = '123456';

    component.cancelMfaReplacement();

    expect(component.mfaStep()).toBe('idle');
    expect(component.mfaStepUpCode).toBe('');
    expect(component.mfaReplacing()).toBeFalse();
  });
});
