import { HttpErrorResponse } from '@angular/common/http';
import { formatDate } from '@angular/common';
import { signal } from '@angular/core';
import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { AdmissionsComponent } from './admissions';
import { AdmissionResponse, AdmissionService } from '../services/admission.service';
import { HospitalResponse, HospitalService } from '../services/hospital.service';
import { StaffResponse, StaffService } from '../services/staff.service';
import { PatientResponse, PatientService } from '../services/patient.service';
import { DepartmentLookupService } from '../services/department-lookup.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { HospitalScopeUrlService } from '../core/hospital-scope-url.service';
import { AuthService } from '../auth/auth.service';
import { BedResponse, BedService } from '../services/bed.service';
import { AppliedOrderSetSummary } from '../services/order-set.service';
import { toLocalInputValue } from '../shared/date-utils';
import { roleContextStub } from '../testing/role-context.stub';

/**
 * QA D176 (regression of D84/D123): the server refused a nurse or a midwife as
 * admitting or discharging provider, but the picker still offered them and
 * the save and discharge toasts hid the server's reason behind a generic
 * text. The rest of the file covers the component's other flows.
 */
describe('AdmissionsComponent', () => {
  let component: AdmissionsComponent;
  let admissionService: jasmine.SpyObj<AdmissionService>;
  let hospitalService: jasmine.SpyObj<HospitalService>;
  let patientService: jasmine.SpyObj<PatientService>;
  let departmentLookup: jasmine.SpyObj<DepartmentLookupService>;
  let bedService: jasmine.SpyObj<BedService>;
  let auth: jasmine.SpyObj<AuthService>;
  let toast: jasmine.SpyObj<ToastService>;
  let superAdmin: ReturnType<typeof signal<boolean>>;
  let queryTab: string | null;
  let activeRoles: string[];

  const member = (id: string, roleCode: string) => ({ id, roleCode }) as StaffResponse;
  const adm = (overrides: Partial<AdmissionResponse> = {}) =>
    ({
      id: 'a1',
      hospitalId: 'h1',
      patientId: 'p1',
      patientName: 'Awa Traore Diallo',
      status: 'ACTIVE',
      chiefComplaint: 'Fever',
      roomBed: 'B-12',
      ...overrides,
    }) as AdmissionResponse;
  const serverRefusal = (message?: string) =>
    throwError(
      () => new HttpErrorResponse({ status: 400, error: message ? { message } : undefined }),
    );

  beforeEach(() => {
    queryTab = null;
    superAdmin = signal(false);
    activeRoles = ['ROLE_DOCTOR'];
    admissionService = jasmine.createSpyObj<AdmissionService>('AdmissionService', [
      'getAll',
      'create',
      'update',
      'delete',
      'discharge',
      'assignBed',
      'unassignBed',
    ]);
    admissionService.getAll.and.returnValue(
      of([
        adm(),
        adm({ id: 'a2', status: 'DISCHARGED', patientName: 'Moussa Kone', roomBed: undefined }),
      ]),
    );
    hospitalService = jasmine.createSpyObj<HospitalService>('HospitalService', [
      'list',
      'getMyHospitalAsResponse',
    ]);
    hospitalService.getMyHospitalAsResponse.and.returnValue(
      of({ id: 'h1', name: 'CHU Ouaga' } as HospitalResponse),
    );
    hospitalService.list.and.returnValue(
      of([
        { id: 'h1', name: 'A' } as HospitalResponse,
        { id: 'h2', name: 'B' } as HospitalResponse,
      ]),
    );
    const staffService = jasmine.createSpyObj<StaffService>('StaffService', ['list']);
    staffService.list.and.returnValue(
      of([
        member('doc', 'ROLE_DOCTOR'),
        member('nurse', 'NURSE'),
        member('midwife', 'MIDWIFE'),
        member('surgeon', 'SURGEON'),
      ]),
    );
    patientService = jasmine.createSpyObj<PatientService>('PatientService', ['list']);
    departmentLookup = jasmine.createSpyObj<DepartmentLookupService>('DepartmentLookupService', [
      'getActiveDepartments',
    ]);
    bedService = jasmine.createSpyObj<BedService>('BedService', ['getAvailableBeds']);
    auth = jasmine.createSpyObj<AuthService>('AuthService', ['getUserProfile']);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);

    TestBed.configureTestingModule({
      providers: [
        { provide: AdmissionService, useValue: admissionService },
        { provide: HospitalService, useValue: hospitalService },
        { provide: StaffService, useValue: staffService },
        { provide: PatientService, useValue: patientService },
        { provide: DepartmentLookupService, useValue: departmentLookup },
        { provide: ToastService, useValue: toast },
        {
          provide: RoleContextService,
          useValue: {
            activeHospitalId: 'h1',
            isSuperAdmin: superAdmin,
            globalView: signal(false),
            hasAnyActiveRole: (roles: string[]) => roles.some((r) => activeRoles.includes(r)),
          },
        },
        { provide: HospitalScopeUrlService, useValue: { applyUrlScopeSync: () => undefined } },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              get queryParamMap() {
                return convertToParamMap(queryTab ? { tab: queryTab } : {});
              },
            },
          },
        },
        { provide: TranslateService, useValue: { instant: (k: string) => k } },
        { provide: AuthService, useValue: auth },
        { provide: BedService, useValue: bedService },
      ],
    });
    component = TestBed.runInInjectionContext(() => new AdmissionsComponent());
  });

  describe('actions by role (QA D427/D527)', () => {
    function gates() {
      return {
        admit: component['canAdmit'](),
        edit: component['canEditAdmission'](),
        orderSets: component['canApplyOrderSets'](),
        assignBed: component['canAssignBed'](),
        unassignBed: component['canUnassignBed'](),
        discharge: component['canDischarge'](),
        cancel: component['canCancelAdmission'](),
      };
    }

    it('a nurse is offered no Delete, Discharge or New admission (DOCTOR-only endpoints)', () => {
      activeRoles = ['ROLE_NURSE'];
      expect(gates()).toEqual({
        admit: false,
        edit: true,
        orderSets: true,
        assignBed: true,
        unassignBed: true,
        discharge: false,
        cancel: false,
      });
    });

    it('a doctor keeps every admission action', () => {
      activeRoles = ['ROLE_DOCTOR'];
      expect(Object.values(gates()).every(Boolean)).toBeTrue();
    });

    it('a midwife reads admissions but is offered no write', () => {
      activeRoles = ['ROLE_MIDWIFE'];
      expect(Object.values(gates()).some(Boolean)).toBeFalse();
    });

    it('a super-admin moves beds only (PUT, discharge, cancel and admit refuse her)', () => {
      activeRoles = ['ROLE_SUPER_ADMIN'];
      const g = gates();
      expect(g.assignBed && g.unassignBed).toBeTrue();
      expect(g.edit || g.cancel || g.discharge || g.admit || g.orderSets).toBeFalse();
    });
  });

  describe('provider picker and refusals (QA D176)', () => {
    it('offers only physicians as provider, never a nurse or a midwife', () => {
      component.ngOnInit();
      expect(component.staffMembers().map((s) => s.id)).toEqual(['doc', 'surgeon']);
    });

    it("shows the server's reason when a save is refused", () => {
      admissionService.create.and.returnValue(
        serverRefusal('Le prestataire doit être un médecin.'),
      );
      component.submitForm();
      expect(toast.error).toHaveBeenCalledWith('Le prestataire doit être un médecin.');
      expect(component.saving()).toBeFalse();
    });

    it('falls back to the generic save text when the server gives none', () => {
      admissionService.create.and.returnValue(serverRefusal());
      component.submitForm();
      expect(toast.error).toHaveBeenCalledWith('ADMISSIONS.SAVE_FAILED');
    });

    it('QA D618: the discharge form offers the signed-in physician alone, not her colleagues', () => {
      auth.getUserProfile.and.returnValue({ id: 'u1', staffId: 'doc' } as never);
      component.ngOnInit();
      component.confirmDischarge(adm());
      expect(component.dischargeProviders().map((s) => s.id)).toEqual(['doc']);
      expect(component.dischargeForm.dischargingProviderId).toBe('doc');
    });

    it('QA D618: a physician missing from the staff list is still offered as herself', () => {
      auth.getUserProfile.and.returnValue({
        id: 'u9',
        staffId: 'st-9',
        firstName: 'Aminata',
        lastName: 'Sow',
      } as never);
      component.ngOnInit();
      expect(component.dischargeProviders()).toEqual([
        { id: 'st-9', name: 'Aminata Sow', email: '' },
      ]);
    });

    it("shows the server's reason when a discharge is refused, else the generic text", () => {
      component.confirmDischarge(adm());
      admissionService.discharge.and.returnValue(serverRefusal('Pas une infirmière.'));
      component.executeDischarge();
      expect(toast.error).toHaveBeenCalledWith('Pas une infirmière.');
      admissionService.discharge.and.returnValue(serverRefusal());
      component.executeDischarge();
      expect(toast.error).toHaveBeenCalledWith('ADMISSIONS.DISCHARGE_ERROR');
      expect(component.discharging()).toBeFalse();
    });
  });

  describe('loading and filters', () => {
    it('locks a non-super-admin to their hospital and honours the tab query', () => {
      queryTab = 'discharged';
      component.ngOnInit();
      expect(component.hospitals().map((h) => h.id)).toEqual(['h1']);
      expect(component.form.hospitalId).toBe('h1');
      expect(component.hospitalLocked).toBeTrue();
      expect(component.lockedHospitalName).toBe('CHU Ouaga');
      expect(component.activeTab()).toBe('discharged');
    });

    it('lets a super admin choose any hospital', () => {
      superAdmin.set(true);
      component.ngOnInit();
      expect(component.hospitals().length).toBe(2);
      expect(component.hospitalLocked).toBeFalse();
      expect(component.lockedHospitalName).toBe('COMMON.NO_HOSPITAL_ASSIGNED');
    });

    it('filters by tab and search term and counts by status', () => {
      component.load();
      component.setTab('admitted');
      expect(component.filtered().map((a) => a.id)).toEqual(['a1']);
      component.setTab('all');
      component.searchTerm = 'b-12';
      component.applyFilter();
      expect(component.filtered().map((a) => a.id)).toEqual(['a1']);
      component.searchTerm = 'moussa';
      component.applyFilter();
      expect(component.filtered().map((a) => a.id)).toEqual(['a2']);
      expect(component.countByStatus('ACTIVE')).toBe(1);
      component.onScopeChange(null);
      expect(admissionService.getAll).toHaveBeenCalledTimes(2);
    });

    it('reports a failed load', () => {
      admissionService.getAll.and.returnValue(throwError(() => new Error('x')));
      component.load();
      expect(toast.error).toHaveBeenCalledWith('ADMISSIONS.LOAD_FAILED');
      expect(component.loading()).toBeFalse();
    });

    it('maps status, acuity and labels', () => {
      expect(component.getStatusClass('ACTIVE')).toBe('status-admitted');
      expect(component.getStatusClass('DISCHARGED')).toBe('status-discharged');
      expect(component.getStatusClass('PENDING')).toBe('status-pending');
      expect(component.getStatusClass('TRANSFERRED')).toBe('status-transferred');
      expect(component.getStatusClass('X')).toBe('');
      expect(component.getAcuityClass('LEVEL_5_CRITICAL')).toBe('acuity-critical');
      expect(component.getAcuityClass('LEVEL_4_SEVERE')).toBe('acuity-high');
      expect(component.getAcuityClass('LEVEL_3_MAJOR')).toBe('acuity-moderate');
      expect(component.getAcuityClass('LEVEL_1_MINIMAL')).toBe('acuity-low');
      expect(component.acuityLabel['LEVEL_5_CRITICAL']).toBe('ADMISSIONS.ACUITY_CRITICAL');
    });
  });

  describe('patient picker', () => {
    it('searches after two characters and keeps at most eight suggestions', fakeAsync(() => {
      const many = Array.from(
        { length: 10 },
        (_, i) => ({ id: `p${i}`, firstName: 'A', lastName: 'B' }) as PatientResponse,
      );
      patientService.list.and.returnValue(of(many));
      component.initPatientSearch();
      component.onPatientQueryChange('a');
      expect(component.patientDropdownOpen()).toBeFalse();
      component.onPatientQueryChange('aw');
      tick(250);
      expect(patientService.list).toHaveBeenCalledWith('h1', 'aw');
      expect(component.patientSuggestions().length).toBe(8);
      expect(component.patientDropdownOpen()).toBeTrue();

      component.selectPatient(many[0]);
      expect(component.form.patientId).toBe('p0');
      expect(component.patientInitials(many[0])).toBe('AB');
      expect(component.patientInitials({} as PatientResponse)).toBe('?');
      component.clearPatient();
      expect(component.selectedPatient()).toBeNull();
    }));

    it('clears the spinner when the search fails', fakeAsync(() => {
      patientService.list.and.returnValue(throwError(() => new Error('x')));
      component.initPatientSearch();
      component.onPatientQueryChange('zz');
      tick(250);
      expect(component.patientSearchLoading()).toBeFalse();
    }));
  });

  describe('create, edit and delete', () => {
    it('loads departments for a hospital and reports a failure', () => {
      departmentLookup.getActiveDepartments.and.returnValue(of([{ id: 'd1', name: 'Med' }]));
      component.onHospitalChange('h1');
      expect(component.departments().length).toBe(1);
      departmentLookup.getActiveDepartments.and.returnValue(throwError(() => new Error('x')));
      component.loadDepartmentsFor('h1');
      expect(component.departmentsFailed()).toBeTrue();
      component.loadDepartmentsFor('');
      expect(component.departments()).toEqual([]);
      expect(component.departmentsFailed()).toBeFalse();
    });

    it('re-applies the locked hospital on create and saves', () => {
      // D153: opening the form now loads the departments of the locked hospital.
      departmentLookup.getActiveDepartments.and.returnValue(of([]));
      component.ngOnInit();
      component.openCreate();
      expect(component.form.hospitalId).toBe('h1');
      expect(component.showModal()).toBeTrue();
      admissionService.create.and.returnValue(of(adm()));
      component.submitForm();
      expect(toast.success).toHaveBeenCalledWith('ADMISSIONS.CREATED');
      expect(component.showModal()).toBeFalse();
    });

    it('sends the admission time as the UTC wall clock, not the browser one (QA D622)', () => {
      departmentLookup.getActiveDepartments.and.returnValue(of([]));
      component.ngOnInit();
      component.openCreate();
      component.form.admissionDateTime = '2026-10-23T07:48';
      admissionService.create.and.returnValue(of(adm()));
      component.submitForm();
      const sent = admissionService.create.calls.mostRecent().args[0];
      expect(sent.admissionDateTime).toBe(new Date('2026-10-23T07:48').toISOString().slice(0, 19));
    });

    it('edits an existing admission', () => {
      departmentLookup.getActiveDepartments.and.returnValue(of([]));
      component.openEdit(adm({ admissionDateTime: '2026-10-18T10:00:00' }));
      expect(component.editing()).toBeTrue();
      // QA D622: the stored UTC time, in the user's zone (not a bare date that blanks the input).
      expect(component.form.admissionDateTime).toBe(toLocalInputValue('2026-10-18T10:00:00'));
      expect(component.selectedPatient()?.lastName).toBe('Traore Diallo');
      admissionService.update.and.returnValue(of(adm()));
      component.submitForm();
      expect(admissionService.update).toHaveBeenCalledWith('a1', jasmine.any(Object));
      expect(toast.success).toHaveBeenCalledWith('ADMISSIONS.UPDATED');
    });

    it('deletes, and reports a failed delete', () => {
      component.confirmDelete(adm());
      expect(component.showDeleteConfirm()).toBeTrue();
      admissionService.delete.and.returnValue(of(undefined as unknown as void));
      component.executeDelete();
      expect(toast.success).toHaveBeenCalledWith('ADMISSIONS.DELETED');
      expect(component.deletingAdm()).toBeNull();
      component.confirmDelete(adm());
      admissionService.delete.and.returnValue(throwError(() => new Error('x')));
      component.executeDelete();
      expect(toast.error).toHaveBeenCalledWith('ADMISSIONS.DELETE_FAILED');
    });

    it('discharges', () => {
      component.confirmDischarge(adm());
      admissionService.discharge.and.returnValue(of(adm({ status: 'DISCHARGED' })));
      component.executeDischarge();
      expect(toast.success).toHaveBeenCalledWith('ADMISSIONS.DISCHARGE_SUCCESS');
      expect(component.showDischargeModal()).toBeFalse();
    });
  });

  describe('order sets', () => {
    it('opens the picker for the signed-in clinician, and refuses without one', () => {
      component.openOrderSetPicker(adm({ id: '' }));
      expect(component.orderSetPicker()).toBeNull();
      auth.getUserProfile.and.returnValue(null);
      component.openOrderSetPicker(adm());
      expect(toast.error).toHaveBeenCalledWith('ORDER_SETS.MISSING_ORDERING_STAFF');
      auth.getUserProfile.and.returnValue({ staffId: 's1' } as ReturnType<
        AuthService['getUserProfile']
      >);
      component.openOrderSetPicker(adm());
      expect(component.orderSetPicker()?.orderingStaffId).toBe('s1');
      component.onOrderSetApplied({
        prescriptionIds: ['x'],
        labOrderIds: ['y'],
        imagingOrderIds: [],
        skippedItemCount: 1,
      } as unknown as AppliedOrderSetSummary);
      expect(toast.success).toHaveBeenCalledWith('ORDER_SETS.APPLIED_RESULT');
      component.closeOrderSetPicker();
      expect(component.orderSetPicker()).toBeNull();
    });
  });

  describe('beds', () => {
    it('lists, assigns and unassigns a bed', () => {
      bedService.getAvailableBeds.and.returnValue(of([{ id: 'b1' } as BedResponse]));
      component.openBedAssign(adm());
      expect(component.availableBeds().length).toBe(1);
      // QA D609: the picker asks for the beds this patient may take.
      expect(bedService.getAvailableBeds).toHaveBeenCalledWith(adm().patientId);
      component.submitBedAssign();
      expect(admissionService.assignBed).not.toHaveBeenCalled();
      component.selectedBedId.set('b1');
      admissionService.assignBed.and.returnValue(of(adm({ roomBed: 'B-1' })));
      component.submitBedAssign();
      expect(toast.success).toHaveBeenCalledWith('ADMISSIONS.BED_ASSIGNED');
      expect(component.bedAssignFor()).toBeNull();
      admissionService.unassignBed.and.returnValue(of(adm()));
      component.unassignBed(adm());
      expect(toast.success).toHaveBeenCalledWith('ADMISSIONS.BED_UNASSIGNED');
    });

    it('reports bed failures', () => {
      bedService.getAvailableBeds.and.returnValue(throwError(() => new Error('x')));
      component.openBedAssign(adm());
      expect(toast.error).toHaveBeenCalledWith('ADMISSIONS.BEDS_LOAD_FAILED');
      component.selectedBedId.set('b1');
      admissionService.assignBed.and.returnValue(throwError(() => new Error('x')));
      component.submitBedAssign();
      expect(component.bedAssigning()).toBeFalse();
      admissionService.unassignBed.and.returnValue(throwError(() => new Error('x')));
      component.unassignBed(adm());
      expect(toast.error).toHaveBeenCalledWith('ADMISSIONS.BED_ASSIGN_FAILED');
    });
  });
});

