import { HttpErrorResponse } from '@angular/common/http';
import { formatDate } from '@angular/common';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Subject, of, throwError } from 'rxjs';

import { AdmissionsComponent } from './admissions';
import { AdmissionResponse, AdmissionService } from '../services/admission.service';
import { DischargeService, DischargeSummary } from '../services/discharge.service';
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
import {
  EXPECTED_PICKER_SUBLINES,
  renderPickerSublines,
} from '../testing/patient-subline.fixtures';

/**
 * QA D176 (regression of D84/D123): the server refused a nurse or a midwife as
 * admitting or discharging provider, but the picker still offered them and
 * the save and discharge toasts hid the server's reason behind a generic
 * text. The rest of the file covers the component's other flows.
 */
describe('AdmissionsComponent', () => {
  let component: AdmissionsComponent;
  let admissionService: jasmine.SpyObj<AdmissionService>;
  let dischargeService: jasmine.SpyObj<DischargeService>;
  let hospitalService: jasmine.SpyObj<HospitalService>;
  let patientService: jasmine.SpyObj<PatientService>;
  let departmentLookup: jasmine.SpyObj<DepartmentLookupService>;
  let bedService: jasmine.SpyObj<BedService>;
  let auth: jasmine.SpyObj<AuthService>;
  let toast: jasmine.SpyObj<ToastService>;
  let superAdmin: ReturnType<typeof signal<boolean>>;
  let queryTab: string | null;
  let activeRoles: string[];
  /** The request scope (X-Hospital-Id): the chip's hospital for a super-admin. */
  let requestScope: string | null;

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
    requestScope = 'h1';
    dischargeService = jasmine.createSpyObj<DischargeService>('DischargeService', [
      'summaryForEncounter',
    ]);
    dischargeService.summaryForEncounter.and.returnValue(of(null));
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
        { provide: DischargeService, useValue: dischargeService },
        { provide: HospitalService, useValue: hospitalService },
        { provide: StaffService, useValue: staffService },
        { provide: PatientService, useValue: patientService },
        { provide: DepartmentLookupService, useValue: departmentLookup },
        { provide: ToastService, useValue: toast },
        {
          provide: RoleContextService,
          useValue: {
            activeHospitalId: 'h1',
            effectiveHospitalIdForRequest: () => requestScope,
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

    it('a midwife is offered New admission for labour and delivery only, and no other write (policy 2)', () => {
      activeRoles = ['ROLE_MIDWIFE'];
      expect(gates()).toEqual({
        admit: true,
        edit: false,
        orderSets: false,
        assignBed: false,
        unassignBed: false,
        discharge: false,
        cancel: false,
      });
      expect(component['formAdmissionTypes']()).toEqual(['LABOR_DELIVERY']);
    });

    it('D1634: a midwife admitting sees a neutral provider label', () => {
      activeRoles = ['ROLE_MIDWIFE'];
      expect(component['admittingProviderKeys']()).toEqual({
        label: 'ADMISSIONS.ADMITTING_CLINICIAN',
        select: 'ADMISSIONS.SELECT_CLINICIAN',
      });
    });

    it('D1634: a doctor admitting keeps the physician label', () => {
      activeRoles = ['ROLE_DOCTOR'];
      expect(component['admittingProviderKeys']().label).toBe('ADMISSIONS.ADMITTING_PROVIDER');
    });

    it('a doctor keeps every admission type on the form', () => {
      activeRoles = ['ROLE_DOCTOR'];
      expect(component['formAdmissionTypes']().length).toBeGreaterThan(1);
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
    it('counts only active stays with no structured or free-text bed in the missing-bed card', () => {
      component.admissions.set([
        adm({ id: 'missing', roomBed: '  ', bedId: null }),
        adm({ id: 'structured', roomBed: '', bedId: 'bed-1' }),
        adm({ id: 'legacy', roomBed: 'CDX-12', bedId: null }),
        adm({ id: 'closed', status: 'DISCHARGED', roomBed: '' }),
      ]);
      component.selectMetric('missing-bed');
      expect(component.filtered().map((a) => a.id)).toEqual(['missing']);
      expect(component.summaryMetrics().find((m) => m.id === 'missing-bed')?.count).toBe(1);
      expect(component.admissions().length).toBe(4);
      expect(admissionService.getAll).not.toHaveBeenCalled();
      component.selectMetric('missing-bed');
      expect(component.filtered().length).toBe(4);
    });

    it('combines facets and MRN/reference searches, sorts UTC instants and resets paging', () => {
      component.admissions.set(
        Array.from({ length: 26 }, (_, i) =>
          adm({
            id: `cdx-${i}`,
            patientMrn: `CDX-MRN-${i}`,
            encounterCode: `CDX-ENC-${i}`,
            admissionType: i === 25 ? 'EMERGENCY' : 'ELECTIVE',
            acuityLevel: 'LEVEL_4_SEVERE',
            admissionDateTime: i === 25 ? '2026-11-07T12:00:00' : '2026-11-07T11:00:00Z',
          }),
        ),
      );
      component.applyFilter();
      expect(component.pagedAdmissions()[0].id).toBe('cdx-25');
      expect(component.totalPages()).toBe(3);
      component.setPage(2);
      expect(component.pagedAdmissions().length).toBe(6);
      component.typeFilter.set('EMERGENCY');
      component.acuityFilter.set('LEVEL_4_SEVERE');
      component.searchTerm = 'CDX-MRN-25';
      component.applyFilter();
      expect(component.filtered().map((a) => a.id)).toEqual(['cdx-25']);
      expect(component.currentPage()).toBe(0);
      component.searchTerm = 'CDX-ENC-25';
      component.applyFilter();
      expect(component.filtered().length).toBe(1);
      component.resetFilters();
      expect(component.filtered().length).toBe(26);
    });

    it('ignores stale scoped responses and clears data on a current reload error', () => {
      const old = new Subject<AdmissionResponse[]>();
      const current = new Subject<AdmissionResponse[]>();
      admissionService.getAll.and.returnValues(old, current);
      component.load();
      component.onScopeChange('h2');
      current.next([adm({ id: 'new', hospitalId: 'h2' })]);
      old.next([adm({ id: 'old', hospitalId: 'h1' })]);
      expect(component.admissions().map((a) => a.id)).toEqual(['new']);
      old.error(new Error('stale'));
      expect(component.loadError()).toBeFalse();
      expect(toast.error).not.toHaveBeenCalled();
      admissionService.getAll.and.returnValue(throwError(() => new Error('offline')));
      component.onScopeChange('h1');
      expect(component.admissions()).toEqual([]);
      expect(component.filtered()).toEqual([]);
      expect(component.loadError()).toBeTrue();
    });

    it('locks a non-super-admin to their hospital and honours the tab query', () => {
      queryTab = 'discharged';
      component.ngOnInit();
      expect(component.hospitals().map((h) => h.id)).toEqual(['h1']);
      expect(component.form.hospitalId).toBe('h1');
      expect(component.hospitalLocked).toBeTrue();
      expect(component.lockedHospitalName).toBe('CHU Ouaga');
      expect(component.activeTab()).toBe('discharged');
      expect(component.filtered().map((a) => a.id)).toEqual(['a2']);
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

    // QA D912 (#967 review): a super-admin whose home hospital is A but whose
    // scope chip picked B was offered A's patients, and A's MRN on the chip.
    it("searches the scope chip's hospital, not a super-admin's home one", fakeAsync(() => {
      superAdmin.set(true);
      requestScope = 'hB';
      patientService.list.and.returnValue(of([]));
      component.initPatientSearch();
      component.onPatientQueryChange('kab');
      tick(250);
      expect(patientService.list).toHaveBeenCalledWith('hB', 'kab');

      // A hospital picked on the form wins: the stay is created there.
      component.form.hospitalId = 'hC';
      component.onPatientQueryChange('zon');
      tick(250);
      expect(patientService.list).toHaveBeenCalledWith('hC', 'zon');
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

    // QA D918: the physicians were loaded once for the home hospital, so a stay
    // at another hospital offered physicians the server refuses there.
    it("offers the physicians of the stay's hospital", () => {
      departmentLookup.getActiveDepartments.and.returnValue(of([]));
      const staff = TestBed.inject(StaffService) as jasmine.SpyObj<StaffService>;
      component.ngOnInit();
      expect(staff.list).toHaveBeenCalledWith('h1');

      component.openEdit(adm({ hospitalId: 'hB' }));
      expect(staff.list).toHaveBeenCalledWith('hB');

      component.form.admittingProviderId = 'doc';
      component.onHospitalChange('h2');
      expect(staff.list).toHaveBeenCalledWith('h2');
      expect(component.form.admittingProviderId).toBe('');
    });

    it("offers no physician of the previous hospital while the next hospital's load (QA D918)", () => {
      departmentLookup.getActiveDepartments.and.returnValue(of([]));
      const staff = TestBed.inject(StaffService) as jasmine.SpyObj<StaffService>;
      component.ngOnInit();
      expect(component.staffMembers().length).toBeGreaterThan(0);

      const pending = new Subject<StaffResponse[]>();
      staff.list.and.returnValue(pending);
      component.onHospitalChange('hB');
      expect(component.staffMembers()).toEqual([]);

      pending.next([member('docB', 'ROLE_DOCTOR')]);
      expect(component.staffMembers().map((s) => s.id)).toEqual(['docB']);
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
      component.deleteReason.set('Admission en double');
      component.executeDelete();
      expect(admissionService.delete).toHaveBeenCalledWith('a1', 'Admission en double');
      expect(toast.success).toHaveBeenCalledWith('ADMISSIONS.DELETED');
      expect(component.deletingAdm()).toBeNull();
      component.confirmDelete(adm());
      component.deleteReason.set('Erreur de saisie');
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

    const written = (overrides: Partial<DischargeSummary> = {}) =>
      ({
        id: 's1',
        encounterId: 'e1',
        dischargeDiagnosis: 'R50.9 Fièvre',
        followUpInstructions: 'Contrôle à J7',
        isFinalized: false,
        ...overrides,
      }) as DischargeSummary;

    it("QA D1596: with a written summary, its diagnosis is shown, not typed again, and the dialog's instructions are sent", () => {
      dischargeService.summaryForEncounter.and.returnValue(of(written()));
      component.confirmDischarge(adm({ encounterId: 'e1' }));
      expect(dischargeService.summaryForEncounter).toHaveBeenCalledWith('e1');
      expect(component.staySummary()?.id).toBe('s1');
      expect(component.dischargeForm.dischargeDiagnosis).toBe('R50.9 Fièvre');

      component.dischargeForm.dischargeSummary = 'Évolution favorable';
      component.dischargeForm.dischargeInstructions = 'Paracétamol si fièvre';
      admissionService.discharge.and.returnValue(of(adm({ status: 'DISCHARGED' })));
      component.executeDischarge();
      const body = admissionService.discharge.calls.mostRecent().args[1];
      expect(body['dischargeDiagnosis']).toBeUndefined();
      expect(body['dischargeInstructions']).toBe('Paracétamol si fièvre');
    });

    it('QA D1596: a finalized summary takes no further instructions from the dialog', () => {
      dischargeService.summaryForEncounter.and.returnValue(of(written({ isFinalized: true })));
      component.confirmDischarge(adm({ encounterId: 'e1' }));
      component.dischargeForm.dischargeInstructions = 'typed anyway';
      admissionService.discharge.and.returnValue(of(adm({ status: 'DISCHARGED' })));
      component.executeDischarge();
      expect(
        admissionService.discharge.calls.mostRecent().args[1]['dischargeInstructions'],
      ).toBeUndefined();
    });

    it('QA D1596: without a written summary, the typed diagnosis is sent', () => {
      component.confirmDischarge(adm({ encounterId: 'e1' }));
      expect(component.staySummary()).toBeNull();
      component.dischargeForm.dischargeDiagnosis = ' Paludisme grave ';
      admissionService.discharge.and.returnValue(of(adm({ status: 'DISCHARGED' })));
      component.executeDischarge();
      expect(admissionService.discharge.calls.mostRecent().args[1]['dischargeDiagnosis']).toBe(
        'Paludisme grave',
      );
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
      // QA D1100: freeing the bed goes through a dialog with a reason.
      admissionService.unassignBed.and.returnValue(of(adm()));
      component.openUnassignBed(adm());
      component.unassignReason.set('Transfert en réanimation');
      component.confirmUnassignBed();
      expect(admissionService.unassignBed).toHaveBeenCalledWith(
        adm().id,
        'Transfert en réanimation',
      );
      expect(toast.success).toHaveBeenCalledWith('ADMISSIONS.BED_UNASSIGNED');
      expect(component.unassigningAdm()).toBeNull();
    });

    // QA D1100 (round 19): "Retirer le lit" freed an inpatient's bed in one click.
    it('QA D1100: freeing a bed asks for confirmation and a reason first', () => {
      component.openUnassignBed(adm());
      expect(component.unassigningAdm()).withContext('the dialog opens').toBeTruthy();
      expect(admissionService.unassignBed).not.toHaveBeenCalled();
      component.unassignReason.set('   ');
      component.confirmUnassignBed();
      expect(admissionService.unassignBed)
        .withContext('no reason, no release')
        .not.toHaveBeenCalled();
      component.closeUnassignBed();
      expect(component.unassigningAdm()).toBeNull();
      expect(admissionService.unassignBed).not.toHaveBeenCalled();
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
      component.openUnassignBed(adm());
      component.unassignReason.set('Erreur de lit');
      component.confirmUnassignBed();
      expect(toast.error).toHaveBeenCalledWith('ADMISSIONS.BED_ASSIGN_FAILED');
    });
  });

  describe('QA D1428 / D1439', () => {
    it('offers no edit on a closed stay, and one on every open stay', () => {
      for (const status of [
        'DISCHARGED',
        'CANCELLED',
        'TRANSFERRED',
        'DECEASED',
        'CLOSED_IN_ERROR',
      ]) {
        expect(component.isEditable(adm({ status } as Partial<AdmissionResponse>)))
          .withContext(status)
          .toBeFalse();
      }
      for (const status of ['PENDING', 'ACTIVE', 'ON_LEAVE', 'AWAITING_DISCHARGE']) {
        expect(component.isEditable(adm({ status } as Partial<AdmissionResponse>)))
          .withContext(status)
          .toBeTrue();
      }
    });

    it('the room follows the held bed: read-only while editing a stay that holds one', () => {
      departmentLookup.getActiveDepartments.and.returnValue(of([]));
      component.openEdit(adm({ bedId: 'b1' } as Partial<AdmissionResponse>));
      expect(component.editingHoldsBed()).toBeTrue();
      component.openEdit(adm({ bedId: undefined } as Partial<AdmissionResponse>));
      expect(component.editingHoldsBed()).toBeFalse();
    });

    it('says one order in the singular, as the panel does', () => {
      toast.success.calls.reset();
      component.onOrderSetApplied({
        prescriptionIds: ['x'],
        labOrderIds: [],
        imagingOrderIds: [],
        skippedItemCount: 0,
      } as unknown as AppliedOrderSetSummary);
      expect(toast.success).toHaveBeenCalledWith('ORDER_SETS.APPLIED_RESULT_ONE');
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
    return mount(roles, status, admissionDateTime).nativeElement as HTMLElement;
  }

  function mount(
    roles: string[],
    status = 'ACTIVE',
    admissionDateTime?: string,
    superAdmin = false,
  ): ComponentFixture<AdmissionsComponent> {
    const admissions = jasmine.createSpyObj<AdmissionService>('AdmissionService', [
      'getAll',
      'delete',
    ]);
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
    hospitals.list.and.returnValue(
      of(
        superAdmin
          ? ([
              { id: 'h1', name: 'Hôpital A' },
              { id: 'hB', name: 'Clinique B' },
            ] as HospitalResponse[])
          : [],
      ),
    );
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
        { provide: DischargeService, useValue: { summaryForEncounter: () => of(null) } },
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
          useValue: roleContextStub({ superAdmin, hospitalId: 'h1', roles }),
        },
      ],
    });
    const fixture = TestBed.createComponent(AdmissionsComponent);
    fixture.detectChanges();
    return fixture;
  }

  const newButton = (el: HTMLElement) => el.querySelector('.page-actions .btn-primary');

  /**
   * QA D1381 (round 32): the delete dialog asks for the reason, refuses a
   * blank one on the field, and sends nothing. QA D1388: the patient's name is
   * followed by a space, never glued to "Cette action est irréversible."
   */
  it('QA D1381 / D1388: the delete dialog asks the reason and reads cleanly', () => {
    const fixture = mount(['ROLE_DOCTOR']);
    const service = TestBed.inject(AdmissionService) as unknown as { delete: jasmine.Spy };
    fixture.componentInstance.confirmDelete({
      id: 'a1',
      hospitalId: 'h1',
      patientId: 'p1',
      patientName: 'Fatimata Zongo',
      status: 'ACTIVE',
    } as AdmissionResponse);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const reason = el.querySelector<HTMLTextAreaElement>('#admission-cancel-reason');
    expect(reason).withContext('the reason field').not.toBeNull();
    expect(reason!.getAttribute('data-server-field')).toBe('reason');
    fixture.componentInstance.executeDelete();
    fixture.detectChanges();
    expect(service.delete).not.toHaveBeenCalled();
    expect(reason!.getAttribute('aria-invalid')).toBe('true');
    const warning = el.querySelector('.delete-warning')!;
    expect(warning.textContent!.replace(/\s+/g, ' ')).not.toMatch(/Zongo\S/);
  });

  it('QA D1597: a patient with no bed reads "no room assigned", never "N/A"', () => {
    const fixture = mount(['ROLE_DOCTOR']);
    fixture.componentInstance.confirmDischarge({
      id: 'a1',
      hospitalId: 'h1',
      patientId: 'p1',
      patientName: 'Fatimata Zongo',
      status: 'ACTIVE',
    } as AdmissionResponse);
    fixture.detectChanges();
    const info = (fixture.nativeElement as HTMLElement).querySelector('.discharge-info')!;
    expect(info.textContent).not.toContain('N/A');
    expect(info.querySelector('[data-testid="discharge-no-room"]')).not.toBeNull();
  });

  // QA D903/D912: the chip showed the email, or an empty line for a phone-only
  // patient, and the option led with the email; both now name the MRN.
  it('names the patient by MRN in the chip and the options (QA D912)', () => {
    const fixture = mount(['ROLE_DOCTOR']);
    fixture.componentInstance.openCreate();
    fixture.detectChanges();
    expect(renderPickerSublines(fixture, fixture.componentInstance)).toEqual(
      EXPECTED_PICKER_SUBLINES,
    );
  });

  // QA D912: [value] on the select ran before its @for options existed, so a
  // super-admin reopening a stay at B saw "Select a hospital".
  it("shows a super-admin the reopened stay's hospital as selected (QA D912)", () => {
    const fixture = mount(['ROLE_SUPER_ADMIN'], 'ACTIVE', undefined, true);
    fixture.componentInstance.openEdit({
      id: 'a2',
      hospitalId: 'hB',
      patientId: 'p1',
      patientName: 'Awa Kaboré',
      status: 'ACTIVE',
    } as AdmissionResponse);
    fixture.detectChanges();
    const select = (fixture.nativeElement as HTMLElement).querySelector(
      '#adm-hospitalId',
    ) as HTMLSelectElement;
    expect(select.value).toBe('hB');
    expect(select.selectedOptions[0]?.textContent?.trim()).toBe('Clinique B');
  });

  it('keeps the MRN on the chip when an admission is reopened (QA D912)', () => {
    const fixture = mount(['ROLE_DOCTOR']);
    fixture.componentInstance.openEdit({
      id: 'a1',
      hospitalId: 'h1',
      patientId: 'p1',
      patientName: 'Awa Kaboré',
      patientMrn: 'CHW2572',
      status: 'ACTIVE',
    } as AdmissionResponse);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.picker-chip .chip-sub')?.textContent?.trim()).toBe('CHW2572');
  });

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
  for (const status of ['DISCHARGED', 'TRANSFERRED', 'DECEASED', 'CANCELLED', 'CLOSED_IN_ERROR']) {
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
