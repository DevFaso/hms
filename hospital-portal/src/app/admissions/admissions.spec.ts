import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { AdmissionsComponent } from './admissions';
import { AdmissionService } from '../services/admission.service';
import { HospitalService } from '../services/hospital.service';
import { StaffResponse, StaffService } from '../services/staff.service';
import { PatientService } from '../services/patient.service';
import { DepartmentLookupService } from '../services/department-lookup.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { HospitalScopeUrlService } from '../core/hospital-scope-url.service';
import { AuthService } from '../auth/auth.service';
import { BedService } from '../services/bed.service';

/**
 * QA D176 (regression of D84/D123): the server refused a nurse or a midwife as
 * admitting or discharging provider, but the picker still offered them and
 * the save toast hid the server's reason behind a generic text.
 */
describe('AdmissionsComponent — physician-only provider picker (QA D176)', () => {
  let component: AdmissionsComponent;
  let admissionService: jasmine.SpyObj<AdmissionService>;
  let toast: jasmine.SpyObj<ToastService>;

  const member = (id: string, roleCode: string) => ({ id, roleCode }) as StaffResponse;

  beforeEach(() => {
    admissionService = jasmine.createSpyObj<AdmissionService>('AdmissionService', [
      'create',
      'update',
    ]);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
    const staffService = jasmine.createSpyObj<StaffService>('StaffService', ['list']);
    staffService.list.and.returnValue(
      of([
        member('doc', 'ROLE_DOCTOR'),
        member('nurse', 'NURSE'),
        member('midwife', 'MIDWIFE'),
        member('surgeon', 'SURGEON'),
      ]),
    );
    TestBed.configureTestingModule({
      providers: [
        { provide: AdmissionService, useValue: admissionService },
        { provide: HospitalService, useValue: {} },
        { provide: StaffService, useValue: staffService },
        { provide: PatientService, useValue: {} },
        { provide: DepartmentLookupService, useValue: {} },
        { provide: ToastService, useValue: toast },
        { provide: RoleContextService, useValue: { activeHospitalId: 'h1' } },
        { provide: HospitalScopeUrlService, useValue: { applyUrlScopeSync: () => undefined } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({}) } },
        },
        { provide: TranslateService, useValue: { instant: (k: string) => k } },
        { provide: AuthService, useValue: {} },
        { provide: BedService, useValue: {} },
      ],
    });
    component = TestBed.runInInjectionContext(() => new AdmissionsComponent());
    spyOn(component, 'load');
    spyOn(component, 'initPatientSearch');
    spyOn(component as unknown as { loadAssignedHospitals: () => void }, 'loadAssignedHospitals');
  });

  it('offers only physicians as provider, never a nurse or a midwife', () => {
    component.ngOnInit();
    expect(component.staffMembers().map((s) => s.id)).toEqual(['doc', 'surgeon']);
  });

  it("shows the server's reason when a save is refused", () => {
    admissionService.create.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 400,
            error: { message: 'Le prestataire doit être un médecin.' },
          }),
      ),
    );
    component.submitForm();
    expect(toast.error).toHaveBeenCalledWith('Le prestataire doit être un médecin.');
    expect(component.saving()).toBeFalse();
  });

  it('falls back to the generic text when the server gives none', () => {
    admissionService.create.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 500 })),
    );
    component.submitForm();
    expect(toast.error).toHaveBeenCalledWith('ADMISSIONS.SAVE_FAILED');
  });
});
