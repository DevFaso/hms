import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { signal, WritableSignal } from '@angular/core';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { ProfileComponent } from './profile';
import { AuthService, LoginUserProfile } from '../auth/auth.service';
import { MfaService } from '../auth/mfa.service';
import { OidcAuthService } from '../auth/oidc-auth.service';
import { ToastService } from '../core/toast.service';
import {
  AuditEvent,
  EmailChangeResponse,
  ProfileService,
  UserProfile,
} from '../services/profile.service';
import { PatientPortalService, PatientProfileDTO } from '../services/patient-portal.service';

const profile = (email: string, firstName = 'Awa'): UserProfile => ({
  id: 'u1',
  username: 'awa',
  email,
  firstName,
  lastName: 'Traore',
  phoneNumber: '+22670000000',
  active: true,
  roles: [],
});

const codeSent: EmailChangeResponse = {
  message: 'sent',
  // QUEUED is what the server answers: the code mail leaves from its outbox.
  delivery: [
    { channel: 'EMAIL', purpose: 'EMAIL_CHANGE_CODE', outcome: 'QUEUED', target: 'n***@x' },
  ],
};

describe('ProfileComponent — editing the profile', () => {
  let fixture: ComponentFixture<ProfileComponent>;
  let component: ProfileComponent;
  let profiles: jasmine.SpyObj<ProfileService>;
  let toast: jasmine.SpyObj<ToastService>;
  let auth: jasmine.SpyObj<AuthService>;
  let ssoAuthenticated: WritableSignal<boolean>;
  let stored: LoginUserProfile;
  let portal: jasmine.SpyObj<PatientPortalService>;

  beforeEach(async () => {
    profiles = jasmine.createSpyObj<ProfileService>('ProfileService', [
      'getUserProfile',
      'getCredentialHealth',
      'getAssignments',
      'updateProfile',
      'changeOwnEmail',
      'confirmOwnEmailChange',
      'changePassword',
      'requestPasswordReset',
      'requestPasswordResetByPhone',
    ]);
    profiles.getUserProfile.and.returnValue(of(profile('old@example.test')));
    profiles.getCredentialHealth.and.returnValue(throwError(() => new Error('n/a')));
    profiles.getAssignments.and.returnValue(of([]));
    profiles.updateProfile.and.callFake((_id, data) =>
      of(profile(data.email ?? '', data.firstName ?? 'Awa')),
    );
    toast = jasmine.createSpyObj<ToastService>('ToastService', [
      'success',
      'error',
      'info',
      'warning',
    ]);
    stored = { id: 'u1', username: 'awa', email: 'old@example.test' } as LoginUserProfile;
    auth = jasmine.createSpyObj<AuthService>('AuthService', [
      'getUserId',
      'getUserProfile',
      'getRoles',
      'hasAnyRole',
      'setUserProfile',
      'logout',
    ]);
    auth.getUserId.and.returnValue('u1');
    auth.getUserProfile.and.callFake(() => stored);
    auth.getRoles.and.returnValue([]);
    auth.hasAnyRole.and.returnValue(false);
    ssoAuthenticated = signal(false);
    portal = jasmine.createSpyObj<PatientPortalService>('PatientPortalService', [
      'updateMyProfile',
    ]);
    portal.updateMyProfile.and.returnValue(of({} as PatientProfileDTO));

    await TestBed.configureTestingModule({
      imports: [ProfileComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: ProfileService, useValue: profiles },
        { provide: ToastService, useValue: toast },
        { provide: AuthService, useValue: auth },
        { provide: MfaService, useValue: {} },
        { provide: OidcAuthService, useValue: { authenticated: ssoAuthenticated } },
        { provide: PatientPortalService, useValue: portal },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ProfileComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component.switchTab('edit');
    fixture.detectChanges();
  });

  const byId = (id: string): HTMLInputElement | null =>
    fixture.nativeElement.querySelector(`#${id}`);

  it('QA D844: a patient sees no staff counters (roles, assignments, security %) in the header', () => {
    // PATIENT_B1 holds ROLE_PATIENT only.
    auth.getRoles.and.returnValue(['ROLE_PATIENT']);
    const patientFixture = TestBed.createComponent(ProfileComponent);
    patientFixture.detectChanges();
    expect(patientFixture.nativeElement.querySelector('.header-stats')).toBeNull();

    // Any staff role keeps them, including one isStaffOrAdmin does not list.
    for (const role of ['ROLE_DOCTOR', 'ROLE_PHARMACIST']) {
      auth.getRoles.and.returnValue([role]);
      const staffFixture = TestBed.createComponent(ProfileComponent);
      staffFixture.detectChanges();
      expect(staffFixture.nativeElement.querySelector('.header-stats'))
        .withContext(role)
        .not.toBeNull();
    }
  });

  describe('patient phone (QA D939)', () => {
    function asPatient(): ProfileComponent {
      auth.getRoles.and.returnValue(['ROLE_PATIENT']);
      const f = TestBed.createComponent(ProfileComponent);
      f.detectChanges();
      return f.componentInstance;
    }

    it('sends the new phone of a patient to the patient record too', () => {
      profiles.updateProfile.and.callFake(() =>
        of({ ...profile('old@example.test'), phoneNumber: '+22676112233' }),
      );
      const patient = asPatient();
      patient.updateField('phoneNumber', '+22676112233');
      patient.saveProfile();
      expect(portal.updateMyProfile).toHaveBeenCalledWith({ phoneNumberPrimary: '+22676112233' });
    });

    it('a refused record update is said, and the account gets its old phone back', () => {
      profiles.updateProfile.and.callFake((_id, data) =>
        of({ ...profile('old@example.test'), phoneNumber: data.phoneNumber ?? '' }),
      );
      portal.updateMyProfile.and.returnValue(throwError(() => ({ error: { message: 'taken' } })));
      const patient = asPatient();
      patient.updateField('phoneNumber', '+22676112233');
      patient.saveProfile();
      expect(toast.error).toHaveBeenCalledWith('taken');
      expect(profiles.updateProfile).toHaveBeenCalledTimes(2);
      expect(profiles.updateProfile.calls.mostRecent().args[1].phoneNumber).toBe('+22670000000');
    });

    it('a staff account phone stays the login contact only', () => {
      component.updateField('phoneNumber', '+22676112233');
      component.saveProfile();
      expect(portal.updateMyProfile).not.toHaveBeenCalled();
    });

    it('a patient who changes only the name does not touch the record phone', () => {
      const patient = asPatient();
      patient.updateField('firstName', 'Rasmata');
      patient.saveProfile();
      expect(portal.updateMyProfile).not.toHaveBeenCalled();
    });
  });

  it('saves names and phone through the profile PUT, sending the email back unchanged', () => {
    component.updateField('firstName', 'Aminata');
    component.saveProfile();

    expect(profiles.changeOwnEmail).not.toHaveBeenCalled();
    expect(profiles.updateProfile).toHaveBeenCalledWith(
      'u1',
      jasmine.objectContaining({ firstName: 'Aminata', email: 'old@example.test' }),
    );
    expect(toast.success).toHaveBeenCalledWith('PROFILE.UPDATED');
    expect(auth.setUserProfile).toHaveBeenCalled();
  });

  it('asks for the current password only once the email is really changed', () => {
    expect(byId('editEmailPassword')).toBeNull();
    expect(byId('editEmail')?.disabled).withContext('editable outside single sign-on').toBeFalse();
    expect(fixture.nativeElement.querySelector('#editEmailHint')).toBeNull();

    component.updateField('email', '   ');
    fixture.detectChanges();
    expect(component.emailChanged()).withContext('blank is no change').toBeFalse();

    component.updateField('email', 'OLD@example.test');
    fixture.detectChanges();
    expect(component.emailChanged()).withContext('letter case alone is no change').toBeFalse();

    component.updateField('email', 'new@example.test');
    fixture.detectChanges();
    expect(component.emailChanged()).toBeTrue();
    expect(byId('editEmailPassword')).not.toBeNull();
  });

  it('a blank email field saves the rest and never sends an empty email', () => {
    component.updateField('firstName', 'Aminata');
    component.updateField('email', '');
    component.saveProfile();

    expect(profiles.changeOwnEmail).not.toHaveBeenCalled();
    expect(profiles.updateProfile).toHaveBeenCalledWith(
      'u1',
      jasmine.objectContaining({ email: 'old@example.test' }),
    );
  });

  it('refuses to send an email change without the current password', () => {
    component.updateField('email', 'new@example.test');
    component.saveProfile();

    expect(toast.error).toHaveBeenCalledWith('PROFILE.EMAIL_CHANGE_PASSWORD_REQUIRED');
    expect(profiles.changeOwnEmail).not.toHaveBeenCalled();
    expect(profiles.updateProfile).not.toHaveBeenCalled();
    expect(component.saving()).toBeFalse();
  });

  it('an email-only change requests a code, says so, and changes nothing yet', () => {
    profiles.changeOwnEmail.and.returnValue(of(codeSent));
    component.updateField('email', ' new@example.test ');
    component.emailChangePassword.set('Current-Pass-1');
    component.saveProfile();
    fixture.detectChanges();

    expect(profiles.changeOwnEmail).toHaveBeenCalledWith('Current-Pass-1', 'new@example.test');
    expect(profiles.updateProfile).not.toHaveBeenCalled();
    expect(toast.info).toHaveBeenCalledWith('PROFILE.EMAIL_CODE_SENT');
    expect(toast.success).not.toHaveBeenCalled();
    expect(component.pendingEmail()).toBe('new@example.test');
    expect(component.user()?.email).toBe('old@example.test');
    expect(auth.setUserProfile).not.toHaveBeenCalled();
    expect(component.emailChangePassword()).toBe('');
    expect(byId('emailChangeCode')).not.toBeNull();
  });

  it('warns when the code could not be mailed', () => {
    profiles.changeOwnEmail.and.returnValue(
      of({ message: 'sent', delivery: [{ channel: 'EMAIL', outcome: 'NOT_CONFIGURED' }] }),
    );
    component.updateField('email', 'new@example.test');
    component.emailChangePassword.set('Current-Pass-1');
    component.saveProfile();

    expect(toast.warning).toHaveBeenCalledWith('PROFILE.VERIFICATION_SEND_FAILED');
    expect(toast.info).not.toHaveBeenCalled();
  });

  it('names and email: the names are saved and reported even when the email step is refused', () => {
    profiles.changeOwnEmail.and.returnValue(
      throwError(() => ({ status: 400, error: { message: 'The current password is incorrect.' } })),
    );
    component.updateField('firstName', 'Aminata');
    component.updateField('email', 'new@example.test');
    component.emailChangePassword.set('wrong');
    component.saveProfile();

    expect(profiles.updateProfile).toHaveBeenCalledWith(
      'u1',
      jasmine.objectContaining({ firstName: 'Aminata', email: 'old@example.test' }),
    );
    // The typed password, not the '' the form reset leaves behind.
    expect(profiles.changeOwnEmail).toHaveBeenCalledWith('wrong', 'new@example.test');
    expect(toast.success).toHaveBeenCalledWith('PROFILE.UPDATED');
    expect(toast.error).toHaveBeenCalledWith('The current password is incorrect.');
    expect(toast.error).not.toHaveBeenCalledWith('PROFILE.UPDATE_FAILED');
    expect(component.user()?.firstName).toBe('Aminata');
    expect(component.user()?.email).toBe('old@example.test');
    expect(component.pendingEmail()).toBeNull();
    expect(component.saving()).toBeFalse();
  });

  it('names and email: the email step sends the password typed before the save reset the form', () => {
    profiles.changeOwnEmail.and.returnValue(of(codeSent));
    component.updateField('firstName', 'Aminata');
    component.updateField('email', 'new@example.test');
    component.emailChangePassword.set('Current-Pass-1');
    component.saveProfile();

    expect(profiles.updateProfile).toHaveBeenCalled();
    expect(profiles.changeOwnEmail).toHaveBeenCalledOnceWith('Current-Pass-1', 'new@example.test');
    expect(component.pendingEmail()).toBe('new@example.test');
  });

  it('while a code is pending, neither the old address nor the password prompt is shown', () => {
    profiles.changeOwnEmail.and.returnValue(of(codeSent));
    component.updateField('email', 'new@example.test');
    component.emailChangePassword.set('Current-Pass-1');
    fixture.detectChanges();
    expect(byId('editEmailPassword')).not.toBeNull();

    component.saveProfile();
    component.updateField('email', 'other@example.test');
    fixture.detectChanges();

    expect(byId('emailChangeCode')).not.toBeNull();
    expect(byId('editEmail')).withContext('old address hidden').toBeNull();
    expect(byId('editEmailPassword')).withContext('password prompt hidden').toBeNull();
  });

  it('a 410 (expired or cancelled) drops the code form and shows the message', () => {
    profiles.changeOwnEmail.and.returnValue(of(codeSent));
    profiles.confirmOwnEmailChange.and.returnValue(
      throwError(() => ({ status: 410, error: { message: 'The verification code has expired.' } })),
    );
    component.updateField('email', 'new@example.test');
    component.emailChangePassword.set('Current-Pass-1');
    component.saveProfile();
    component.emailCode.set('424242');
    component.confirmEmailChange();
    fixture.detectChanges();

    expect(toast.error).toHaveBeenCalledWith('The verification code has expired.');
    expect(component.pendingEmail()).toBeNull();
    expect(byId('emailChangeCode')).toBeNull();
    expect(byId('editEmail')).not.toBeNull();
    expect(component.user()?.email).toBe('old@example.test');
  });

  it('Enter in the code box verifies the code, and does not submit the profile', () => {
    profiles.changeOwnEmail.and.returnValue(of(codeSent));
    profiles.confirmOwnEmailChange.and.returnValue(of({ message: 'done' }));
    component.updateField('email', 'new@example.test');
    component.emailChangePassword.set('Current-Pass-1');
    component.saveProfile();
    fixture.detectChanges();
    profiles.changeOwnEmail.calls.reset();
    toast.error.calls.reset();

    const codeInput = byId('emailChangeCode')!;
    const codeForm = codeInput.closest('form')!;
    expect(codeForm.classList.contains('edit-form'))
      .withContext('not the profile form')
      .toBeFalse();
    component.emailCode.set('424242');
    codeForm.dispatchEvent(new Event('submit'));

    expect(profiles.confirmOwnEmailChange).toHaveBeenCalledOnceWith('424242');
    expect(profiles.changeOwnEmail).not.toHaveBeenCalled();
    expect(toast.error).not.toHaveBeenCalledWith('PROFILE.EMAIL_CHANGE_PASSWORD_REQUIRED');
  });

  it('Save is disabled while a code is pending', () => {
    profiles.changeOwnEmail.and.returnValue(of(codeSent));
    component.updateField('email', 'new@example.test');
    component.emailChangePassword.set('Current-Pass-1');
    component.saveProfile();
    component.updateField('firstName', 'Aminata');
    fixture.detectChanges();

    const save = fixture.nativeElement.querySelector(
      'form.edit-form button[type="submit"]',
    ) as HTMLButtonElement;
    expect(save.disabled).toBeTrue();
  });

  it('a refused profile PUT stops there: no email step is attempted', () => {
    profiles.updateProfile.and.returnValue(throwError(() => ({ status: 500 })));
    component.updateField('firstName', 'Aminata');
    component.updateField('email', 'new@example.test');
    component.emailChangePassword.set('Current-Pass-1');
    component.saveProfile();

    expect(toast.error).toHaveBeenCalledWith('PROFILE.UPDATE_FAILED');
    expect(profiles.changeOwnEmail).not.toHaveBeenCalled();
  });

  it('the code applies the change: the page and the stored login profile get the new address', () => {
    profiles.changeOwnEmail.and.returnValue(of(codeSent));
    profiles.confirmOwnEmailChange.and.returnValue(of({ message: 'done' }));
    component.updateField('email', 'New@Example.test');
    component.emailChangePassword.set('Current-Pass-1');
    component.saveProfile();
    component.emailCode.set(' 424242 ');
    component.confirmEmailChange();

    expect(profiles.confirmOwnEmailChange).toHaveBeenCalledWith('424242');
    expect(component.user()?.email).toBe('new@example.test');
    expect(stored.email).toBe('new@example.test');
    expect(auth.setUserProfile).toHaveBeenCalledWith(stored);
    expect(component.pendingEmail()).toBeNull();
    expect(toast.success).toHaveBeenCalledWith('PROFILE.EMAIL_CHANGED');
  });

  it('a refused code keeps the old address and the pending step, and shows the server message', () => {
    profiles.changeOwnEmail.and.returnValue(of(codeSent));
    profiles.confirmOwnEmailChange.and.returnValue(
      throwError(() => ({
        status: 400,
        error: { message: 'The verification code is incorrect.' },
      })),
    );
    component.updateField('email', 'new@example.test');
    component.emailChangePassword.set('Current-Pass-1');
    component.saveProfile();
    component.emailCode.set('000000');
    component.confirmEmailChange();

    expect(toast.error).toHaveBeenCalledWith('The verification code is incorrect.');
    expect(component.user()?.email).toBe('old@example.test');
    expect(component.pendingEmail()).toBe('new@example.test');
    expect(auth.setUserProfile).not.toHaveBeenCalled();
  });

  it('cancelling the pending change clears it and the form', () => {
    profiles.changeOwnEmail.and.returnValue(of(codeSent));
    component.updateField('email', 'new@example.test');
    component.emailChangePassword.set('Current-Pass-1');
    component.saveProfile();
    component.cancelEmailChange();

    expect(component.pendingEmail()).toBeNull();
    expect(component.editForm().email).toBe('old@example.test');
  });

  it('a single sign-on session cannot edit the email: the field is locked and no change is sent', async () => {
    ssoAuthenticated.set(true);
    fixture.detectChanges();
    component.updateField('email', 'new@example.test');
    component.updateField('firstName', 'Aminata');
    fixture.detectChanges();

    expect(component.emailChanged()).toBeFalse();
    expect(byId('editEmailPassword')).toBeNull();
    // NgModel applies [disabled] asynchronously.
    await fixture.whenStable();
    fixture.detectChanges();
    expect(byId('editEmail')?.disabled).toBeTrue();
    expect(
      (fixture.nativeElement.querySelector('#editEmailHint') as HTMLElement).textContent?.trim(),
    ).toBe('PROFILE.EMAIL_MANAGED_BY_SSO');
    component.saveProfile();
    expect(profiles.changeOwnEmail).not.toHaveBeenCalled();
    expect(profiles.updateProfile).toHaveBeenCalledWith(
      'u1',
      jasmine.objectContaining({ email: 'old@example.test' }),
    );
  });

  it('cancelling the edit forgets the typed password', () => {
    component.updateField('email', 'new@example.test');
    component.emailChangePassword.set('Current-Pass-1');
    component.cancelEdit();

    expect(component.emailChangePassword()).toBe('');
    expect(component.emailChanged()).toBeFalse();
  });

  describe('password reset link (D256)', () => {
    const asUser = (email: string, phoneNumber: string): void => {
      component.user.set({ ...profile(email), phoneNumber });
    };

    it('an e-mail account gets the link by e-mail', () => {
      profiles.requestPasswordReset.and.returnValue(of(undefined));
      asUser('awa@example.test', '+22670000000');
      component.requestPasswordReset();

      expect(profiles.requestPasswordReset).toHaveBeenCalledWith('awa@example.test');
      expect(profiles.requestPasswordResetByPhone).not.toHaveBeenCalled();
      expect(toast.success).toHaveBeenCalledWith('PROFILE.RESET_LINK_SENT');
    });

    it('a phone-only account gets the link by SMS, not "no e-mail on file"', () => {
      profiles.requestPasswordResetByPhone.and.returnValue(of(undefined));
      asUser('', '+22670000000');
      component.requestPasswordReset();

      expect(profiles.requestPasswordResetByPhone).toHaveBeenCalledWith('+22670000000');
      expect(profiles.requestPasswordReset).not.toHaveBeenCalled();
      expect(toast.success).toHaveBeenCalledWith('PROFILE.RESET_LINK_SENT');
      expect(toast.error).not.toHaveBeenCalled();
    });

    it('an SMS failure says the request failed', () => {
      profiles.requestPasswordResetByPhone.and.returnValue(throwError(() => ({ status: 500 })));
      asUser('', '+22670000000');
      component.requestPasswordReset();

      expect(toast.error).toHaveBeenCalledWith('PROFILE.RESET_REQUEST_FAILED');
    });

    it('an account with neither e-mail nor phone calls nothing and says so', () => {
      asUser('', '');
      component.requestPasswordReset();

      expect(profiles.requestPasswordReset).not.toHaveBeenCalled();
      expect(profiles.requestPasswordResetByPhone).not.toHaveBeenCalled();
      expect(toast.error).toHaveBeenCalledWith('PROFILE.NO_EMAIL_ON_FILE');
    });
  });

  describe('self-service password change (D38)', () => {
    beforeEach(() => {
      component.switchTab('security');
      fixture.detectChanges();
    });

    const fill = (current: string, next: string, confirm: string): void => {
      component.pwdCurrent.set(current);
      component.pwdNew.set(next);
      component.pwdConfirm.set(confirm);
    };

    it('the security tab offers a change-password form', () => {
      expect(byId('pwdCurrent')).not.toBeNull();
      expect(byId('pwdNew')).not.toBeNull();
      expect(byId('pwdConfirm')).not.toBeNull();
    });

    it('sends the current and new password, then clears the form', () => {
      profiles.changePassword.and.returnValue(of({ message: 'ok' }));
      fill('Old-Pass-1', 'New-Pass-12', 'New-Pass-12');
      component.changePassword();

      expect(profiles.changePassword).toHaveBeenCalledWith('Old-Pass-1', 'New-Pass-12');
      expect(toast.success).toHaveBeenCalledWith('PROFILE.PASSWORD_CHANGED');
      expect(component.pwdCurrent()).toBe('');
      expect(component.changingPassword()).toBeFalse();
      // QA D114: the old session is dead server-side; sign in again.
      expect(auth.logout).toHaveBeenCalled();
    });

    it('refuses a mismatch, a short or an unchanged password without calling the server', () => {
      fill('Old-Pass-1', 'New-Pass-12', 'Other-Pass-12');
      component.changePassword();
      fill('Old-Pass-1', 'short', 'short');
      component.changePassword();
      fill('Same-Pass-1', 'Same-Pass-1', 'Same-Pass-1');
      component.changePassword();

      expect(profiles.changePassword).not.toHaveBeenCalled();
      expect(toast.error.calls.allArgs().map((a) => a[0])).toEqual([
        'PROFILE.PASSWORD_MISMATCH',
        'PROFILE.PASSWORD_TOO_SHORT',
        'PROFILE.PASSWORD_SAME_AS_CURRENT',
      ]);
    });

    it('a 401 says the current password is wrong', () => {
      profiles.changePassword.and.returnValue(throwError(() => ({ status: 401 })));
      fill('Bad-Pass-1', 'New-Pass-12', 'New-Pass-12');
      component.changePassword();

      expect(toast.error).toHaveBeenCalledWith('PROFILE.PASSWORD_CURRENT_WRONG');
      expect(component.changingPassword()).toBeFalse();
    });

    it('a single sign-on session gets a hint instead of the form', () => {
      ssoAuthenticated.set(true);
      fixture.detectChanges();
      expect(byId('pwdCurrent')).toBeNull();
    });
  });
  it('QA D894: the activity timeline reads a system description in the reader language', () => {
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', {
      AUDIT: {
        DESC: { LOGIN_FAILED_BAD_CREDENTIALS: 'Échec de connexion : identifiants incorrects' },
      },
    });
    translate.use('fr');
    expect(
      component.describeActivity({
        eventDescription: 'Login failed: bad credentials',
        entityType: 'USER',
      } as AuditEvent),
    ).toBe('Échec de connexion : identifiants incorrects');
  });
});
