import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError, EMPTY } from 'rxjs';
import { AdmissionsComponent } from './admissions';
import { DepartmentLookupService } from '../services/department-lookup.service';
import { RoleContextService } from '../core/role-context.service';
import { roleContextStub } from '../testing/role-context.stub';
import { AdmissionResponse, AdmissionService } from '../services/admission.service';
import { HospitalResponse, HospitalService } from '../services/hospital.service';
import { StaffService } from '../services/staff.service';
import { PatientResponse, PatientService } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { HospitalScopeUrlService } from '../core/hospital-scope-url.service';
import { AuthService } from '../auth/auth.service';
import { BedService } from '../services/bed.service';

const adm = (over: Partial<AdmissionResponse> = {}): AdmissionResponse =>
  ({
    id: 'a1',
    hospitalId: 'h1',
    patientId: 'p1',
    patientName: 'Awa Traore',
    status: 'ACTIVE',
    chiefComplaint: 'Fever',
    roomBed: '12-B',
    ...over,
  }) as AdmissionResponse;

describe('AdmissionsComponent (inpatient round 3: D153 departments and stay flows)', () => {
  let departments: jasmine.SpyObj<DepartmentLookupService>;
  let admissions: jasmine.SpyObj<AdmissionService>;
  let hospitals: jasmine.SpyObj<HospitalService>;
  let staff: jasmine.SpyObj<StaffService>;
  let patients: jasmine.SpyObj<PatientService>;
  let toast: jasmine.SpyObj<ToastService>;
  let auth: jasmine.SpyObj<AuthService>;
  let beds: jasmine.SpyObj<BedService>;
  let tabParam: string | null;

  function setup(superAdmin = false): AdmissionsComponent {
    TestBed.configureTestingModule({
      imports: [AdmissionsComponent, TranslateModule.forRoot()],
      providers: [
        { provide: DepartmentLookupService, useValue: departments },
        { provide: AdmissionService, useValue: admissions },
        { provide: HospitalService, useValue: hospitals },
        { provide: StaffService, useValue: staff },
        { provide: PatientService, useValue: patients },
        { provide: ToastService, useValue: toast },
        { provide: AuthService, useValue: auth },
        { provide: BedService, useValue: beds },
        { provide: HospitalScopeUrlService, useValue: { applyUrlScopeSync: () => undefined } },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { queryParamMap: convertToParamMap(tabParam ? { tab: tabParam } : {}) },
          },
        },
        {
          provide: RoleContextService,
          useValue: roleContextStub({
            superAdmin,
            hospitalId: 'h1',
            roles: [superAdmin ? 'ROLE_SUPER_ADMIN' : 'ROLE_DOCTOR'],
          }),
        },
      ],
    });
    return TestBed.createComponent(AdmissionsComponent).componentInstance;
  }

  beforeEach(() => {
    tabParam = null;
    departments = jasmine.createSpyObj('DepartmentLookupService', ['getActiveDepartments']);
    departments.getActiveDepartments.and.returnValue(of([{ id: 'd1', name: 'Médecine' }]));
    admissions = jasmine.createSpyObj('AdmissionService', [
      'getAll',
      'create',
      'update',
      'delete',
      'discharge',
      'assignBed',
      'unassignBed',
    ]);
    admissions.getAll.and.returnValue(
      of([adm(), adm({ id: 'a2', status: 'DISCHARGED', patientName: 'Moussa Diallo' })]),
    );
    admissions.create.and.returnValue(of(adm()));
    admissions.update.and.returnValue(of(adm()));
    admissions.delete.and.returnValue(of(undefined));
    admissions.discharge.and.returnValue(of(adm()));
    admissions.assignBed.and.returnValue(of(adm({ roomBed: '3-A' })));
    admissions.unassignBed.and.returnValue(of(adm()));
    hospitals = jasmine.createSpyObj('HospitalService', ['list', 'getMyHospitalAsResponse']);
    hospitals.list.and.returnValue(of([{ id: 'h1', name: 'A' } as never, { id: 'h2' } as never]));
    hospitals.getMyHospitalAsResponse.and.returnValue(
      of({ id: 'h1', name: 'Hôpital A' } as HospitalResponse),
    );
    staff = jasmine.createSpyObj('StaffService', ['list']);
    staff.list.and.returnValue(of([]));
    patients = jasmine.createSpyObj('PatientService', ['list']);
    patients.list.and.returnValue(of([{ id: 'p1', firstName: 'Awa', lastName: 'T' } as never]));
    toast = jasmine.createSpyObj('ToastService', ['success', 'error']);
    auth = jasmine.createSpyObj('AuthService', ['getUserProfile']);
    auth.getUserProfile.and.returnValue({ staffId: 's1' } as never);
    beds = jasmine.createSpyObj('BedService', ['getAvailableBeds']);
    beds.getAvailableBeds.and.returnValue(of([{ id: 'b1' } as never]));
  });

  it('loads the departments for a single-hospital user who never picks a hospital (D153)', () => {
    const component = setup();
    component.ngOnInit();

    component.openCreate();

    expect(component.form.hospitalId).toBe('h1');
    expect(departments.getActiveDepartments).toHaveBeenCalledWith('h1');
    expect(component.departments().length).toBe(1);
  });

  it('loads the departments when the hospital arrives after the modal opened (D153)', () => {
    hospitals.getMyHospitalAsResponse.and.returnValue(EMPTY);
    const component = setup();
    component.ngOnInit();
    component.openCreate();
    expect(departments.getActiveDepartments).not.toHaveBeenCalled();

    hospitals.getMyHospitalAsResponse.and.returnValue(
      of({ id: 'h1', name: 'A' } as HospitalResponse),
    );
    (component as unknown as { loadAssignedHospitals(): void }).loadAssignedHospitals();

    expect(departments.getActiveDepartments).toHaveBeenCalledWith('h1');
  });

  it('flags a failed department lookup and clears the list for no hospital', () => {
    const component = setup();
    departments.getActiveDepartments.and.returnValue(throwError(() => new Error('x')));
    component.onHospitalChange('h2');
    expect(component.departmentsFailed()).toBeTrue();
    expect(component.form.departmentId).toBeUndefined();
    component.loadDepartmentsFor('');
    expect(component.departmentsFailed()).toBeFalse();
    expect(component.departments()).toEqual([]);
  });

  it('lets a super-admin choose among all hospitals and honours the tab query param', () => {
    tabParam = 'discharged';
    const component = setup(true);
    component.ngOnInit();
    expect(component.hospitals().length).toBe(2);
    expect(component.hospitalLocked).toBeFalse();
    expect(component.lockedHospitalName).toBe('COMMON.NO_HOSPITAL_ASSIGNED');
    expect(component.activeTab()).toBe('discharged');
    component.setTab(component.activeTab());
    expect(component.filtered().map((a) => a.id)).toEqual(['a2']);
  });

  it('filters by tab and search term and counts by status', () => {
    const component = setup();
    component.ngOnInit();
    expect(component.lockedHospitalName).toBe('Hôpital A');
    component.setTab('admitted');
    expect(component.filtered().map((a) => a.id)).toEqual(['a1']);
    component.setTab('all');
    component.searchTerm = 'moussa';
    component.applyFilter();
    expect(component.filtered().map((a) => a.id)).toEqual(['a2']);
    expect(component.countByStatus('ACTIVE')).toBe(1);
    component.onScopeChange(null);
    expect(admissions.getAll).toHaveBeenCalledTimes(2);
  });

  it('reports a failed list load', () => {
    admissions.getAll.and.returnValue(throwError(() => new Error('x')));
    const component = setup();
    component.load();
    expect(toast.error).toHaveBeenCalled();
    expect(component.loading()).toBeFalse();
  });

  it('searches, selects and clears a patient', fakeAsync(() => {
    const component = setup();
    component.ngOnInit();
    component.onPatientQueryChange('a');
    expect(component.patientDropdownOpen()).toBeFalse();
    component.onPatientQueryChange('aw');
    tick(250);
    expect(component.patientSuggestions().length).toBe(1);
    const p = { id: 'p1', firstName: 'Awa', lastName: 'Traore' } as PatientResponse;
    expect(component.patientInitials(p)).toBe('AT');
    expect(component.patientInitials({} as PatientResponse)).toBe('?');
    component.selectPatient(p);
    expect(component.form.patientId).toBe('p1');
    component.clearPatient();
    expect(component.form.patientId).toBe('');
  }));

  it('stops the patient spinner when the search fails', fakeAsync(() => {
    patients.list.and.returnValue(throwError(() => new Error('x')));
    const component = setup();
    component.initPatientSearch();
    component.onPatientQueryChange('aw');
    tick(250);
    expect(component.patientSearchLoading()).toBeFalse();
  }));

  it('creates and updates an admission, and reports a failed save', () => {
    const component = setup();
    component.openCreate();
    component.submitForm();
    expect(admissions.create).toHaveBeenCalled();
    component.openEdit(adm({ admissionDateTime: '2026-10-01T10:00:00' }));
    expect(component.selectedPatient()?.lastName).toBe('Traore');
    expect(component.form.admissionDateTime).toBe('2026-10-01');
    component.submitForm();
    expect(admissions.update).toHaveBeenCalledWith('a1', jasmine.any(Object));
    admissions.update.and.returnValue(throwError(() => new Error('x')));
    component.submitForm();
    expect(toast.error).toHaveBeenCalled();
    expect(component.saving()).toBeFalse();
    component.closeModal();
    expect(component.showModal()).toBeFalse();
  });

  it('deletes an admission, and reports a failed delete', () => {
    const component = setup();
    component.confirmDelete(adm());
    component.executeDelete();
    expect(admissions.delete).toHaveBeenCalledWith('a1');
    expect(component.showDeleteConfirm()).toBeFalse();
    admissions.delete.and.returnValue(throwError(() => new Error('x')));
    component.confirmDelete(adm());
    component.executeDelete();
    expect(component.deleting()).toBeFalse();
    expect(toast.error).toHaveBeenCalled();
  });

  it('discharges an admission, and reports a failed discharge', () => {
    const component = setup();
    component.confirmDischarge(adm());
    component.executeDischarge();
    expect(admissions.discharge).toHaveBeenCalled();
    expect(component.showDischargeModal()).toBeFalse();
    admissions.discharge.and.returnValue(throwError(() => new Error('x')));
    component.confirmDischarge(adm());
    component.executeDischarge();
    expect(component.discharging()).toBeFalse();
  });

  it('assigns and unassigns a bed, and reports failures', () => {
    const component = setup();
    component.openBedAssign(adm());
    expect(component.availableBeds().length).toBe(1);
    component.submitBedAssign();
    expect(admissions.assignBed).not.toHaveBeenCalled();
    component.selectedBedId.set('b1');
    component.submitBedAssign();
    expect(admissions.assignBed).toHaveBeenCalledWith('a1', 'b1');
    expect(component.bedAssignFor()).toBeNull();
    admissions.assignBed.and.returnValue(throwError(() => new Error('x')));
    component.openBedAssign(adm());
    component.selectedBedId.set('b1');
    component.submitBedAssign();
    expect(component.bedAssigning()).toBeFalse();
    component.unassignBed(adm());
    admissions.unassignBed.and.returnValue(throwError(() => new Error('x')));
    component.unassignBed(adm());
    beds.getAvailableBeds.and.returnValue(throwError(() => new Error('x')));
    component.openBedAssign(adm());
    expect(component.bedsLoading()).toBeFalse();
    expect(toast.error).toHaveBeenCalledTimes(3);
  });

  it('opens the order-set picker only with an ordering clinician', () => {
    const component = setup();
    component.openOrderSetPicker(adm({ id: '' }));
    expect(component.orderSetPicker()).toBeNull();
    component.openOrderSetPicker(adm());
    expect(component.orderSetPicker()?.orderingStaffId).toBe('s1');
    component.closeOrderSetPicker();
    expect(component.orderSetPicker()).toBeNull();
    auth.getUserProfile.and.returnValue(null as never);
    component.openOrderSetPicker(adm());
    expect(toast.error).toHaveBeenCalled();
    component.onOrderSetApplied({
      prescriptionIds: ['x'],
      labOrderIds: [],
      imagingOrderIds: [],
      skippedItemCount: 0,
    } as never);
    expect(toast.success).toHaveBeenCalled();
  });

  it('maps status and acuity classes and labels', () => {
    const component = setup();
    const statuses = ['ACTIVE', 'DISCHARGED', 'PENDING', 'TRANSFERRED', 'X'];
    expect(statuses.map((s) => component.getStatusClass(s))).toEqual([
      'status-admitted',
      'status-discharged',
      'status-pending',
      'status-transferred',
      '',
    ]);
    const levels = ['LEVEL_5_CRITICAL', 'LEVEL_4_SEVERE', 'LEVEL_3_MAJOR', 'LEVEL_1_MINIMAL'];
    expect(levels.map((l) => component.getAcuityClass(l))).toEqual([
      'acuity-critical',
      'acuity-high',
      'acuity-moderate',
      'acuity-low',
    ]);
    expect(Object.keys(component.acuityLabel).length).toBe(5);
  });
});
