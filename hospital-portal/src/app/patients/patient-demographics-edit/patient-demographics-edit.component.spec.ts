import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
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
});