/**
 * QA D527 (independent verification): the flags alone do not prove the page —
 * an `@if (true)` in the template would bring the nurse's Delete back with every
 * flag test still green. These render the row and read the DOM.
 */
describe('AdmissionsComponent row actions (rendered, D527)', () => {
  function render(roles: string[], status = 'ACTIVE', admissionDateTime?: string): HTMLElement {
    const admissions = jasmine.createSpyObj<AdmissionService>('AdmissionService', ['getAll']);
    admissions.getAll.and.returnValue(
      of([
        {
          id: 'a1',
          hospitalId: 'h1',
          patientId: 'p1',
          patientName: 'Awa Traore',
          status,
          chiefComplaint: 'Fever',
          admissionDateTime,
        } as AdmissionResponse,
      ]),
    );
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
        { provide: AuthService, useValue: jasmine.createSpyObj('AuthService', ['getUserProfile']) },
        { provide: BedService, useValue: jasmine.createSpyObj('BedService', ['getAvailableBeds']) },
        { provide: HospitalScopeUrlService, useValue: { applyUrlScopeSync: () => undefined } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({}) } },
        },
        {
          provide: RoleContextService,
          useValue: roleContextStub({ superAdmin: false, hospitalId: 'h1', roles }),
        },
      ],
    });
    const fixture = TestBed.createComponent(AdmissionsComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const newButton = (el: HTMLElement) => el.querySelector('.page-actions .btn-primary');

  it('shows a nurse edit, order sets and beds, but not New, Discharge or Delete', () => {
    const el = render(['ROLE_NURSE']);
    expect(el.querySelector('.edit-link')).not.toBeNull();
    expect(el.querySelector('[data-testid=open-order-set-picker]')).not.toBeNull();
    expect(el.querySelector('[data-testid=open-bed-assign]')).not.toBeNull();
    expect(el.querySelector('.delete-link')).toBeNull();
    expect(el.querySelector('.discharge-link')).toBeNull();
    expect(newButton(el)).toBeNull();
  });

  it('shows a doctor every row action and New', () => {
    const el = render(['ROLE_DOCTOR']);
    expect(el.querySelector('.edit-link')).not.toBeNull();
    expect(el.querySelector('[data-testid=open-order-set-picker]')).not.toBeNull();
    expect(el.querySelector('[data-testid=open-bed-assign]')).not.toBeNull();
    expect(el.querySelector('.delete-link')).not.toBeNull();
    expect(el.querySelector('.discharge-link')).not.toBeNull();
    expect(newButton(el)).not.toBeNull();
  });

  // QA D603: a discharged (finalized, billed) stay was deleted from the "Sorti" row.
  for (const status of ['DISCHARGED', 'TRANSFERRED', 'DECEASED', 'CANCELLED']) {
    it(`offers no Delete on a ${status} stay, even to a doctor`, () => {
      const el = render(['ROLE_DOCTOR'], status);
      expect(el.querySelector('.delete-link')).toBeNull();
    });
  }

  // QA D622: the admission time is stored as UTC wall clock; the row must show
  // the user's local date of that instant, not the bare string read as local.
  it('shows the admission date in the user zone (D622)', () => {
    const el = render(['ROLE_DOCTOR'], 'ACTIVE', '2026-10-23T02:30:00');
    const expected = formatDate(new Date(Date.UTC(2026, 9, 23, 2, 30)), 'mediumDate', 'en-US');
    const cells = Array.from(el.querySelectorAll('td')).map((td) => td.textContent?.trim());
    expect(cells).toContain(expected);
  });
});
