import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { EncountersComponent } from './encounters';
import {
  EXPECTED_PICKER_SUBLINES,
  renderPickerSublines,
} from '../testing/patient-subline.fixtures';
import {
  EncounterService,
  EncounterResponse,
  EncounterNoteResponse,
  EncounterNoteAddendumResponse,
  AfterVisitSummary,
} from '../services/encounter.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { StaffService, StaffResponse } from '../services/staff.service';
import { DepartmentLookupService } from '../services/department-lookup.service';
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
  let deptLookupSpy: jasmine.SpyObj<DepartmentLookupService>;
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
    hasAnyActiveRole: (roles: string[]) => boolean;
  };
  let activeRoles: string[];

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
      'signNote',
      'cosignNote',
      'getById',
      'startEncounter',
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
    deptLookupSpy = jasmine.createSpyObj('DepartmentLookupService', ['getActiveDepartments']);
    deptLookupSpy.getActiveDepartments.and.returnValue(of([]));
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
    activeRoles = ['ROLE_DOCTOR'];
    roleCtx = {
      isSuperAdmin: () => false,
      globalView: () => false,
      activeHospitalId: 'h1',
      permittedHospitalIds: ['h1'],
      effectiveHospitalIdForRequest: () => 'h1',
      hasAnyActiveRole: (roles: string[]) => roles.some((r) => activeRoles.includes(r)),
    };

    await TestBed.configureTestingModule({
      imports: [EncountersComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: EncounterService, useValue: encounterSpy },
        { provide: HospitalService, useValue: hospitalSpy },
        { provide: StaffService, useValue: staffSpy },
        { provide: DepartmentLookupService, useValue: deptLookupSpy },
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

  // QA D912 (#967 review): the row carries no MRN, but its contact details do;
  // the reopened chip passed an empty email and showed nothing under the name.
  it('falls back to the row phone on a reopened encounter chip (QA D912)', () => {
    const fixture = TestBed.createComponent(EncountersComponent);
    fixture.detectChanges();
    fixture.componentInstance.openEdit(
      mockEncounter({
        patientName: 'Fatimata Zongo',
        patientEmail: '',
        patientPhoneNumber: '70001003',
      }),
    );
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.picker-chip .chip-sub')?.textContent?.trim()).toBe('70001003');
  });

  // QA D903/D912: the chip showed the email, or an empty line for a
  // phone-only patient, instead of this hospital's MRN.
  it('names the patient by MRN in the chip and the options (QA D912)', () => {
    const fixture = TestBed.createComponent(EncountersComponent);
    fixture.detectChanges();
    fixture.componentInstance.openCreate();
    fixture.detectChanges();
    expect(renderPickerSublines(fixture, fixture.componentInstance)).toEqual(
      EXPECTED_PICKER_SUBLINES,
    );
  });

  it("opens a super-admin's new visit, and its patient search, at the chip's hospital (QA D912)", fakeAsync(() => {
    roleCtx.isSuperAdmin = () => true;
    roleCtx.effectiveHospitalIdForRequest = () => 'hB';
    // A super-admin's hospital is not locked; the search starts with the page.
    component.initPatientSearch();
    deptLookupSpy.getActiveDepartments.calls.reset();
    component.openCreate();
    expect(component.form.hospitalId).toBe('hB');
    // The prefill fires no (change), so the form loads hB's departments itself.
    expect(deptLookupSpy.getActiveDepartments).toHaveBeenCalledWith('hB');

    const patients = TestBed.inject(PatientService) as jasmine.SpyObj<PatientService>;
    patients.list.calls.reset();
    component.onPatientQueryChange('kab');
    tick(250);
    expect(patients.list).toHaveBeenCalledWith('hB', 'kab');
  }));

  describe('super-admin scoped to hospital B (QA D912, D918)', () => {
    let staff: jasmine.SpyObj<StaffService>;

    beforeEach(() => {
      roleCtx.isSuperAdmin = () => true;
      roleCtx.effectiveHospitalIdForRequest = () => 'hB';
      (TestBed.inject(AuthService) as jasmine.SpyObj<AuthService>).hasAnyRole.and.returnValue(true);
      hospitalSpy.list.and.returnValue(
        of([
          { id: 'h1', name: 'Hôpital A' },
          { id: 'hB', name: 'Clinique B' },
        ] as HospitalResponse[]),
      );
      staff = TestBed.inject(StaffService) as jasmine.SpyObj<StaffService>;
      staff.list.calls.reset();
    });

    function openNewVisit() {
      const fixture = TestBed.createComponent(EncountersComponent);
      fixture.detectChanges();
      fixture.componentInstance.openCreate();
      fixture.detectChanges();
      return fixture;
    }

    it('shows hospital B as selected in the new-visit form', () => {
      const fixture = openNewVisit();
      const select = (fixture.nativeElement as HTMLElement).querySelector(
        '#enc-hospitalId',
      ) as HTMLSelectElement;
      expect(select.value).toBe('hB');
      expect(select.selectedOptions[0]?.textContent?.trim()).toBe('Clinique B');
    });

    it("offers hospital B's clinicians, and another hospital's after a change", () => {
      const fixture = openNewVisit();
      expect(staff.list).toHaveBeenCalledWith('hB');
      expect(staff.list).not.toHaveBeenCalledWith('h1');

      fixture.componentInstance.form.staffId = 'st-b';
      fixture.componentInstance.onHospitalChange('h1');
      expect(staff.list).toHaveBeenCalledWith('h1');
      expect(fixture.componentInstance.form.staffId).toBe('');
    });

    it("shows the server's reason when the visit is refused", () => {
      const fixture = openNewVisit();
      encounterSpy.create.and.returnValue(
        throwError(() => ({
          status: 400,
          error: {
            message:
              'El miembro del personal seleccionado no pertenece al hospital de la consulta.',
          },
        })),
      );
      fixture.componentInstance.submitForm();
      expect(toastSpy.error).toHaveBeenCalledWith(
        'El miembro del personal seleccionado no pertenece al hospital de la consulta.',
      );
    });
  });

  it('QA D427: a pharmacist reading encounters is offered no New, Edit or Delete', () => {
    activeRoles = ['ROLE_PHARMACIST'];
    const fixture = TestBed.createComponent(EncountersComponent);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('.page-actions .btn-primary')).toBeNull();
    const cmp = fixture.componentInstance;
    expect(cmp['canEditEncounter']()).toBeFalse();
    expect(cmp['canDeleteEncounter']()).toBeFalse();
    activeRoles = ['ROLE_NURSE'];
    const nurseView = TestBed.createComponent(EncountersComponent).componentInstance;
    expect(nurseView['canCreateEncounter']()).toBeTrue();
    expect(nurseView['canEditEncounter']()).toBeTrue();
  });

  it('opens the encounter named by an /encounters/:id deep link (D96)', () => {
    const route = TestBed.inject(ActivatedRoute) as unknown as {
      snapshot: { paramMap?: ReturnType<typeof convertToParamMap> };
    };
    route.snapshot.paramMap = convertToParamMap({ id: 'e9' });
    const enc = { id: 'e9', status: 'WAITING_FOR_PHYSICIAN' } as EncounterResponse;
    encounterSpy.getById.and.returnValue(of(enc));
    encounterSpy.getNoteHistory.and.returnValue(of([]));
    component.ngOnInit();
    expect(encounterSpy.getById).toHaveBeenCalledWith('e9');
    expect(component.selectedEncounter()?.id).toBe('e9');
  });

  it('lets a doctor start a waiting encounter (D96)', () => {
    const auth = TestBed.inject(AuthService) as jasmine.SpyObj<AuthService>;
    auth.hasAnyRole.and.returnValue(true);
    const waiting = { id: 'e9', status: 'WAITING_FOR_PHYSICIAN' } as EncounterResponse;
    expect(component.canStartEncounter(waiting)).toBeTrue();
    encounterSpy.startEncounter.and.returnValue(
      of({ ...waiting, status: 'IN_PROGRESS' } as EncounterResponse),
    );
    component.startEncounter(waiting);
    expect(component.selectedEncounter()?.status).toBe('IN_PROGRESS');
    expect(
      component.canCheckOut({ ...waiting, status: 'READY_FOR_DISCHARGE' } as EncounterResponse),
    ).toBeTrue();
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

  it('loads departments from the departments endpoint, not from the staff list', () => {
    // THE REGRESSION, and the spec that used to guard it read "derives unique
    // departments for a hospital from the staff list" — it asserted the
    // broken behaviour as correct. Deriving from staff means a department
    // with nobody assigned to it is invisible, so a real active department
    // could be edited in the admin screens and still be unselectable here.
    // The nurse then had nothing to pick, left the field empty, and the API
    // rejected the encounter with departmentId: must not be null.
    deptLookupSpy.getActiveDepartments.and.returnValue(
      of([
        { id: 'd9', name: 'General Practices' },
        { id: 'd1', name: 'ER' },
      ]),
    );
    component.ngOnInit();
    component.loadDepartmentsFor('h1');

    expect(deptLookupSpy.getActiveDepartments).toHaveBeenCalledWith('h1');
    // d9 has no staff assigned; the old staff-derived list could never
    // produce it.
    expect(component.departments().map((d) => d.id)).toEqual(['d9', 'd1']);
  });

  it('does not call the endpoint until a hospital is chosen', () => {
    component.ngOnInit();
    deptLookupSpy.getActiveDepartments.calls.reset();
    component.loadDepartmentsFor('');
    expect(deptLookupSpy.getActiveDepartments).not.toHaveBeenCalled();
    expect(component.departments()).toEqual([]);
    expect(component.departmentsFailed()).toBeFalse();
  });

  it('distinguishes a failed lookup from a hospital with no departments', () => {
    // An empty dropdown and a failed request look identical to the user but
    // mean opposite things — one says "there are none", the other says "we
    // could not ask". Only one of those is the user's problem to solve.
    deptLookupSpy.getActiveDepartments.and.returnValue(throwError(() => new Error('500')));
    component.ngOnInit();
    component.loadDepartmentsFor('h1');

    expect(component.departmentsFailed()).toBeTrue();
    expect(component.departments()).toEqual([]);

    deptLookupSpy.getActiveDepartments.and.returnValue(of([]));
    component.loadDepartmentsFor('h1');
    expect(component.departmentsFailed()).toBeFalse();
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

  it('sends the chosen department, never an empty string', () => {
    // The payload used to carry departmentId: "" because the picker's empty
    // option was selectable and nothing stopped submission. Jackson read that
    // as null and the DTO's @NotNull rejected the whole request with a field
    // error the form never surfaced — the user just saw a failure.
    deptLookupSpy.getActiveDepartments.and.returnValue(
      of([{ id: 'd9', name: 'General Practices' }]),
    );
    component.ngOnInit();
    encounterSpy.create.and.returnValue(of(mockEncounter()));
    component.openCreate();
    component.form.encounterDate = '2026-08-18';
    component.form.departmentId = 'd9';

    component.submitForm();

    const payload = encounterSpy.create.calls.mostRecent().args[0];
    expect(payload.departmentId).toBe('d9');
    expect(payload.departmentId).not.toBe('');
  });

  // #948 review: the list reads encounterDate through serverTime (UTC), so
  // the picked day must be sent as the reader's local midnight on the UTC
  // clock. Sent as '2026-10-24T00:00:00', a 24 October visit created in New
  // York was listed as 'Oct 23, 8:00 PM'. Expectations are built from local
  // Date constructors, so the specs hold in any workstation zone.
  it('submitForm sends local midnight of the picked day on the UTC clock', () => {
    component.ngOnInit();
    encounterSpy.create.and.returnValue(of(mockEncounter()));
    component.openCreate();
    component.form.encounterDate = '2026-10-24';
    component.submitForm();
    const payload = encounterSpy.create.calls.mostRecent().args[0];
    const localMidnight = new Date(2026, 9, 24);
    expect(payload.encounterDate).toBe(localMidnight.toISOString().slice(0, 19));
    expect(new Date(`${payload.encounterDate}Z`).getTime()).toBe(localMidnight.getTime());
    expect(component.showModal()).toBeFalse();
  });

  it('openEdit pre-fills the reader day of the stored time, not its UTC day', () => {
    // 21:00 on 24 October in the reader's zone, as the API stores it (UTC).
    const stored = new Date(2026, 9, 24, 21, 0).toISOString().slice(0, 19);
    component.openEdit(mockEncounter({ encounterDate: stored }));
    expect(component.form.encounterDate).toBe('2026-10-24');
  });

  it('an edit that keeps the day sends the stored time back unchanged', () => {
    encounterSpy.update.and.returnValue(of(mockEncounter()));
    const stored = new Date(2026, 9, 24, 21, 0).toISOString().slice(0, 19);
    component.openEdit(mockEncounter({ encounterDate: stored }));
    component.form.notes = 'edited';
    component.submitForm();
    expect(encounterSpy.update.calls.mostRecent().args[1].encounterDate).toBe(stored);
  });

  it('an edit that changes the day sends local midnight of the new day', () => {
    encounterSpy.update.and.returnValue(of(mockEncounter()));
    const stored = new Date(2026, 9, 24, 21, 0).toISOString().slice(0, 19);
    component.openEdit(mockEncounter({ encounterDate: stored }));
    component.form.encounterDate = '2026-10-26';
    component.submitForm();
    expect(encounterSpy.update.calls.mostRecent().args[1].encounterDate).toBe(
      new Date(2026, 9, 26).toISOString().slice(0, 19),
    );
  });

  it('openCreate pre-fills and locks the hospital, and loads its departments', () => {
    deptLookupSpy.getActiveDepartments.and.returnValue(
      of([{ id: 'd9', name: 'General Practices' }]),
    );
    component.ngOnInit();
    deptLookupSpy.getActiveDepartments.calls.reset();

    component.openCreate();

    expect(component.form.hospitalId).toBe('h1');
    // Asserting the LOOKUP happened for the locked hospital, not a count that
    // happened to fall out of the staff list.
    expect(deptLookupSpy.getActiveDepartments).toHaveBeenCalledWith('h1');
    expect(component.departments().map((d) => d.id)).toEqual(['d9']);
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

  /* ── QA D831: one note entry per author on a stay ── */

  const draftA = {
    id: 'note-a',
    authorName: 'Abdoulaye Ouédraogo',
    documentedAt: '2026-10-25T08:00:00',
    signedAt: null,
  } as unknown as EncounterNoteResponse;
  const entryB = {
    id: 'note-b',
    authorName: 'Issouf Compaoré',
    documentedAt: '2026-10-26T08:00:00',
    signedAt: null,
  } as unknown as EncounterNoteResponse;

  it("QA D831: the first doctor's draft is listed as its own entry beside the second doctor's", () => {
    component.selectEncounter(mockEncounter({ note: entryB, noteEntries: [draftA, entryB] }));

    expect(component.noteState()?.id).toBe('note-b');
    expect(component.earlierNoteEntries().map((e) => e.id)).toEqual(['note-a']);
  });

  it('QA D831: an earlier entry is signed by its id, and only that entry changes', () => {
    encounterSpy.signNote.and.returnValue(
      of({ ...draftA, signedAt: '2026-10-26T09:00:00' } as EncounterNoteResponse),
    );
    component.selectEncounter(mockEncounter({ note: entryB, noteEntries: [draftA, entryB] }));

    component.signNoteEntry(draftA);

    expect(encounterSpy.signNote).toHaveBeenCalledWith('e1', 'note-a');
    expect(component.earlierNoteEntries()[0].signedAt).toBe('2026-10-26T09:00:00');
    expect(component.noteState()?.id).toBe('note-b');
    expect(component.noteState()?.signedAt).toBeNull();
  });

  for (const [label, admissionId, offered] of [
    ['a stay', 'adm-1', true],
    ['a visit', null, false],
  ] as const) {
    it(`QA D831: after the current entry is signed, ${label} ${offered ? 'still offers' : 'no longer offers'} a new note`, () => {
      const fixture = TestBed.createComponent(EncountersComponent);
      const page = fixture.componentInstance;
      page.selectEncounter(
        mockEncounter({
          admissionId,
          note: { ...draftA, signedAt: '2026-10-25T09:00:00' } as EncounterNoteResponse,
        }),
      );
      fixture.detectChanges();

      const addNote = (fixture.nativeElement as HTMLElement).querySelector(
        'button[title="ENCOUNTERS.ADD_NOTE"]',
      );
      expect(!!addNote).toBe(offered);
    });
  }

  it("#965 review (k5.js replay): Sign under DOCTOR2_A's J1 names J1, never DOCTOR_A's J0", () => {
    encounterSpy.signNote.and.returnValue(
      throwError(() => ({ error: { message: "Only the note's author can sign it." } })),
    );
    component.selectEncounter(mockEncounter({ note: entryB, noteEntries: [draftA, entryB] }));

    component.signNote();

    expect(encounterSpy.signNote).toHaveBeenCalledWith('e1', 'note-b');
    expect(component.noteState()?.id).toBe('note-b');
    expect(toastSpy.error).toHaveBeenCalledWith("Only the note's author can sign it.");
  });

  it('#965 review: an earlier entry shows its text, and one awaiting co-signature is co-signed by its id', () => {
    const signedA = {
      ...draftA,
      subjective: 'QA R13 J0 toux fébrile',
      assessment: 'Pneumopathie',
      signedAt: '2026-10-25T09:00:00',
      requiresCosign: true,
      cosignedAt: null,
    } as unknown as EncounterNoteResponse;
    encounterSpy.cosignNote.and.returnValue(
      of({ ...signedA, cosignedAt: '2026-10-26T10:00:00' } as EncounterNoteResponse),
    );
    const fixture = TestBed.createComponent(EncountersComponent);
    const page = fixture.componentInstance;
    page.selectEncounter(mockEncounter({ note: entryB, noteEntries: [signedA, entryB] }));
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    const sections = Array.from(host.querySelectorAll('[data-testid="note-entry-section"]')).map(
      (el) => el.textContent ?? '',
    );
    expect(sections.some((t) => t.includes('QA R13 J0 toux fébrile'))).toBeTrue();
    expect(sections.some((t) => t.includes('Pneumopathie'))).toBeTrue();
    const cosign = host.querySelector<HTMLButtonElement>('[data-testid="cosign-note-entry"]');
    expect(cosign).not.toBeNull();

    cosign!.click();

    expect(encounterSpy.cosignNote).toHaveBeenCalledWith('e1', 'note-a');
    expect(page.earlierNoteEntries()[0].cosignedAt).toBe('2026-10-26T10:00:00');
    expect(page.noteState()?.id).toBe('note-b');
  });

  it("#965 review (k5.js replay): an addendum opened on DOCTOR_A's signed J0 is filed under J0, not J1", () => {
    const signedA = { ...draftA, signedAt: '2026-10-25T09:00:00' } as EncounterNoteResponse;
    encounterSpy.addAddendum.and.returnValue(of({ id: 'a1' } as EncounterNoteAddendumResponse));
    const fixture = TestBed.createComponent(EncountersComponent);
    const page = fixture.componentInstance;
    page.selectEncounter(mockEncounter({ note: entryB, noteEntries: [signedA, entryB] }));
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;

    host.querySelector<HTMLButtonElement>('[data-testid="addendum-note-entry"]')!.click();
    fixture.detectChanges();

    // The form opens under J0, the entry it corrects.
    expect(host.querySelector('[data-testid="note-entry-addendum-form"]')).not.toBeNull();
    page.addendumContent = 'Correction J0 : T 38.4, non 39.4';
    page.addAddendum();

    const [id, payload, noteId] = encounterSpy.addAddendum.calls.mostRecent().args;
    expect(id).toBe('e1');
    expect(payload.content).toBe('Correction J0 : T 38.4, non 39.4');
    expect(noteId).toBe('note-a');
    expect(page.showAddendumForm()).toBeFalse();
    expect(page.addendumTargetId()).toBeNull();
  });

  it('QA D831: the addendum button under the current entry names the current entry', () => {
    encounterSpy.addAddendum.and.returnValue(of({ id: 'a1' } as EncounterNoteAddendumResponse));
    const fixture = TestBed.createComponent(EncountersComponent);
    const page = fixture.componentInstance;
    page.selectEncounter(mockEncounter({ note: entryB, noteEntries: [draftA, entryB] }));
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;

    host.querySelector<HTMLButtonElement>('[data-testid="addendum-current-entry"]')!.click();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="note-entry-addendum-form"]')).toBeNull();
    expect(host.querySelector('[data-testid="addendum-content"]')).not.toBeNull();
    page.addendumContent = 'Ajout';
    page.addAddendum();

    expect(encounterSpy.addAddendum.calls.mostRecent().args[2]).toBe('note-b');
  });

  it('QA D831: a save that starts a new stay entry keeps the previous one listed', () => {
    encounterSpy.addNote.and.returnValue(of(entryB));
    component.selectEncounter(mockEncounter({ note: draftA }));

    component.saveStructuredNote({ subjective: 'QA R13 J1 apyrétique' } as never);

    expect(component.noteState()?.id).toBe('note-b');
    expect(component.earlierNoteEntries().map((e) => e.id)).toEqual(['note-a']);
  });

  it('status transition guards follow the encounter lifecycle', () => {
    expect(component.canCompleteExamination(mockEncounter({ status: 'IN_PROGRESS' }))).toBeTrue();
    expect(component.canCompleteExamination(mockEncounter({ status: 'SCHEDULED' }))).toBeFalse();
    expect(
      component.canMarkReadyForDischarge(mockEncounter({ status: 'AWAITING_RESULTS' })),
    ).toBeTrue();
    expect(component.canMarkReadyForDischarge(mockEncounter({ status: 'COMPLETED' }))).toBeFalse();
  });

  it('QA D788: ready for discharge on a visit IN_PROGRESS is offered to the clinician only', () => {
    const auth = TestBed.inject(AuthService) as jasmine.SpyObj<AuthService>;
    auth.hasAnyRole.and.callFake((roles: string[]) => roles.includes('ROLE_RECEPTIONIST'));
    expect(
      component.canMarkReadyForDischarge(mockEncounter({ status: 'IN_PROGRESS' })),
    ).toBeFalse();
    expect(
      component.canMarkReadyForDischarge(mockEncounter({ status: 'AWAITING_RESULTS' })),
    ).toBeTrue();

    auth.hasAnyRole.and.callFake((roles: string[]) => roles.includes('ROLE_NURSE'));
    expect(component.canMarkReadyForDischarge(mockEncounter({ status: 'IN_PROGRESS' }))).toBeTrue();
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

  describe('note sign/co-sign ceremony (P3 #20)', () => {
    const note = (overrides: Partial<EncounterNoteResponse> = {}): EncounterNoteResponse =>
      ({
        id: 'n1',
        encounterId: 'e1',
        signedAt: null,
        requiresCosign: false,
        cosignedAt: null,
        ...overrides,
      }) as EncounterNoteResponse;

    it('selecting an encounter seeds the note state from its payload', () => {
      const enc = mockEncounter({ note: note({ requiresCosign: true }) });
      component.selectEncounter(enc);
      expect(component.noteState()?.requiresCosign).toBeTrue();
    });

    it('signNote replaces the note state with the ceremony response', () => {
      component.selectEncounter(mockEncounter({ note: note() }));
      encounterSpy.signNote.and.returnValue(
        of(note({ signedAt: '2026-08-22T10:00:00', signatureValue: 'a'.repeat(64) })),
      );

      component.signNote();

      expect(encounterSpy.signNote).toHaveBeenCalledWith('e1', 'n1');
      expect(component.noteState()?.signedAt).toBeTruthy();
      expect(component.signingNote()).toBeFalse();
    });

    it('sign refusal is surfaced verbatim', () => {
      component.selectEncounter(mockEncounter({ note: note() }));
      encounterSpy.signNote.and.returnValue(
        throwError(() => ({ error: { message: "Only the note's author can sign it." } })),
      );

      component.signNote();

      expect(toastSpy.error).toHaveBeenCalledWith("Only the note's author can sign it.");
      expect(component.signingNote()).toBeFalse();
    });

    it('cosignNote posts the ceremony and stores the co-signed state', () => {
      component.selectEncounter(
        mockEncounter({ note: note({ signedAt: '2026-08-22T09:00:00', requiresCosign: true }) }),
      );
      encounterSpy.cosignNote.and.returnValue(
        of(
          note({
            signedAt: '2026-08-22T09:00:00',
            requiresCosign: true,
            cosignedAt: '2026-08-22T11:00:00',
            cosignedByName: 'Dr Attending',
          }),
        ),
      );

      component.cosignNote();

      expect(encounterSpy.cosignNote).toHaveBeenCalledWith('e1', 'n1');
      expect(component.noteState()?.cosignedAt).toBeTruthy();
    });

    it('saveStructuredNote surfaces the signed-lock refusal verbatim', () => {
      component.selectEncounter(mockEncounter({ note: note() }));
      encounterSpy.addNote.and.returnValue(
        throwError(() => ({
          error: {
            message: 'This note is signed and can no longer be edited. Append an addendum instead.',
          },
        })),
      );

      component.saveStructuredNote({ template: 'SOAP' });

      expect(toastSpy.error).toHaveBeenCalledWith(
        'This note is signed and can no longer be edited. Append an addendum instead.',
      );
    });
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
