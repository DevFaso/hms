import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';
import { MedicalHistoryTabComponent } from './medical-history-tab.component';
import { MedicalHistoryService } from '../../services/medical-history.service';
import { AuthService } from '../../auth/auth.service';
import { ToastService } from '../../core/toast.service';

describe('MedicalHistoryTabComponent — past PEV doses (QA D1003)', () => {
  let component: MedicalHistoryTabComponent;
  let history: jasmine.SpyObj<MedicalHistoryService>;

  beforeEach(async () => {
    history = jasmine.createSpyObj('MedicalHistoryService', [
      'createImmunization',
      'updateImmunization',
    ]);
    history.createImmunization.and.returnValue(of({ id: 'im1', patientId: 'p1' }));
    const auth = jasmine.createSpyObj('AuthService', [
      'getUserProfile',
      'hasAnyRole',
      'getHospitalId',
    ]);
    auth.getHospitalId.and.returnValue('h1');
    auth.getUserProfile.and.returnValue({ staffId: 'staff-me' });
    auth.hasAnyRole.and.returnValue(true);
    await TestBed.configureTestingModule({
      imports: [MedicalHistoryTabComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MedicalHistoryService, useValue: history },
        { provide: AuthService, useValue: auth },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
      ],
    }).compileComponents();
    component = TestBed.createComponent(MedicalHistoryTabComponent).componentInstance;
    component.patientId = 'p1';
    spyOn(component as never, 'loadImmunizations');
  });

  it('a 2021 dose from the card is recorded as such, with no vaccinator here', () => {
    component.openCreateImmunization();
    component.immunizationForm.vaccineDisplay = 'Pentavalent (DTC-HepB-Hib)';
    component.immunizationForm.vaccineCode = '102';
    component.onImmunizationDateChange('2021-03-10');

    component.submitImmunization();

    const sent = history.createImmunization.calls.mostRecent().args[0];
    expect(sent.sourceOfRecord).toBe('VACCINATION_CARD');
    expect(sent.administeredByStaffId).toBeUndefined();
    expect(sent.vaccineCode).toBe('102');
  });

  describe('impossible doses (QA D1425)', () => {
    const FUTURE = '2999-01-01';

    function filled(over: Partial<typeof component.immunizationForm>): void {
      component.openCreateImmunization();
      component.immunizationForm = {
        ...component.immunizationForm,
        vaccineDisplay: 'BCG (tuberculose)',
        ...over,
      };
    }

    for (const [label, over] of [
      ['a future date', { administrationDate: FUTURE }],
      ['dose 0', { doseNumber: 0 }],
      ['dose -1', { doseNumber: -1 }],
      ['dose 5 of 2', { doseNumber: 5, totalDosesInSeries: 2 }],
      ['a series of 0', { totalDosesInSeries: 0 }],
      [
        'a next dose before this one',
        { administrationDate: '2024-05-05', nextDoseDueDate: '2024-01-01' },
      ],
    ] as const) {
      it(`${label} is refused before sending`, () => {
        filled(over);
        component.submitImmunization();
        expect(history.createImmunization).not.toHaveBeenCalled();
      });
    }

    it('a plain dose is still sent', () => {
      filled({ doseNumber: 1, totalDosesInSeries: 2 });
      component.submitImmunization();
      expect(history.createImmunization).toHaveBeenCalled();
    });

    it('a stored wrong row stays editable on its other fields, until its wrong value is touched', () => {
      history.updateImmunization.and.returnValue(of({ id: 'im9', patientId: 'p1' }));
      component.openEditImmunization({
        id: 'im9',
        patientId: 'p1',
        vaccineDisplay: 'RR',
        administrationDate: FUTURE,
        status: 'COMPLETED',
        doseNumber: -1,
        totalDosesInSeries: 2,
      } as never);
      component.immunizationForm.notes = 'ajouté plus tard';
      component.submitImmunization();
      expect(history.updateImmunization).toHaveBeenCalledTimes(1);

      component.immunizationForm.doseNumber = 0;
      component.submitImmunization();
      expect(history.updateImmunization).toHaveBeenCalledTimes(1);
    });

    it('a row turned into a completed dose is judged on its date, even unchanged', () => {
      component.openEditImmunization({
        id: 'im9',
        patientId: 'p1',
        vaccineDisplay: 'RR',
        administrationDate: FUTURE,
        status: 'DEFERRED',
      } as never);
      component.immunizationForm.status = 'COMPLETED';
      component.submitImmunization();
      expect(history.updateImmunization).not.toHaveBeenCalled();
    });
  });

  it('a dose given today is credited to the clinician who records it', () => {
    component.openCreateImmunization();
    component.immunizationForm.vaccineDisplay = 'BCG (tuberculose)';
    component.immunizationForm.vaccineCode = '19';

    component.submitImmunization();

    const sent = history.createImmunization.calls.mostRecent().args[0];
    expect(sent.sourceOfRecord).toBe('GIVEN_HERE');
    expect(sent.administeredByStaffId).toBe('staff-me');
  });

  it('editing an older dose with no recorded source never credits the editor', () => {
    history.updateImmunization.and.returnValue(of({ id: 'im9', patientId: 'p1' }));
    component.openEditImmunization({
      id: 'im9',
      patientId: 'p1',
      vaccineDisplay: 'BCG',
      vaccineCode: '19',
      administrationDate: '2025-01-10',
      administeredByStaffId: 'staff-original',
    } as never);
    expect(component.immunizationForm.sourceOfRecord).toBe('');

    component.submitImmunization();

    const sent = history.updateImmunization.calls.mostRecent().args[1];
    expect(sent.administeredByStaffId).toBe('staff-original');
    expect(sent.sourceOfRecord).toBeUndefined();
  });

  it('a source chosen by hand is kept when the date changes', () => {
    component.openCreateImmunization();
    component.onImmunizationSourceChange('PATIENT_REPORT');
    component.onImmunizationDateChange('2020-01-01');
    expect(component.immunizationForm.sourceOfRecord).toBe('PATIENT_REPORT');
  });

  describe('CVX code (QA D1003, round 17)', () => {
    it('saves a dose without a CVX code', () => {
      component.openCreateImmunization();
      component.onVaccineChosen('MENA');
      component.immunizationForm.vaccineCode = '';

      component.submitImmunization();

      expect(history.createImmunization).toHaveBeenCalled();
    });

    it('choosing a PEV vaccine fills its verified CVX code', () => {
      component.openCreateImmunization();
      component.onVaccineChosen('PENTA');
      expect(component.immunizationForm.vaccineCode).toBe('198');
      component.onVaccineChosen('VAA');
      expect(component.immunizationForm.vaccineCode).toBe('184');
    });

    it('a vaccine with no verified code clears the code the form filled', () => {
      component.openCreateImmunization();
      component.onVaccineChosen('BCG');
      expect(component.immunizationForm.vaccineCode).toBe('19');
      component.onVaccineChosen('MALARIA');
      expect(component.immunizationForm.vaccineCode).toBe('');
    });

    it('a code the clinician typed is never overwritten', () => {
      component.openCreateImmunization();
      component.immunizationForm.vaccineCode = '02';
      component.onVaccineChosen('VPO');
      expect(component.immunizationForm.vaccineCode).toBe('02');
    });

    it('the filled code stays editable and is sent as edited', () => {
      component.openCreateImmunization();
      component.onVaccineChosen('ROTA');
      component.immunizationForm.vaccineCode = '119';
      component.submitImmunization();
      expect(history.createImmunization.calls.mostRecent().args[0].vaccineCode).toBe('119');
    });
  });
});
