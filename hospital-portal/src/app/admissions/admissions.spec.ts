import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { TranslateService } from '@ngx-translate/core';
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
          useValue: { activeHospitalId: 'h1', isSuperAdmin: superAdmin, globalView: signal(false) },
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

    it('edits an existing admission', () => {
      departmentLookup.getActiveDepartments.and.returnValue(of([]));
      component.openEdit(adm({ admissionDateTime: '2026-10-18T10:00:00' }));
      expect(component.editing()).toBeTrue();
      expect(component.form.admissionDateTime).toBe('2026-10-18');
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
