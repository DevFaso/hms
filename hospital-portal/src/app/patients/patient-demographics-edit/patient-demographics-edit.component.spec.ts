import { RoleContextService } from '../../core/role-context.service';
import { TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { PatientDemographicsEditComponent } from './patient-demographics-edit.component';
import { PatientResponse, PatientService } from '../../services/patient.service';
import { ToastService } from '../../core/toast.service';

describe('PatientDemographicsEditComponent (QA D926)', () => {
  const patient: PatientResponse = {
    id: 'p-1',
    firstName: 'Ibrahim',
    lastName: 'Ouattara',
    gender: 'MALE',
    dateOfBirth: '2020-03-01',
    phoneNumberPrimary: '+22670001014',
    address: 'Secteur 15',
    emergencyContactName: 'Mariam Ouattara',
    emergencyContactPhone: '+22670001015',
    emergencyContactRelationship: 'Mère',
    active: true,
  };
  let patients: jasmine.SpyObj<PatientService>;
  let toast: jasmine.SpyObj<ToastService>;

  function create() {
    patients = jasmine.createSpyObj<PatientService>('PatientService', ['patchDemographics']);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
    TestBed.configureTestingModule({
      imports: [PatientDemographicsEditComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PatientService, useValue: patients },
        { provide: ToastService, useValue: toast },
      ],
    });
    const fixture = TestBed.createComponent(PatientDemographicsEditComponent);
    fixture.componentInstance.patient = patient;
    fixture.detectChanges();
    return fixture;
  }

  it('RCPT-CLIN: the blood type is not offered to a receptionist, and stays for a nurse', () => {
    const fixture = create();
    const roles = TestBed.inject(RoleContextService);
    const box = () => (fixture.nativeElement as HTMLElement).querySelector('#demoBloodType');
    roles.setRoles(['ROLE_NURSE']);
    fixture.componentRef.changeDetectorRef.markForCheck();
    fixture.detectChanges();
    expect(box()).not.toBeNull();
    roles.setRoles(['ROLE_RECEPTIONIST']);
    fixture.componentRef.changeDetectorRef.markForCheck();
    fixture.detectChanges();
    expect(box()).toBeNull();
  });

  it('opens on the chart as it stands, the legacy address included', () => {
    const c = create().componentInstance;
    expect(c.form.firstName).toBe('Ibrahim');
    expect(c.form.addressLine1).toBe('Secteur 15');
    expect(c.form.emergencyContactRelationship).toBe('Mère');
  });

  it('sends only what changed, and hands the corrected chart back', () => {
    const fixture = create();
    const c = fixture.componentInstance;
    const updated = { ...patient, lastName: 'Ouattara-Sanou' };
    patients.patchDemographics.and.returnValue(of(updated));
    const saved = jasmine.createSpy('saved');
    c.saved.subscribe(saved);

    c.form.lastName = ' Ouattara-Sanou ';
    c.form.emergencyContactPhone = '70 00 10 16';
    c.submit();

    expect(patients.patchDemographics).toHaveBeenCalledWith('p-1', {
      lastName: 'Ouattara-Sanou',
      emergencyContactPhone: '70 00 10 16',
    });
    expect(saved).toHaveBeenCalledWith(updated);
    expect(toast.success).toHaveBeenCalled();
  });

  for (const change of [
    { dateOfBirth: '2999-01-01' },
    { dateOfBirth: '1800-01-01' },
    { lastName: '12345' },
  ]) {
    it(`QA D1403 + D1414: ${JSON.stringify(change)} is refused before the request`, () => {
      const c = create().componentInstance;
      Object.assign(c.form, change);
      c.submit();
      expect(c.error()).toBeTruthy();
      expect(patients.patchDemographics).not.toHaveBeenCalled();
    });
  }

  it('refuses a blank required field without calling the server', () => {
    const c = create().componentInstance;
    c.form.phoneNumberPrimary = '  ';
    c.submit();
    expect(patients.patchDemographics).not.toHaveBeenCalled();
    expect(c.error()).toBeTruthy();
  });

  it('shows the server refusal (a number another record holds)', () => {
    const c = create().componentInstance;
    patients.patchDemographics.and.returnValue(
      throwError(() => ({
        status: 409,
        error: { message: 'Un autre dossier patient utilise déjà ce numéro.' },
      })),
    );
    c.form.phoneNumberPrimary = '+22670001002';
    c.submit();
    expect(c.error()).toBe('Un autre dossier patient utilise déjà ce numéro.');
    expect(c.saving()).toBeFalse();
  });

  it('is a modal dialog with the keyboard contract', () => {
    const el: HTMLElement = create().nativeElement;
    const dialog = el.querySelector('[role="dialog"]');
    expect(dialog?.getAttribute('aria-modal')).toBe('true');
    expect(dialog?.hasAttribute('appdialogfocus')).toBeTrue();
  });

  it('review: a legacy address-only record keeps its street when the city is edited', () => {
    const c = create().componentInstance;
    patients.patchDemographics.and.returnValue(of(patient));
    c.form.city = 'Koudougou';
    c.submit();
    expect(patients.patchDemographics).toHaveBeenCalledWith('p-1', {
      city: 'Koudougou',
      addressLine1: 'Secteur 15',
    });
  });

  it('review round 10: a cleared date of birth is refused, never sent as a silent no-op', () => {
    const c = create().componentInstance;
    c.form.dateOfBirth = '';
    c.submit();
    expect(patients.patchDemographics).not.toHaveBeenCalled();
    // QA D1358: the refusal names the blank field (it was a fixed sentence).
    expect(c.error()).toBe('COMMON.MISSING_REQUIRED_FIELDS');
  });

  it('QA D1358: the refusal names exactly the blank fields, the date of birth included', () => {
    const fixture = create();
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', {
      COMMON: { MISSING_REQUIRED_FIELDS: 'Champs obligatoires manquants : {{fields}}' },
      PATIENTS: { FIRST_NAME: 'Prénom', DOB: 'Date de naissance', PHONE: 'Téléphone' },
    });
    translate.use('fr');
    const c = fixture.componentInstance;
    c.form.dateOfBirth = '';
    c.submit();
    expect(c.error()).toBe('Champs obligatoires manquants : Date de naissance');
    c.form.firstName = ' ';
    c.submit();
    expect(c.error()).toBe('Champs obligatoires manquants : Prénom, Date de naissance');
    expect(patients.patchDemographics).not.toHaveBeenCalled();
  });

  it('review round 8: a legacy composed address sends only its street, never the city and country again', () => {
    patients = jasmine.createSpyObj<PatientService>('PatientService', ['patchDemographics']);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
    TestBed.configureTestingModule({
      imports: [PatientDemographicsEditComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PatientService, useValue: patients },
        { provide: ToastService, useValue: toast },
      ],
    });
    const fixture = TestBed.createComponent(PatientDemographicsEditComponent);
    fixture.componentInstance.patient = {
      ...patient,
      address: 'Secteur 15, Ouagadougou, Burkina Faso',
      city: 'Ouagadougou',
      country: 'Burkina Faso',
    };
    fixture.detectChanges();
    const c = fixture.componentInstance;
    expect(c.form.addressLine1).toBe('Secteur 15');
    patients.patchDemographics.and.returnValue(of(patient));
    c.form.city = 'Bobo-Dioulasso';
    c.submit();
    expect(patients.patchDemographics).toHaveBeenCalledWith('p-1', {
      city: 'Bobo-Dioulasso',
      addressLine1: 'Secteur 15',
    });
  });

  describe('QA D1422: a refused identity is marked on its field', () => {
    const marked = (fixture: ReturnType<typeof create>): string[] =>
      Array.from(
        (fixture.nativeElement as HTMLElement).querySelectorAll('[aria-invalid="true"]'),
      ).map((el) => el.id);

    for (const dob of ['2030-01-01', '1800-01-01']) {
      it(`the birth date ${dob}: aria-invalid, described and focused, nothing sent`, () => {
        const fixture = create();
        fixture.detectChanges();
        fixture.componentInstance.form.dateOfBirth = dob;
        fixture.componentInstance.submit();
        fixture.detectChanges();

        expect(patients.patchDemographics).not.toHaveBeenCalled();
        expect(marked(fixture)).toEqual(['demoDob']);
        const el = (fixture.nativeElement as HTMLElement).querySelector('#demoDob') as HTMLElement;
        expect(el.getAttribute('aria-describedby')).toContain('demoDob');
        expect(document.activeElement).toBe(el);
        expect(fixture.componentInstance.error()).toBeTruthy();
      });
    }

    it('a digit-only name: each one marked; correcting it clears the mark and saves', () => {
      const fixture = create();
      fixture.detectChanges();
      const c = fixture.componentInstance;
      c.form.firstName = '12345';
      c.form.lastName = '678';
      c.submit();
      fixture.detectChanges();
      expect(marked(fixture)).toEqual(['demoFirstName', 'demoLastName']);
      expect(patients.patchDemographics).not.toHaveBeenCalled();

      c.form.firstName = 'Awa';
      c.form.lastName = 'Traoré';
      c.submit();
      fixture.detectChanges();
      expect(marked(fixture)).toEqual([]);
      expect(patients.patchDemographics).toHaveBeenCalled();
    });
  });
});
