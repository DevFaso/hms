import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { EncountersComponent } from './encounters';
import {
  EncounterService,
  EncounterResponse,
  EncounterNoteResponse,
  EncounterNoteAddendumResponse,
  AfterVisitSummary,
} from '../services/encounter.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { StaffService, StaffResponse } from '../services/staff.service';
import { PatientService, PatientResponse } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { HospitalScopeUrlService } from '../core/hospital-scope-url.service';

function mockEncounter(overrides: Partial<EncounterResponse> = {}): EncounterResponse {
  return {
    id: 'e1',
    patientId: 'p1',
    patientName: 'John Doe',
    staffId: 'st1',
    staffName: 'Dr Smith',
    hospitalId: 'h1',
    encounterType: 'OUTPATIENT',
    encounterDate: '2026-08-18T09:00:00',
    status: 'IN_PROGRESS',
    ...overrides,
  } as EncounterResponse;
}

describe('EncountersComponent', () => {
  let component: EncountersComponent;
  let encounterSpy: jasmine.SpyObj<EncounterService>;
  let hospitalSpy: jasmine.SpyObj<HospitalService>;
  let toastSpy: jasmine.SpyObj<ToastService>;
  let roleCtx: {
    isSuperAdmin: () => boolean;
    globalView: () => boolean;
    activeHospitalId: string | null;
    permittedHospitalIds: string[];
    effectiveHospitalIdForRequest: () => string | null;
  };

  beforeEach(async () => {
    encounterSpy = jasmine.createSpyObj('EncounterService', [
      'list',
      'create',
      'update',
      'delete',
      'addNote',
      'getNoteHistory',
      'addAddendum',
      'completeExamination',
      'markReadyForDischarge',
      'getAvs',
    ]);
    encounterSpy.list.and.returnValue(
      of([
        mockEncounter(),
        mockEncounter({ id: 'e2', status: 'COMPLETED' }),
        mockEncounter({ id: 'e3', status: 'SCHEDULED' }),
      ]),
    );
    encounterSpy.getNoteHistory.and.returnValue(of([]));
    hospitalSpy = jasmine.createSpyObj('HospitalService', ['list', 'getMyHospitalAsResponse']);
    hospitalSpy.getMyHospitalAsResponse.and.returnValue(
      of({ id: 'h1', name: 'City Hospital' } as HospitalResponse),
    );
    toastSpy = jasmine.createSpyObj('ToastService', ['success', 'error']);
    const staffSpy = jasmine.createSpyObj('StaffService', ['list']);
    staffSpy.list.and.returnValue(
      of([
        {
          id: 'st1',
          name: 'Dr Smith',
          email: 'smith@x.test',
          hospitalId: 'h1',
          departmentId: 'd1',
          departmentName: 'ER',
        } as StaffResponse,
        {
          id: 'st2',
          name: 'Dr Jones',
          email: 'jones@x.test',
          hospitalId: 'h1',
          departmentId: 'd1',
          departmentName: 'ER',
        } as StaffResponse,
        {
          id: 'st3',
          name: 'Dr Roe',
          email: 'roe@x.test',
          hospitalId: 'h2',
          departmentId: 'd9',
          departmentName: 'ICU',
        } as StaffResponse,
      ]),
    );
    const patientSpy = jasmine.createSpyObj('PatientService', ['list']);
    patientSpy.list.and.returnValue(of([]));
    const authSpy = jasmine.createSpyObj('AuthService', ['hasAnyRole', 'getHospitalId']);
    authSpy.hasAnyRole.and.returnValue(false);
    const scopeUrlSpy = jasmine.createSpyObj('HospitalScopeUrlService', ['applyUrlScopeSync']);
    roleCtx = {
      isSuperAdmin: () => false,
      globalView: () => false,
      activeHospitalId: 'h1',
      permittedHospitalIds: ['h1'],
      effectiveHospitalIdForRequest: () => 'h1',
    };

    await TestBed.configureTestingModule({
      imports: [EncountersComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: EncounterService, useValue: encounterSpy },
        { provide: HospitalService, useValue: hospitalSpy },
        { provide: StaffService, useValue: staffSpy },
        { provide: PatientService, useValue: patientSpy },
        { provide: ToastService, useValue: toastSpy },
        { provide: AuthService, useValue: authSpy },
        { provide: RoleContextService, useValue: roleCtx as unknown as RoleContextService },
        { provide: HospitalScopeUrlService, useValue: scopeUrlSpy },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: new Map() } } },
      ],
    }).compileComponents();

    component = TestBed.createComponent(EncountersComponent).componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('loads encounters scoped to the effective hospital on init', () => {
    component.ngOnInit();
    expect(encounterSpy.list).toHaveBeenCalledWith({ hospitalId: 'h1' });
    expect(component.encounters().length).toBe(3);
    expect(component.loading()).toBeFalse();
  });

  it('locks non-admin staff to their own hospital', () => {
    component.ngOnInit();
    expect(hospitalSpy.getMyHospitalAsResponse).toHaveBeenCalled();
    expect(hospitalSpy.list).not.toHaveBeenCalled();
    expect(component.lockedHospitalId).toBe('h1');
    expect(component.lockedHospitalName).toBe('City Hospital');
  });

  it('derives unique departments for a hospital from the staff list', () => {
    component.ngOnInit();
    component.loadDepartmentsFor('h1');
    expect(component.departments()).toEqual([{ id: 'd1', name: 'ER' }]);
    component.loadDepartmentsFor('');
    expect(component.departments()).toEqual([]);
  });

  it('tab filters split open and completed encounters', () => {
    component.ngOnInit();
    component.setTab('open');
    expect(component.filtered().map((e) => e.id)).toEqual(['e1', 'e3']);
    component.setTab('completed');
    expect(component.filtered().map((e) => e.id)).toEqual(['e2']);
  });

  it('search filter matches patient, staff, and department', () => {
    component.ngOnInit();
    component.searchTerm = 'smith';
    component.applyFilter();
    expect(component.filtered().length).toBe(3);
    component.searchTerm = 'nobody';
    component.applyFilter();
    expect(component.filtered().length).toBe(0);
  });

  it('selectEncounter loads the note history', () => {
    encounterSpy.getNoteHistory.and.returnValue(of([{ id: 'n1' } as never]));
    component.selectEncounter(mockEncounter());
    expect(encounterSpy.getNoteHistory).toHaveBeenCalledWith('e1');
    expect(component.noteHistory().length).toBe(1);
    component.closeDetail();
    expect(component.selectedEncounter()).toBeNull();
    expect(component.noteHistory().length).toBe(0);
  });

  it('submitForm appends a midnight time to the encounter date', () => {
    component.ngOnInit();
    encounterSpy.create.and.returnValue(of(mockEncounter()));
    component.openCreate();
    component.form.encounterDate = '2026-08-18';
    component.submitForm();
    const payload = encounterSpy.create.calls.mostRecent().args[0];
    expect(payload.encounterDate).toBe('2026-08-18T00:00:00');
    expect(component.showModal()).toBeFalse();
  });

  it('openCreate pre-fills and locks the hospital', () => {
    component.ngOnInit();
    component.openCreate();
    expect(component.form.hospitalId).toBe('h1');
    expect(component.departments().length).toBe(1);
  });

  it('executeDelete deletes and reloads', () => {
    component.ngOnInit();
    encounterSpy.delete.and.returnValue(of(void 0));
    encounterSpy.list.calls.reset();
    component.confirmDelete(component.encounters()[0]);
    component.executeDelete();
    expect(encounterSpy.delete).toHaveBeenCalledWith('e1');
    expect(encounterSpy.list).toHaveBeenCalled();
    expect(component.showDeleteConfirm()).toBeFalse();
  });

  it('addAddendum requires content', () => {
    component.selectedEncounter.set(mockEncounter());
    component.addendumContent = '   ';
    component.addAddendum();
    expect(encounterSpy.addAddendum).not.toHaveBeenCalled();
  });

  it('addAddendum records the addendum and refreshes history', () => {
    encounterSpy.addAddendum.and.returnValue(of({ id: 'a1' } as EncounterNoteAddendumResponse));
    component.selectedEncounter.set(mockEncounter());
    component.addendumContent = ' late note ';
    component.addAddendum();
    const [id, payload] = encounterSpy.addAddendum.calls.mostRecent().args;
    expect(id).toBe('e1');
    expect(payload.content).toBe('late note');
    expect(payload.attestAccuracy).toBeTrue();
    expect(component.addendumContent).toBe('');
    expect(encounterSpy.getNoteHistory).toHaveBeenCalledWith('e1');
  });

  it('saveStructuredNote saves against the selected encounter', () => {
    encounterSpy.addNote.and.returnValue(of({ id: 'n2' } as EncounterNoteResponse));
    component.selectedEncounter.set(mockEncounter());
    component.saveStructuredNote({ chiefComplaint: 'Cough' } as never);
    expect(encounterSpy.addNote).toHaveBeenCalledWith('e1', jasmine.anything());
    expect(component.showNoteForm()).toBeFalse();
  });

  it('status transition guards follow the encounter lifecycle', () => {
    expect(component.canCompleteExamination(mockEncounter({ status: 'IN_PROGRESS' }))).toBeTrue();
    expect(component.canCompleteExamination(mockEncounter({ status: 'SCHEDULED' }))).toBeFalse();
    expect(
      component.canMarkReadyForDischarge(mockEncounter({ status: 'AWAITING_RESULTS' })),
    ).toBeTrue();
    expect(component.canMarkReadyForDischarge(mockEncounter({ status: 'COMPLETED' }))).toBeFalse();
  });

  it('completeExamination updates the selected encounter and reloads', () => {
    component.ngOnInit();
    const updated = mockEncounter({ status: 'READY_FOR_DISCHARGE' });
    encounterSpy.completeExamination.and.returnValue(of(updated));
    encounterSpy.list.calls.reset();
    component.completeExamination(mockEncounter());
    expect(component.selectedEncounter()).toBe(updated);
    expect(encounterSpy.list).toHaveBeenCalled();
    expect(component.transitioning()).toBeFalse();
  });

  it('markReadyForDischarge handles errors without leaving the spinner on', () => {
    encounterSpy.markReadyForDischarge.and.returnValue(throwError(() => new Error('fail')));
    component.markReadyForDischarge(mockEncounter());
    expect(toastSpy.error).toHaveBeenCalled();
    expect(component.transitioning()).toBeFalse();
  });

  it('viewAvs shows the summary or a toast when none exists', () => {
    const summary = { encounterId: 'e1' } as AfterVisitSummary;
    encounterSpy.getAvs.and.returnValue(of(summary));
    component.viewAvs(mockEncounter());
    expect(component.avs()).toBe(summary);
    component.closeAvs();
    expect(component.avs()).toBeNull();

    encounterSpy.getAvs.and.returnValue(throwError(() => new Error('404')));
    component.viewAvs(mockEncounter());
    expect(toastSpy.error).toHaveBeenCalled();
    expect(component.avsLoading()).toBeFalse();
  });

  it('maps statuses to classes and types to icons', () => {
    expect(component.getStatusClass('SCHEDULED')).toBe('status-open');
    expect(component.getStatusClass('READY_FOR_DISCHARGE')).toBe('status-ready');
    expect(component.getStatusClass('CANCELLED')).toBe('status-cancelled');
    expect(component.getTypeIcon('EMERGENCY')).toBe('emergency');
    expect(component.getTypeIcon('LAB')).toBe('science');
    expect(component.getTypeIcon('UNKNOWN')).toBe('medical_services');
  });

  it('countByStatus counts loaded encounters', () => {
    component.ngOnInit();
    expect(component.countByStatus('IN_PROGRESS')).toBe(1);
    expect(component.countByStatus('COMPLETED')).toBe(1);
    expect(component.countByStatus('CANCELLED')).toBe(0);
  });

  it('selecting a patient fills the form; clearing resets it', () => {
    const patient = { id: 'p9', firstName: 'Jane', lastName: 'Roe' } as PatientResponse;
    component.selectPatient(patient);
    expect(component.form.patientId).toBe('p9');
    component.clearPatient();
    expect(component.form.patientId).toBe('');
  });
});
