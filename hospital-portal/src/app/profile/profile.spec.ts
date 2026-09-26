import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { ProfileComponent } from './profile';
import { AuthService } from '../auth/auth.service';
import { MfaService } from '../auth/mfa.service';
import { ToastService } from '../core/toast.service';
import { ProfileService, UserProfile } from '../services/profile.service';

const profile = (email: string): UserProfile => ({
  id: 'u1',
  username: 'awa',
  email,
  firstName: 'Awa',
  lastName: 'Traore',
  phoneNumber: '+22670000000',
  active: true,
  roles: [],
});

describe('ProfileComponent — editing the profile', () => {
  let fixture: ComponentFixture<ProfileComponent>;
  let component: ProfileComponent;
  let profiles: jasmine.SpyObj<ProfileService>;
  let toast: jasmine.SpyObj<ToastService>;

  beforeEach(async () => {
    profiles = jasmine.createSpyObj<ProfileService>('ProfileService', [
      'getUserProfile',
      'getCredentialHealth',
      'getAssignments',
      'updateProfile',
      'changeOwnEmail',
    ]);
    profiles.getUserProfile.and.returnValue(of(profile('old@example.test')));
    profiles.getCredentialHealth.and.returnValue(throwError(() => new Error('n/a')));
    profiles.getAssignments.and.returnValue(of([]));
    profiles.updateProfile.and.callFake((_id, data) => of(profile(data.email ?? '')));
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
    const auth = jasmine.createSpyObj<AuthService>('AuthService', [
      'getUserId',
      'getUserProfile',
      'getRoles',
      'hasAnyRole',
      'setUserProfile',
    ]);
    auth.getUserId.and.returnValue('u1');
    auth.getUserProfile.and.returnValue(null);
    auth.getRoles.and.returnValue([]);
    auth.hasAnyRole.and.returnValue(false);

    await TestBed.configureTestingModule({
      imports: [ProfileComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: ProfileService, useValue: profiles },
        { provide: ToastService, useValue: toast },
        { provide: AuthService, useValue: auth },
        { provide: MfaService, useValue: {} },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ProfileComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component.switchTab('edit');
    fixture.detectChanges();
  });

  const passwordField = (): HTMLInputElement | null =>
    fixture.nativeElement.querySelector('#editEmailPassword');

  it('saves names and phone through the profile PUT, sending the email back unchanged', () => {
    component.updateField('firstName', 'Aminata');
    component.saveProfile();

    expect(profiles.changeOwnEmail).not.toHaveBeenCalled();
    expect(profiles.updateProfile).toHaveBeenCalledWith(
      'u1',
      jasmine.objectContaining({ firstName: 'Aminata', email: 'old@example.test' }),
    );
    expect(passwordField()).toBeNull();
  });

  it('asks for the current password only once the email is changed', () => {
    expect(passwordField()).toBeNull();

    component.updateField('email', 'new@example.test');
    fixture.detectChanges();

    expect(component.emailChanged()).toBeTrue();
    expect(passwordField()).not.toBeNull();
  });

  it('refuses to send an email change without the current password', () => {
    component.updateField('email', 'new@example.test');
    component.saveProfile();

    expect(toast.error).toHaveBeenCalledWith('PROFILE.EMAIL_CHANGE_PASSWORD_REQUIRED');
    expect(profiles.changeOwnEmail).not.toHaveBeenCalled();
    expect(profiles.updateProfile).not.toHaveBeenCalled();
    expect(component.saving()).toBeFalse();
  });

  it('changes the email through its own endpoint first, then saves the rest', () => {
    const order: string[] = [];
    profiles.changeOwnEmail.and.callFake(() => {
      order.push('email');
      return of({ message: 'ok' });
    });
    profiles.updateProfile.and.callFake((_id, data) => {
      order.push('profile');
      return of(profile(data.email ?? ''));
    });
    component.updateField('email', '  new@example.test ');
    component.emailChangePassword.set('Current-Pass-1');
    component.saveProfile();

    expect(profiles.changeOwnEmail).toHaveBeenCalledWith('Current-Pass-1', 'new@example.test');
    expect(profiles.updateProfile).toHaveBeenCalledWith(
      'u1',
      jasmine.objectContaining({ email: 'new@example.test' }),
    );
    expect(order).toEqual(['email', 'profile']);
    expect(component.user()?.email).toBe('new@example.test');
    expect(component.emailChangePassword()).toBe('');
    expect(toast.success).toHaveBeenCalledWith('PROFILE.UPDATED');
  });

  it('saves nothing when the server refuses the password, and shows its message', () => {
    profiles.changeOwnEmail.and.returnValue(
      throwError(() => ({ status: 400, error: { message: 'The current password is incorrect.' } })),
    );
    component.updateField('email', 'new@example.test');
    component.emailChangePassword.set('wrong');
    component.saveProfile();

    expect(profiles.updateProfile).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledWith('The current password is incorrect.');
    expect(component.user()?.email).toBe('old@example.test');
    expect(component.saving()).toBeFalse();
  });

  it('cancelling the edit forgets the typed password', () => {
    component.updateField('email', 'new@example.test');
    component.emailChangePassword.set('Current-Pass-1');
    component.cancelEdit();

    expect(component.emailChangePassword()).toBe('');
    expect(component.emailChanged()).toBeFalse();
  });
});
