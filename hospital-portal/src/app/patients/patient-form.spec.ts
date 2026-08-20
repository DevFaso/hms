import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { PatientFormComponent } from './patient-form';
import {
  PatientService,
  PatientResponse,
  PhoneVerificationChallenge,
  RegistrationMatch,
} from '../services/patient.service';
import { UserService, UserDetail } from '../services/user.service';
import { AuthService } from '../auth/auth.service';
import { ToastService } from '../core/toast.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { RoleContextService } from '../core/role-context.service';
import { ReceptionService } from '../reception/reception.service';
import { RegistrationService, HospitalRegistration } from '../services/registration.service';

function mockMatch(overrides: Partial<RegistrationMatch> = {}): RegistrationMatch {
  return {
    patientId: 'p1',
    fullName: 'Jane Doe',
    birthYear: 1990,
    gender: 'FEMALE',
    maskedPhone: '+•••••••••70',
    maskedEmail: 'j•••@example.com',
    hospitalCount: 1,
    alreadyRegisteredHere: false,
    matchedOn: 'PHONE',
    ...overrides,
  };
}

describe('PatientFormComponent', () => {
  let component: PatientFormComponent;
  let patientSpy: jasmine.SpyObj<PatientService>;
  let userSpy: jasmine.SpyObj<UserService>;
  let toastSpy: jasmine.SpyObj<ToastService>;
  let registrationSpy: jasmine.SpyObj<RegistrationService>;
  let receptionSpy: jasmine.SpyObj<ReceptionService>;
  let routerSpy: jasmine.SpyObj<Router>;

  beforeEach(async () => {
    patientSpy = jasmine.createSpyObj('PatientService', [
      'registrationMatch',
      'create',
      'phoneVerificationAvailability',
      'requestPhoneVerification',
      'confirmPhoneVerification',
    ]);
    patientSpy.registrationMatch.and.returnValue(of([]));
    patientSpy.create.and.returnValue(of({ id: 'p9' } as PatientResponse));
    patientSpy.phoneVerificationAvailability.and.returnValue(of({ available: false }));
    userSpy = jasmine.createSpyObj('UserService', ['adminRegister', 'delete']);
    userSpy.adminRegister.and.returnValue(of({ id: 'u1' } as UserDetail));
    toastSpy = jasmine.createSpyObj('ToastService', ['success', 'error']);
    registrationSpy = jasmine.createSpyObj('RegistrationService', ['create']);
    registrationSpy.create.and.returnValue(of({ id: 'r1' } as HospitalRegistration));
    receptionSpy = jasmine.createSpyObj('ReceptionService', ['getDuplicateCandidates']);
    receptionSpy.getDuplicateCandidates.and.returnValue(of([]));
    routerSpy = jasmine.createSpyObj('Router', ['navigate']);
    const authSpy = jasmine.createSpyObj('AuthService', ['getHospitalId']);
    authSpy.getHospitalId.and.returnValue('h1');
    const hospitalSpy = jasmine.createSpyObj('HospitalService', [
      'list',
      'getMyHospitalAsResponse',
    ]);
    hospitalSpy.getMyHospitalAsResponse.and.returnValue(
      of({ id: 'h1', name: 'City Hospital' } as HospitalResponse),
    );
    const roleCtx = { isSuperAdmin: () => false } as unknown as RoleContextService;

    await TestBed.configureTestingModule({
      imports: [PatientFormComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: PatientService, useValue: patientSpy },
        { provide: UserService, useValue: userSpy },
        { provide: AuthService, useValue: authSpy },
        { provide: ToastService, useValue: toastSpy },
        { provide: HospitalService, useValue: hospitalSpy },
        { provide: RoleContextService, useValue: roleCtx },
        { provide: ReceptionService, useValue: receptionSpy },
        { provide: RegistrationService, useValue: registrationSpy },
        { provide: Router, useValue: routerSpy },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: new Map() } } },
      ],
    }).compileComponents();

    component = TestBed.createComponent(PatientFormComponent).componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('queries registration-match with the typed phone after the debounce', (done) => {
    patientSpy.registrationMatch.and.returnValue(of([mockMatch()]));
    component.ngOnInit();
    component.form.phoneNumberPrimary = '+22670707070';
    component.onIdentifierChange();
    setTimeout(() => {
      expect(patientSpy.registrationMatch).toHaveBeenCalledWith({
        email: undefined,
        phone: '+22670707070',
        hospitalId: 'h1',
      });
      expect(component.registrationMatches.length).toBe(1);
      done();
    }, 700);
  });

  it('does not query registration-match for a too-short phone and no email', (done) => {
    component.ngOnInit();
    component.registrationMatches = [mockMatch()];
    component.form.phoneNumberPrimary = '070';
    component.form.email = 'not-an-email';
    component.onIdentifierChange();
    setTimeout(() => {
      expect(patientSpy.registrationMatch).not.toHaveBeenCalled();
      expect(component.registrationMatches.length).toBe(0);
      done();
    }, 700);
  });

  it('re-arms the match card when the identifier changes after a dismissal', (done) => {
    patientSpy.registrationMatch.and.returnValue(of([mockMatch()]));
    component.ngOnInit();
    component.form.phoneNumberPrimary = '+22670707070';
    component.onIdentifierChange();
    setTimeout(() => {
      component.dismissMatch();
      expect(component.matchDismissed).toBeTrue();
      patientSpy.registrationMatch.and.returnValue(of([mockMatch({ patientId: 'p2' })]));
      component.form.phoneNumberPrimary = '+22670707071';
      component.onIdentifierChange();
      setTimeout(() => {
        expect(component.matchDismissed).toBeFalse();
        expect(component.registrationMatches[0]?.patientId).toBe('p2');
        done();
      }, 700);
    }, 700);
  });

  it('links the matched patient to this hospital and navigates to their record', () => {
    component.form.hospitalId = 'h1';
    component.linkExistingPatient(mockMatch());
    expect(registrationSpy.create).toHaveBeenCalledWith({ patientId: 'p1', hospitalId: 'h1' });
    expect(toastSpy.success).toHaveBeenCalled();
    expect(routerSpy.navigate).toHaveBeenCalledWith(['/patients', 'p1']);
  });

  it('surfaces a conflict toast when the link registration returns 409', () => {
    registrationSpy.create.and.returnValue(throwError(() => ({ status: 409 })));
    component.form.hospitalId = 'h1';
    component.linkExistingPatient(mockMatch());
    expect(toastSpy.error).toHaveBeenCalled();
    expect(component.linking).toBeFalse();
    expect(routerSpy.navigate).not.toHaveBeenCalled();
  });

  it('blocks submission while a match is neither linked nor dismissed', () => {
    Object.assign(component.form, {
      firstName: 'Jane',
      lastName: 'Doe',
      email: 'jane@example.com',
      phoneNumberPrimary: '+22670707070',
      gender: 'FEMALE',
      dateOfBirth: '1990-01-01',
      country: 'Burkina Faso',
      city: 'Ouagadougou',
      hospitalId: 'h1',
    });
    component.registrationMatches = [mockMatch()];
    component.onSubmit();
    expect(toastSpy.error).toHaveBeenCalled();
    expect(userSpy.adminRegister).not.toHaveBeenCalled();
  });

  it('registers a patient without an email (phone-first) and omits it from both payloads', () => {
    Object.assign(component.form, {
      firstName: 'Awa',
      lastName: 'Ouedraogo',
      email: '',
      phoneNumberPrimary: '+22670707070',
      gender: 'FEMALE',
      dateOfBirth: '1992-05-04',
      country: 'Burkina Faso',
      city: 'Ouagadougou',
      hospitalId: 'h1',
    });
    component.onSubmit();
    expect(userSpy.adminRegister).toHaveBeenCalledWith(
      jasmine.objectContaining({ email: undefined, roleNames: ['PATIENT'] }),
    );
    expect(patientSpy.create).toHaveBeenCalledWith(jasmine.objectContaining({ email: undefined }));
    expect(routerSpy.navigate).toHaveBeenCalledWith(['/patients', 'p9']);
  });

  it('sends and confirms an SMS verification code, then attaches the challenge to the create payload', () => {
    patientSpy.requestPhoneVerification.and.returnValue(
      of({
        challengeId: 'ch1',
        maskedPhone: '+•••••••••70',
        expiresAt: '2026-08-20T00:05:00',
        verified: false,
      } as PhoneVerificationChallenge),
    );
    patientSpy.confirmPhoneVerification.and.returnValue(
      of({
        challengeId: 'ch1',
        maskedPhone: '+•••••••••70',
        expiresAt: '2026-08-20T00:05:00',
        verified: true,
      } as PhoneVerificationChallenge),
    );
    component.form.phoneNumberPrimary = '+22670707070';
    component.sendPhoneVerification();
    expect(component.otpChallengeId).toBe('ch1');
    component.otpCode = '123456';
    component.confirmPhoneVerification();
    expect(component.phoneVerified).toBeTrue();

    Object.assign(component.form, {
      firstName: 'Awa',
      lastName: 'Ouedraogo',
      email: '',
      gender: 'FEMALE',
      dateOfBirth: '1992-05-04',
      country: 'Burkina Faso',
      city: 'Ouagadougou',
      hospitalId: 'h1',
    });
    component.onSubmit();
    expect(patientSpy.create).toHaveBeenCalledWith(
      jasmine.objectContaining({ phoneVerificationId: 'ch1' }),
    );
  });

  it('invalidates a completed verification when the phone number changes', () => {
    component.ngOnInit();
    component.phoneVerified = true;
    component.otpChallengeId = 'ch1';
    component.form.phoneNumberPrimary = '+22670000000';
    component.onIdentifierChange();
    expect(component.phoneVerified).toBeFalse();
    expect(component.otpChallengeId).toBeNull();
  });

  it('proceeds with a fresh registration once the match is dismissed', () => {
    Object.assign(component.form, {
      firstName: 'Jane',
      lastName: 'Doe',
      email: 'jane@example.com',
      phoneNumberPrimary: '+22670707070',
      gender: 'FEMALE',
      dateOfBirth: '1990-01-01',
      country: 'Burkina Faso',
      city: 'Ouagadougou',
      hospitalId: 'h1',
    });
    component.registrationMatches = [mockMatch()];
    component.dismissMatch();
    component.onSubmit();
    expect(userSpy.adminRegister).toHaveBeenCalled();
    expect(routerSpy.navigate).toHaveBeenCalledWith(['/patients', 'p9']);
  });
});
