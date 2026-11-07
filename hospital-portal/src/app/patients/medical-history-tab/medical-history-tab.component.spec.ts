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
});
