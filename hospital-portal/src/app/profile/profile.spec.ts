import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { signal, WritableSignal } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { ProfileComponent } from './profile';
import { AuthService, LoginUserProfile } from '../auth/auth.service';
import { MfaService } from '../auth/mfa.service';
import { OidcAuthService } from '../auth/oidc-auth.service';
import { ToastService } from '../core/toast.service';
import { EmailChangeResponse, ProfileService, UserProfile } from '../services/profile.service';

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
  delivery: [{ channel: 'EMAIL', purpose: 'EMAIL_CHANGE_CODE', outcome: 'SENT', target: 'n***@x' }],
};

describe('ProfileComponent — editing the profile', () => {
  let fixture: ComponentFixture<ProfileComponent>;
  let component: ProfileComponent;
  let profiles: jasmine.SpyObj<ProfileService>;
  let toast: jasmine.SpyObj<ToastService>;
  let auth: jasmine.SpyObj<AuthService>;
  let ssoAuthenticated: WritableSignal<boolean>;
  let stored: LoginUserProfile;

  beforeEach(async () => {
    profiles = jasmine.createSpyObj<ProfileService>('ProfileService', [
      'getUserProfile',
      'getCredentialHealth',
      'getAssignments',
      'updateProfile',
      'changeOwnEmail',
      'confirmOwnEmailChange',
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
    ]);
    auth.getUserId.and.returnValue('u1');
    auth.getUserProfile.and.callFake(() => stored);
    auth.getRoles.and.returnValue([]);
    auth.hasAnyRole.and.returnValue(false);
    ssoAuthenticated = signal(false);

    await TestBed.configureTestingModule({
      imports: [ProfileComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: ProfileService, useValue: profiles },
        { provide: ToastService, useValue: toast },
        { provide: AuthService, useValue: auth },
        { provide: MfaService, useValue: {} },
        { provide: OidcAuthService, useValue: { authenticated: ssoAuthenticated } },
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
    expect(toast.success).toHaveBeenCalledWith('PROFILE.UPDATED');
    expect(toast.error).toHaveBeenCalledWith('The current password is incorrect.');
    expect(toast.error).not.toHaveBeenCalledWith('PROFILE.UPDATE_FAILED');
    expect(component.user()?.firstName).toBe('Aminata');
    expect(component.user()?.email).toBe('old@example.test');
    expect(component.pendingEmail()).toBeNull();
    expect(component.saving()).toBeFalse();
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

  it('a single sign-on session cannot edit the email: the field is locked and no change is sent', () => {
    ssoAuthenticated.set(true);
    fixture.detectChanges();
    component.updateField('email', 'new@example.test');
    component.updateField('firstName', 'Aminata');
    fixture.detectChanges();

    expect(component.emailChanged()).toBeFalse();
    expect(byId('editEmailPassword')).toBeNull();
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
});
