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
import { ProfileService, UserProfile } from '../services/profile.service';

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

describe('ProfileComponent — editing the profile', () => {
  let fixture: ComponentFixture<ProfileComponent>;
  let component: ProfileComponent;
  let profiles: jasmine.SpyObj<ProfileService>;
  let toast: jasmine.SpyObj<ToastService>;
  let auth: jasmine.SpyObj<AuthService>;
  let ssoAuthenticated: WritableSignal<boolean>;

  beforeEach(async () => {
    profiles = jasmine.createSpyObj<ProfileService>('ProfileService', [
      'getUserProfile',
      'getCredentialHealth',
      'getAssignments',
      'updateProfile',
    ]);
    profiles.getUserProfile.and.returnValue(of(profile('old@example.test')));
    profiles.getCredentialHealth.and.returnValue(throwError(() => new Error('n/a')));
    profiles.getAssignments.and.returnValue(of([]));
    profiles.updateProfile.and.callFake((_id, data) =>
      of(profile(data.email ?? '', data.firstName ?? 'Awa')),
    );
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
    auth = jasmine.createSpyObj<AuthService>('AuthService', [
      'getUserId',
      'getUserProfile',
      'getRoles',
      'hasAnyRole',
      'setUserProfile',
    ]);
    auth.getUserId.and.returnValue('u1');
    auth.getUserProfile.and.returnValue({ id: 'u1', username: 'awa' } as LoginUserProfile);
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

  const emailInput = (): HTMLInputElement => fixture.nativeElement.querySelector('#editEmail');
  const emailHint = (): string =>
    (fixture.nativeElement.querySelector('#editEmailHint') as HTMLElement).textContent?.trim() ??
    '';

  it('saves names and phone through the profile PUT, sending the email back as the account has it', () => {
    component.updateField('firstName', 'Aminata');
    component.saveProfile();

    expect(profiles.updateProfile).toHaveBeenCalledWith(
      'u1',
      jasmine.objectContaining({ firstName: 'Aminata', email: 'old@example.test' }),
    );
    expect(toast.success).toHaveBeenCalledWith('PROFILE.UPDATED');
  });

  it('the email is read-only for a self-edit, with the administrator hint', async () => {
    await fixture.whenStable();
    fixture.detectChanges();

    expect(emailInput().disabled).toBeTrue();
    expect(emailInput().readOnly).toBeTrue();
    expect(emailHint()).toBe('PROFILE.EMAIL_CHANGED_BY_ADMIN');
  });

  it('whatever reaches the form model, the PUT carries the account email', () => {
    component.updateField('email', 'someone-else@evil.test');
    component.updateField('firstName', 'Aminata');
    component.saveProfile();

    expect(profiles.updateProfile).toHaveBeenCalledWith(
      'u1',
      jasmine.objectContaining({ email: 'old@example.test' }),
    );
  });

  it('a single sign-on session shows the single sign-on hint instead', async () => {
    ssoAuthenticated.set(true);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(emailInput().disabled).toBeTrue();
    expect(emailHint()).toBe('PROFILE.EMAIL_MANAGED_BY_SSO');
  });

  it('a refused PUT shows the server message', () => {
    profiles.updateProfile.and.returnValue(
      throwError(() => ({
        status: 400,
        error: { message: 'An administrator can change your email address.' },
      })),
    );
    component.updateField('firstName', 'Aminata');
    component.saveProfile();

    expect(toast.error).toHaveBeenCalledWith('An administrator can change your email address.');
    expect(component.saving()).toBeFalse();
  });
});
