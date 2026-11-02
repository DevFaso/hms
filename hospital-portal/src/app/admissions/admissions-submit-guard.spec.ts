import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { AdmissionsComponent } from './admissions';
import { AdmissionResponse, AdmissionService } from '../services/admission.service';
import { HospitalResponse, HospitalService } from '../services/hospital.service';
import { StaffService } from '../services/staff.service';
import { PatientService } from '../services/patient.service';
import { DepartmentLookupService } from '../services/department-lookup.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { HospitalScopeUrlService } from '../core/hospital-scope-url.service';
import { AuthService } from '../auth/auth.service';
import { BedResponse, BedService } from '../services/bed.service';
import { roleContextStub } from '../testing/role-context.stub';

/**
 * QA D1124 (round 20 merge): the bed-assign and discharge buttons were
 * disabled until their form was complete, so a click said nothing. They now
 * stay enabled, refuse the click, name the blank fields and keep `[disabled]`
 * for the request in flight only (shared/forms/submit-guard.directive.ts).
 */
describe('AdmissionsComponent submit buttons say why they refuse (QA D1124)', () => {
  const stay = {
    id: 'a1',
    hospitalId: 'h1',
    patientId: 'p1',
    patientName: 'Boukary Zongo',
    status: 'ACTIVE',
    roomBed: 'M1-04',
  } as AdmissionResponse;
  let admissions: jasmine.SpyObj<AdmissionService>;

  function mount() {
    admissions = jasmine.createSpyObj<AdmissionService>('AdmissionService', [
      'getAll',
      'assignBed',
      'discharge',
    ]);
    admissions.getAll.and.returnValue(of([stay]));
    admissions.assignBed.and.returnValue(of(stay));
    admissions.discharge.and.returnValue(of(stay));
    const hospitals = jasmine.createSpyObj<HospitalService>('HospitalService', [
      'list',
      'getMyHospitalAsResponse',
    ]);
    hospitals.list.and.returnValue(of([]));
    hospitals.getMyHospitalAsResponse.and.returnValue(of({ id: 'h1' } as HospitalResponse));
    const staff = jasmine.createSpyObj<StaffService>('StaffService', ['list']);
    staff.list.and.returnValue(of([]));
    const departments = jasmine.createSpyObj<DepartmentLookupService>('DepartmentLookupService', [
      'getActiveDepartments',
    ]);
    departments.getActiveDepartments.and.returnValue(of([]));
    const beds = jasmine.createSpyObj<BedService>('BedService', ['getAvailableBeds']);
    beds.getAvailableBeds.and.returnValue(
      of([{ id: 'b1', label: 'M1-05', wardName: 'Médecine' } as BedResponse]),
    );
    const auth = jasmine.createSpyObj<AuthService>('AuthService', ['getUserProfile']);
    auth.getUserProfile.and.returnValue(null);
    TestBed.configureTestingModule({
      imports: [AdmissionsComponent, TranslateModule.forRoot()],
      providers: [
        { provide: AdmissionService, useValue: admissions },
        { provide: HospitalService, useValue: hospitals },
        { provide: StaffService, useValue: staff },
        { provide: PatientService, useValue: jasmine.createSpyObj('PatientService', ['list']) },
        { provide: DepartmentLookupService, useValue: departments },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
        { provide: AuthService, useValue: auth },
        { provide: BedService, useValue: beds },
        { provide: HospitalScopeUrlService, useValue: { applyUrlScopeSync: () => undefined } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({}) } },
        },
        {
          provide: RoleContextService,
          useValue: roleContextStub({
            superAdmin: false,
            hospitalId: 'h1',
            roles: ['ROLE_DOCTOR'],
          }),
        },
      ],
    });
    const fixture = TestBed.createComponent(AdmissionsComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('Assign bed with no bed chosen is refused, the picker is marked, the button stays enabled', () => {
    const fixture = mount();
    fixture.componentInstance.openBedAssign(stay);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const dialog = host.querySelector<HTMLElement>(
      '[aria-labelledby="admissions-dialog-1-title"]',
    )!;
    const assign = dialog.querySelector<HTMLButtonElement>('.modal-actions .btn-primary')!;
    expect(assign.disabled).withContext('never silently disabled').toBeFalse();
    assign.click();
    fixture.detectChanges();
    expect(admissions.assignBed).not.toHaveBeenCalled();
    expect(host.querySelector('#adm-bed-select')?.getAttribute('aria-invalid')).toBe('true');
    expect(dialog.querySelector('[data-testid="submit-guard-reason"]')).not.toBeNull();

    fixture.componentInstance.selectedBedId.set('b1');
    fixture.detectChanges();
    assign.click();
    expect(admissions.assignBed).toHaveBeenCalledTimes(1);
  });

  it('Discharge with blank required fields is refused and names them', () => {
    const fixture = mount();
    fixture.componentInstance.confirmDischarge(stay);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const dialog = host.querySelector<HTMLElement>(
      '[aria-labelledby="admissions-dialog-5-title"]',
    )!;
    const discharge = dialog.querySelector<HTMLButtonElement>('.modal-actions .btn-primary')!;
    expect(discharge.disabled).withContext('never silently disabled').toBeFalse();
    discharge.click();
    fixture.detectChanges();
    expect(admissions.discharge).not.toHaveBeenCalled();
    expect(host.querySelector('#discharge-diagnosis')?.getAttribute('aria-invalid')).toBe('true');
    expect(host.querySelector('#discharge-summary')?.getAttribute('aria-invalid')).toBe('true');
    expect(dialog.querySelector('[data-testid="submit-guard-reason"]')).not.toBeNull();
  });
});
