import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { TransfusionComponent } from './transfusion';
import { TransfusionService } from '../services/transfusion.service';
import { PatientResponse, PatientService } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { roleContextStub } from '../testing/role-context.stub';
import { bloodRequestState } from '../shared/blood-request-handoff';

/**
 * QA D3220: « Demander du sang » on the labour card, the post-partum watch or the prescription dialog lands on
 * the blood bank with its patient in the navigation state. The existing request form opens for that patient.
 */
describe('TransfusionComponent: a request handed over from a clinical card (QA D3220)', () => {
  let txSpy: jasmine.SpyObj<TransfusionService>;
  let patients: jasmine.SpyObj<PatientService>;
  let toast: jasmine.SpyObj<ToastService>;

  const mother = { id: 'p-1', firstName: 'Awa', lastName: 'Diallo' } as PatientResponse;

  function create(roles: string[]): TransfusionComponent {
    txSpy = jasmine.createSpyObj<TransfusionService>('TransfusionService', [
      'listRequests',
      'getAntibodyStatus',
      'getCurrentBloodGroup',
    ]);
    txSpy.listRequests.and.returnValue(of([]));
    txSpy.getAntibodyStatus.and.returnValue(of(null) as never);
    txSpy.getCurrentBloodGroup.and.returnValue(of(null) as never);
    patients = jasmine.createSpyObj<PatientService>('PatientService', ['getById']);
    patients.getById.and.returnValue(of(mother));
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
    TestBed.configureTestingModule({
      imports: [TransfusionComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: TransfusionService, useValue: txSpy },
        { provide: PatientService, useValue: patients },
        { provide: ToastService, useValue: toast },
        {
          provide: RoleContextService,
          useValue: roleContextStub({ superAdmin: false, hospitalId: 'h1', roles }),
        },
      ],
    });
    return TestBed.createComponent(TransfusionComponent).componentInstance;
  }

  afterEach(() => history.replaceState(null, ''));

  it('opens the request form with the patient the card was about, and reads their group', () => {
    history.replaceState(bloodRequestState('p-1'), '');
    const component = create(['ROLE_MIDWIFE']);

    component.ngOnInit();

    expect(patients.getById).toHaveBeenCalledOnceWith('p-1');
    expect(component.showRequestModal()).toBeTrue();
    expect(component.selectedPatient()).toEqual(mother);
    expect(component.requestForm.patientId).toBe('p-1');
    expect(txSpy.getCurrentBloodGroup).toHaveBeenCalledWith('p-1');
    // Read once: a reload of the blood bank does not reopen the form.
    component.closeNewRequest();
    component.ngOnInit();
    expect(patients.getById).toHaveBeenCalledTimes(1);
    expect(component.showRequestModal()).toBeFalse();
  });

  it('leaves the form open for the picker when the patient cannot be read', () => {
    history.replaceState(bloodRequestState('p-1'), '');
    const component = create(['ROLE_DOCTOR']);
    patients.getById.and.returnValue(throwError(() => new Error('404')));

    component.ngOnInit();

    expect(component.showRequestModal()).toBeTrue();
    expect(component.selectedPatient()).toBeNull();
    expect(toast.error).toHaveBeenCalledWith('TRANSFUSION.HANDOVER_PATIENT_FAILED');
  });

  it('opens nothing for a role that cannot request blood, or with no patient handed over', () => {
    history.replaceState(bloodRequestState('p-1'), '');
    const nurse = create(['ROLE_NURSE']);
    nurse.ngOnInit();
    expect(nurse.showRequestModal()).toBeFalse();
    expect(patients.getById).not.toHaveBeenCalled();
  });

  it('opens nothing on an ordinary visit to the blood bank', () => {
    const component = create(['ROLE_DOCTOR']);
    component.ngOnInit();
    expect(component.showRequestModal()).toBeFalse();
    expect(patients.getById).not.toHaveBeenCalled();
  });
});
