import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
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
    userSpy.adminRegister.and.returnValue(of({ id: 'u1', patientId: 'p9' } as UserDetail));
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
        provideHttpClient(withXhr()),
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

  it('QA D756: shows the server message on the card when the link fails with 404', () => {
    registrationSpy.create.and.returnValue(
      throwError(() => ({ status: 404, error: { message: 'Patient introuvable' } })),
    );
    component.form.hospitalId = 'h1';
    component.linkExistingPatient(mockMatch());
    expect(component.linkError).toBe('Patient introuvable');
    expect(toastSpy.error).toHaveBeenCalledWith('Patient introuvable');
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
      // QA D626: the server registers a patient only when the patient form says so.
      jasmine.objectContaining({
        email: undefined,
        roleNames: ['PATIENT'],
        patientRegistration: true,
        patient: jasmine.objectContaining({ email: undefined, dateOfBirth: '1992-05-04' }),
      }),
    );
    // QA D626: the record rides on the same call; no second create to fail.
    expect(patientSpy.create).not.toHaveBeenCalled();
    expect(routerSpy.navigate).toHaveBeenCalledWith(['/patients', 'p9']);
  });

  function fillValidForm(): void {
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
  }

  it('QA D626: a refused registration leaves nothing to compensate and keeps the form open', () => {
    userSpy.adminRegister.and.returnValue(throwError(() => ({ status: 400, error: {} })));
    fillValidForm();
    component.onSubmit();
    expect(userSpy.delete).not.toHaveBeenCalled();
    expect(patientSpy.create).not.toHaveBeenCalled();
    expect(toastSpy.error).toHaveBeenCalled();
    expect(routerSpy.navigate).not.toHaveBeenCalled();
  });

  it('QA D725: a 409 for an existing patient shows the server reason and offers the existing record', () => {
    // The round-10 click landed before the debounced match check answered.
    userSpy.adminRegister.and.returnValue(
      throwError(() => ({
        status: 409,
        error: { message: 'Un patient ... est déjà enregistré.' },
      })),
    );
    patientSpy.registrationMatch.and.returnValue(of([mockMatch()]));
    fillValidForm();
    component.onSubmit();

    expect(toastSpy.error).toHaveBeenCalledWith('Un patient ... est déjà enregistré.');
    // QA D1061 / D1064: the re-query also carries the identity typed in the form.
    expect(patientSpy.registrationMatch).toHaveBeenCalledWith({
      email: undefined,
      phone: '+22670707070',
      hospitalId: 'h1',
      firstName: 'Awa',
      lastName: 'Ouedraogo',
      dateOfBirth: '1992-05-04',
    });
    expect(component.registrationMatches.length).toBe(1);
    expect(component.matchDismissed).toBeFalse();
    expect(routerSpy.navigate).not.toHaveBeenCalled();
  });

  it('QA D1072: a client-side refusal marks every missing field and focuses the first', () => {
    const fixture = TestBed.createComponent(PatientFormComponent);
    const form = fixture.componentInstance;
    form.ngOnInit();
    document.body.appendChild(fixture.nativeElement);
    try {
      fixture.detectChanges();
      Object.assign(form.form, { firstName: 'Abdoul', lastName: '', hospitalId: 'h1' });
      form.onSubmit();
      fixture.detectChanges();

      const el = fixture.nativeElement as HTMLElement;
      const last = el.querySelector<HTMLInputElement>('#lastName')!;
      expect(last.getAttribute('aria-invalid')).toBe('true');
      expect(last.getAttribute('aria-describedby')).toBe('lastName-server-error');
      expect(el.querySelector('#lastName-server-error')?.textContent).toContain(
        'COMMON.FIELD_REQUIRED',
      );
      for (const id of ['phonePrimary', 'dob', 'gender', 'country', 'city']) {
        expect(el.querySelector(`#${id}`)?.getAttribute('aria-invalid'))
          .withContext(id)
          .toBe('true');
      }
      // The first refused field in reading order has the focus.
      expect(document.activeElement).toBe(last);
      expect(el.querySelector('#firstName')?.getAttribute('aria-invalid')).toBeNull();
      expect(userSpy.adminRegister).not.toHaveBeenCalled();
    } finally {
      fixture.nativeElement.remove();
    }
  });

  it('QA D1061: a child with no phone of their own is registered under the guardian phone', () => {
    fillValidForm();
    Object.assign(component.form, {
      firstName: 'Abdoul Aziz',
      lastName: 'Nikiéma',
      dateOfBirth: '2021-08-19',
      phoneNumberPrimary: '',
      emergencyContactName: 'Boukari Nikiéma',
      emergencyContactPhone: '+22676334455',
    });
    expect(component.phoneRequired).toBeFalse();
    component.onSubmit();
    expect(userSpy.adminRegister).toHaveBeenCalledWith(
      jasmine.objectContaining({
        phoneNumber: '+22676334455',
        patient: jasmine.objectContaining({ phoneNumberPrimary: '+22676334455' }),
      }),
    );
  });

  it('QA D1061: a match that only shares the contact does not block the registration', () => {
    fillValidForm();
    component.form.dateOfBirth = '2021-08-19';
    component.registrationMatches = [mockMatch({ sameIdentity: false, birthYear: 1985 })];
    expect(component.identityMatches.length).toBe(0);
    expect(component.sharedContactMatches.length).toBe(1);
    component.onSubmit();
    expect(userSpy.adminRegister).toHaveBeenCalled();
  });

  it('QA D1061 review: a phone match born the same year still asks (a spelling variant)', () => {
    fillValidForm();
    component.form.dateOfBirth = '1990-04-02';
    component.registrationMatches = [mockMatch({ sameIdentity: false, birthYear: 1990 })];
    component.onSubmit();
    expect(userSpy.adminRegister).not.toHaveBeenCalled();
    expect(toastSpy.error).toHaveBeenCalledWith('PATIENTS.MATCH_DECIDE');
  });

  it('QA D1061 review: a shared e-mail still asks (an e-mail is one person)', () => {
    fillValidForm();
    component.form.dateOfBirth = '2021-08-19';
    component.registrationMatches = [
      mockMatch({ sameIdentity: false, birthYear: 1985, matchedOn: 'EMAIL' }),
    ];
    component.onSubmit();
    expect(userSpy.adminRegister).not.toHaveBeenCalled();
  });

  it('QA D1061 review: a refused submit leaves the blank phone blank (the guardian number goes into the request only)', () => {
    fillValidForm();
    Object.assign(component.form, {
      phoneNumberPrimary: '',
      emergencyContactPhone: '+22676334455',
      city: '',
    });
    component.onSubmit();
    expect(userSpy.adminRegister).not.toHaveBeenCalled();
    expect(component.form.phoneNumberPrimary).toBe('');
  });

  it('QA D1064: the same name and date of birth is looked up even with no phone match', (done) => {
    component.ngOnInit();
    Object.assign(component.form, {
      firstName: 'Rakiéta',
      lastName: 'Ouédraogo',
      dateOfBirth: '1993-05-14',
      phoneNumberPrimary: '',
    });
    component.onNameOrDobChange();
    setTimeout(() => {
      expect(patientSpy.registrationMatch).toHaveBeenCalledWith(
        jasmine.objectContaining({
          firstName: 'Rakiéta',
          lastName: 'Ouédraogo',
          dateOfBirth: '1993-05-14',
        }),
      );
      done();
    }, 700);
  });

  it('QA D1064: an IDENTITY match still asks the desk to link or dismiss', () => {
    fillValidForm();
    component.registrationMatches = [mockMatch({ matchedOn: 'IDENTITY', sameIdentity: true })];
    component.onSubmit();
    expect(userSpy.adminRegister).not.toHaveBeenCalled();
    expect(toastSpy.error).toHaveBeenCalledWith('PATIENTS.MATCH_DECIDE');
  });

  it('QA D725: any other refusal does not re-query the match', () => {
    userSpy.adminRegister.and.returnValue(throwError(() => ({ status: 400, error: {} })));
    fillValidForm();
    component.onSubmit();
    expect(patientSpy.registrationMatch).not.toHaveBeenCalled();
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
    expect(userSpy.adminRegister).toHaveBeenCalledWith(
      jasmine.objectContaining({
        patient: jasmine.objectContaining({ phoneVerificationId: 'ch1' }),
      }),
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

  it('QA D986: the match sentence says where the matched records are', () => {
    component.registrationMatches = [{ ...mockMatch(), alreadyRegisteredHere: true }];
    expect(component.matchDescriptionKey()).toBe('PATIENTS.EXISTING_MATCH_DESC_HERE');
    component.registrationMatches = [{ ...mockMatch(), alreadyRegisteredHere: false }];
    expect(component.matchDescriptionKey()).toBe('PATIENTS.EXISTING_MATCH_DESC');
    component.registrationMatches = [
      { ...mockMatch(), alreadyRegisteredHere: true },
      { ...mockMatch(), patientId: 'p9', alreadyRegisteredHere: false },
    ];
    expect(component.matchDescriptionKey()).toBe('PATIENTS.EXISTING_MATCH_DESC_MIXED');
  });
});
