import {
  Component,
  computed,
  inject,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { Subject, of } from 'rxjs';
import { catchError, debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import { refusalReason } from '../shared/refusal-reason';
import {
  EncounterService,
  EncounterRequest,
  EncounterResponse,
  EncounterType,
  EncounterNoteResponse,
  AfterVisitSummary,
  EncounterNoteRequest,
} from '../services/encounter.service';
import { NoteHistoryView, noteEntrySections, toNoteHistoryView } from './note-history-view';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { StaffService, StaffResponse, isClinicalProvider } from '../services/staff.service';
import { PatientService, PatientResponse } from '../services/patient.service';
import { DepartmentLookupService, DepartmentOption } from '../services/department-lookup.service';
import { ToastService } from '../core/toast.service';
import { AuthService } from '../auth/auth.service';
import { NursingNotesPanelComponent } from './nursing-notes-panel/nursing-notes-panel.component';
import { NursingNoteService } from '../services/nursing-note.service';
import { MedicalCertificateDialogComponent } from './medical-certificate-dialog/medical-certificate-dialog.component';
import { RoleContextService } from '../core/role-context.service';
import { HospitalScopeUrlService } from '../core/hospital-scope-url.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { EncounterNoteFormComponent } from './encounter-note-form/encounter-note-form.component';
import { EligibilityCheckDialogComponent } from './eligibility-check-dialog/eligibility-check-dialog.component';
import { HospitalScopeChipComponent } from '../shared/hospital-scope-chip/hospital-scope-chip.component';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { CheckoutDialogComponent } from '../checkout/checkout-dialog/checkout-dialog.component';
import { actionRoles, SERVICE_RULE_ROLES } from '../core/action-permissions';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { serverLocalDate, toServerDateTime } from '../shared/date-utils';
import { PatientSublinePipe } from '../shared/patient-picker/patient-subline';

@Component({
  selector: 'app-encounters',
  standalone: true,
  imports: [
    PatientSublinePipe,
    ServerTimePipe,
    CommonModule,
    FormsModule,
    TranslateModule,
    EncounterNoteFormComponent,
    EligibilityCheckDialogComponent,
    HospitalScopeChipComponent,
    EnumLabelPipe,
    CheckoutDialogComponent,
    NursingNotesPanelComponent,
    MedicalCertificateDialogComponent,
  ],
  templateUrl: './encounters.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './encounters.scss',
})
export class EncountersComponent implements OnInit {
  private readonly encounterService = inject(EncounterService);
  private readonly hospitalService = inject(HospitalService);
  private readonly staffService = inject(StaffService);
  private readonly departmentLookup = inject(DepartmentLookupService);
  private readonly patientService = inject(PatientService);
  private readonly toast = inject(ToastService);
  private readonly auth = inject(AuthService);
  private readonly roleContext = inject(RoleContextService);
  private readonly route = inject(ActivatedRoute);
  private readonly scopeUrl = inject(HospitalScopeUrlService);
  private readonly translate = inject(TranslateService);

  /**
   * Signal exposure for the cross-tenant chip + Hospital column toggle
   * (docs/super-admin-cross-tenant-design.md). Pre-existing template
   * `isSuperAdmin` callers (none in encounters today) would keep working
   * via the property-call syntax `isSuperAdmin()`.
   */
  protected readonly isSuperAdmin = this.roleContext.isSuperAdmin;
  protected readonly globalView = this.roleContext.globalView;

  /**
   * QA D427: the actions the API admits, so a reader (a pharmacist or another
   * consulting clinician) is not offered a button that ends in a 403. POST
   * /encounters admits the clinical roles and reception; PUT the clinical
   * roles; DELETE is the super-admin's.
   */
  protected readonly canCreateEncounter = computed(() =>
    this.roleContext.hasAnyActiveRole(actionRoles('encounterCreate')),
  );
  protected readonly canEditEncounter = computed(() =>
    this.roleContext.hasAnyActiveRole(actionRoles('encounterUpdate')),
  );
  protected readonly canDeleteEncounter = computed(() =>
    this.roleContext.hasAnyActiveRole(actionRoles('encounterDelete')),
  );
  /** Non-null when the user is locked to a single hospital (all non-admin staff) */
  lockedHospitalId: string | null = null;
  /** Human-readable name of the locked hospital */
  lockedHospitalName = '';

  encounters = signal<EncounterResponse[]>([]);
  filtered = signal<EncounterResponse[]>([]);
  loading = signal(true);
  searchTerm = '';
  activeTab = signal<'all' | 'open' | 'completed'>('all');
  selectedEncounter = signal<EncounterResponse | null>(null);
  showNoteForm = signal(false);
  savingNote = signal(false);
  showEligibilityDialog = signal(false);

  /* Note history + addendums */
  noteHistory = signal<NoteHistoryView[]>([]);
  noteHistoryLoading = signal(false);

  /* Note sign/co-sign ceremony (P3 #20). State comes from the encounter's
     note payload and is replaced by each ceremony response. */
  noteState = signal<EncounterNoteResponse | null>(null);
  /**
   * QA D831: an inpatient stay keeps one note entry per progress note and
   * author. A second doctor's entry no longer replaces the first doctor's
   * unsigned draft; the earlier entries are listed here, each signable by
   * its own author.
   */
  noteEntries = signal<EncounterNoteResponse[]>([]);
  readonly earlierNoteEntries = computed(() => {
    const currentId = this.noteState()?.id;
    return this.noteEntries().filter((entry) => entry.id !== currentId);
  });
  /**
   * #965 review: each entry's text is shown above its sign/co-sign button, so
   * a clinician never signs or attests an entry they were not shown.
   */
  readonly earlierNoteEntryViews = computed(() =>
    this.earlierNoteEntries().map((entry) => ({ entry, sections: noteEntrySections(entry) })),
  );
  readonly currentNoteSections = computed(() => noteEntrySections(this.noteState()));
  signingEntryId = signal<string | null>(null);
  cosigningEntryId = signal<string | null>(null);
  signingNote = signal(false);
  cosigningNote = signal(false);
  addendumContent = '';
  showAddendumForm = signal(false);
  addendumSaving = signal(false);
  /**
   * QA D831 (#965 review): the entry the open addendum form corrects. On a
   * stay an earlier entry is signed and locked, so an addendum is its only
   * correction route; it must be filed under that entry, never the latest.
   */
  addendumTargetId = signal<string | null>(null);
  /** The addendum form is open under one of the stay's earlier entries, not the current one. */
  readonly addendumOnEarlierEntry = computed(() => {
    const target = this.addendumTargetId();
    return (
      this.showAddendumForm() &&
      target !== null &&
      this.earlierNoteEntries().some((entry) => entry.id === target)
    );
  });

  /* Status transitions + AVS */
  transitioning = signal(false);
  avs = signal<AfterVisitSummary | null>(null);
  avsLoading = signal(false);

  // Dropdowns
  hospitals = signal<HospitalResponse[]>([]);
  staffMembers = signal<{ id: string; name: string }[]>([]);
  private allStaff: StaffResponse[] = [];
  /**
   * QA D918: the clinicians offered are those of the form's hospital. The list
   * was loaded once for the home hospital, so a super-admin scoped to B was
   * offered A's clinicians and the server refused the visit. A newer hospital
   * cancels the load of an older one.
   */
  private readonly staffHospital$ = new Subject<string>();
  /** The hospital the staff list was last requested for. */
  private staffHospitalId: string | null = null;

  departments = signal<DepartmentOption[]>([]);
  departmentsLoading = signal(false);
  /**
   * Distinguished from an empty list on purpose: "this hospital has no
   * departments" and "we could not ask" look identical in a bare dropdown and
   * mean opposite things.
   */
  departmentsFailed = signal(false);

  // Patient picker
  patientQuery = signal('');
  patientSuggestions = signal<PatientResponse[]>([]);
  patientDropdownOpen = signal(false);
  patientSearchLoading = signal(false);
  selectedPatient = signal<PatientResponse | null>(null);
  private readonly patientSearch$ = new Subject<string>();

  // CRUD
  showModal = signal(false);
  editing = signal(false);
  saving = signal(false);
  editingId = '';
  /**
   * The edited encounter's stored date and time, sent back unchanged when the
   * day is not changed, so saving a note edit does not move the visit to
   * midnight.
   */
  private editingOriginalDate = '';
  form: EncounterRequest = this.emptyForm();
  encounterTypes: EncounterType[] = [
    'OUTPATIENT',
    'INPATIENT',
    'EMERGENCY',
    'CONSULTATION',
    'FOLLOW_UP',
    'SURGERY',
    'LAB',
    'TELEHEALTH',
  ];

  // Delete
  showDeleteConfirm = signal(false);
  deletingEnc = signal<EncounterResponse | null>(null);
  deleting = signal(false);

  emptyForm(): EncounterRequest {
    return {
      patientId: '',
      staffId: '',
      hospitalId: '',
      encounterType: 'OUTPATIENT',
      encounterDate: '',
    };
  }

  ngOnInit(): void {
    // Cross-tenant: hydrate the super-admin scope from `?hospitalId=`
    // BEFORE the first list fetch so the auth interceptor sends the
    // right (or no) X-Hospital-Id header on the initial request.
    // See docs/super-admin-cross-tenant-design.md "URL state".
    this.scopeUrl.applyUrlScopeSync(this.route);

    this.loadEncounters();

    // D96: /encounters/:id (the worklist "start" and deep links) opens that
    // encounter's detail instead of falling through to the wildcard route.
    const deepLinkId = this.route.snapshot?.paramMap?.get('id');
    if (deepLinkId) {
      this.encounterService.getById(deepLinkId).subscribe({
        next: (enc) => this.selectEncounter(enc),
        error: () => this.toast.error(this.translate.instant('ENCOUNTERS.NOT_FOUND')),
      });
    }

    // Determine role-based hospital access. We intentionally consult
    // `auth.hasAnyRole` here (and not the `roleContext.isSuperAdmin()`
    // signal) because this branch existed before the cross-tenant
    // refactor and changing its source would alter the dropdown logic
    // below — kept as-is to avoid scope creep.
    // rbac-inline-ok: a scope question (which hospitals to list), not a write button.
    const isSuperAdminUser = this.auth.hasAnyRole(['ROLE_SUPER_ADMIN']);
    const permitted = this.roleContext.permittedHospitalIds;

    if (isSuperAdminUser) {
      // Super admin: load ALL hospitals for dropdown selection
      this.hospitalService.list().subscribe({ next: (h) => this.hospitals.set(h) });
    } else {
      // All other staff: locked to their current (active) hospital
      const activeId = this.roleContext.activeHospitalId;
      const lockId = activeId || (permitted.length > 0 ? permitted[0] : null);

      if (lockId) {
        this.lockedHospitalId = lockId;
        // Use tenant-safe /me/hospital instead of /hospitals/{id} (SUPER_ADMIN-only)
        this.hospitalService.getMyHospitalAsResponse().subscribe({
          next: (h) => {
            this.hospitals.set([h]);
            this.lockedHospitalName = h.name;
            this.roleContext.activeHospitalId = lockId;
          },
        });
      } else {
        this.toast.error(this.translate.instant('ENCOUNTERS.NO_HOSPITAL'));
      }
    }

    // ── TENANT ISOLATION: scope staff list to the form's hospital ──
    this.staffHospital$
      .pipe(
        distinctUntilChanged(),
        switchMap((hid) =>
          this.staffService.list(hid).pipe(catchError(() => of([] as StaffResponse[]))),
        ),
      )
      .subscribe((list) => {
        this.allStaff = list ?? [];
        this.staffMembers.set(
          this.allStaff
            .filter(isClinicalProvider)
            .map((s) => ({ id: s.id, name: s.name || s.email })),
        );
      });
    this.loadStaffFor(
      this.lockedHospitalId ??
        this.roleContext.effectiveHospitalIdForRequest() ??
        this.roleContext.activeHospitalId,
    );
    this.initPatientSearch();
  }

  // ── Patient picker ──
  initPatientSearch(): void {
    this.patientSearch$
      .pipe(
        debounceTime(220),
        distinctUntilChanged(),
        switchMap((q) => {
          this.patientSearchLoading.set(true);
          // ── TENANT ISOLATION: always scope patient search to the form's hospital ──
          return this.patientService.list(this.patientSearchHospitalId(), q);
        }),
      )
      .subscribe({
        next: (list) => {
          this.patientSuggestions.set(list.slice(0, 8));
          this.patientDropdownOpen.set(list.length > 0);
          this.patientSearchLoading.set(false);
        },
        error: () => this.patientSearchLoading.set(false),
      });
  }

  onPatientQueryChange(q: string): void {
    this.patientQuery.set(q);
    if (q.length >= 2) this.patientSearch$.next(q);
    else {
      this.patientSuggestions.set([]);
      this.patientDropdownOpen.set(false);
    }
  }

  selectPatient(p: PatientResponse): void {
    this.selectedPatient.set(p);
    this.form.patientId = p.id;
    this.patientDropdownOpen.set(false);
    this.patientQuery.set('');
  }

  clearPatient(): void {
    this.selectedPatient.set(null);
    this.form.patientId = '';
    this.patientQuery.set('');
  }

  /**
   * QA D912 (#967 review): the hospital whose patients, and whose MRN, the
   * typeahead offers: the form's hospital, else the request scope, which for
   * a super-admin is the hospital the scope chip picked, not their home one.
   */
  private patientSearchHospitalId(): string | undefined {
    return this.form.hospitalId || this.roleContext.effectiveHospitalIdForRequest() || undefined;
  }

  patientInitials(p: PatientResponse): string {
    return ((p.firstName?.[0] ?? '') + (p.lastName?.[0] ?? '')).toUpperCase() || '?';
  }

  // ── Department derivation ──
  onHospitalChange(hospitalId: string): void {
    this.form.hospitalId = hospitalId;
    this.form.departmentId = undefined;
    // QA D918: a clinician of the previous hospital is refused at this one.
    this.form.staffId = '';
    this.loadDepartmentsFor(hospitalId);
    this.loadStaffFor(hospitalId);
  }

  /** QA D918: (re)load the clinicians of `hospitalId`; a no-op when unchanged. */
  loadStaffFor(hospitalId: string | null | undefined): void {
    if (!hospitalId || hospitalId === this.staffHospitalId) return;
    this.staffHospitalId = hospitalId;
    // The previous hospital's clinicians must not be pickable while this loads.
    this.staffMembers.set([]);
    this.staffHospital$.next(hospitalId);
  }

  /**
   * Load the departments a user may pick from.
   *
   * <p>This used to build the list from `allStaff` — collecting the distinct
   * `departmentId` values off staff members — so a department appeared only
   * if somebody was assigned to it. A real, active department with no staff
   * yet was invisible, which is why "General Practices" could be edited in
   * the admin screens and still be unselectable here. The nurse then had no
   * option to choose, left the field on its empty value, and got a 400 from
   * `departmentId: must not be null`.
   *
   * <p>`/departments/active-minimal` is purpose-built for this and already
   * permits NURSE, so nothing forced the workaround.
   */
  loadDepartmentsFor(hospitalId: string): void {
    if (!hospitalId) {
      this.departments.set([]);
      this.departmentsFailed.set(false);
      return;
    }
    this.departmentsLoading.set(true);
    this.departmentsFailed.set(false);
    this.departmentLookup.getActiveDepartments(hospitalId).subscribe({
      next: (depts) => {
        this.departments.set(depts);
        this.departmentsLoading.set(false);
      },
      error: () => {
        this.departments.set([]);
        this.departmentsFailed.set(true);
        this.departmentsLoading.set(false);
      },
    });
  }

  // ── CRUD ──
  openCreate(): void {
    this.form = this.emptyForm();
    // ── TENANT ISOLATION: pre-fill hospitalId from the request scope ──
    // QA D912 (#967 review): for a super-admin that is the scope chip's
    // hospital, not their home one, so the visit and the patient search (and
    // the MRN on the chip) start at the hospital they are working in.
    this.form.hospitalId = this.roleContext.effectiveHospitalIdForRequest() ?? '';
    this.editing.set(false);
    this.editingId = '';
    this.editingOriginalDate = '';
    this.selectedPatient.set(null);
    this.patientQuery.set('');
    this.departments.set([]);

    // Auto-lock hospital for non-super-admin staff
    if (this.lockedHospitalId) {
      this.form.hospitalId = this.lockedHospitalId;
      this.loadDepartmentsFor(this.lockedHospitalId);
    } else if (this.form.hospitalId) {
      // A prefilled hospital never fires the select's (change): load its
      // departments here, or the required list is empty (#967 review, round 2),
      // and its clinicians (QA D918).
      this.loadDepartmentsFor(this.form.hospitalId);
      this.loadStaffFor(this.form.hospitalId);
    }

    this.showModal.set(true);
  }

  openEdit(enc: EncounterResponse): void {
    this.editing.set(true);
    this.editingId = enc.id;
    this.editingOriginalDate = enc.encounterDate ?? '';
    this.form = {
      patientId: enc.patientId,
      staffId: enc.staffId,
      hospitalId: enc.hospitalId,
      departmentId: enc.departmentId || undefined,
      encounterType: enc.encounterType,
      // #948 review: the reader's day, not the UTC day of the stored time.
      encounterDate: serverLocalDate(enc.encounterDate),
      notes: enc.notes || undefined,
    };
    // Pre-fill patient picker display with existing data
    this.selectedPatient.set({
      id: enc.patientId,
      firstName: enc.patientName?.split(' ')[0] ?? '',
      lastName: enc.patientName?.split(' ').slice(1).join(' ') ?? '',
      // QA D912: the row has no MRN; its contact details keep the chip's fallback.
      email: enc.patientEmail,
      phoneNumberPrimary: enc.patientPhoneNumber,
    } as PatientResponse);
    this.loadDepartmentsFor(enc.hospitalId ?? '');
    this.loadStaffFor(enc.hospitalId);
    this.showModal.set(true);
  }

  closeModal(): void {
    this.showModal.set(false);
  }

  /**
   * #948 review: the date picker gives the reader's calendar day, and the API
   * stores encounter times on its UTC clock (the list reads them back through
   * `serverTime`). Sent as `<day>T00:00:00`, a 24 October visit created in
   * New York was stored as UTC midnight and listed as 23 October, 8:00 PM.
   * Local midnight of the day is converted to UTC; an edit that keeps the day
   * sends the stored time back as it was.
   */
  private encounterDatePayload(): string {
    const day = this.form.encounterDate;
    if (!day) {
      return '';
    }
    if (
      this.editing() &&
      this.editingOriginalDate &&
      serverLocalDate(this.editingOriginalDate) === day
    ) {
      return this.editingOriginalDate;
    }
    return toServerDateTime(`${day}T00:00`);
  }

  submitForm(): void {
    this.saving.set(true);
    const payload = {
      ...this.form,
      encounterDate: this.encounterDatePayload(),
    };
    const op = this.editing()
      ? this.encounterService.update(this.editingId, payload)
      : this.encounterService.create(payload);
    op.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(
            this.editing() ? 'ENCOUNTERS.UPDATE_SUCCESS' : 'ENCOUNTERS.CREATE_SUCCESS',
          ),
        );
        this.closeModal();
        this.loadEncounters();
        this.saving.set(false);
      },
      error: (err) => {
        // QA D918: the server says why (e.g. a clinician of another hospital),
        // in the reader's language; the generic text is for anything else.
        this.toast.error(refusalReason(err) ?? this.translate.instant('ENCOUNTERS.SAVE_FAILED'));
        this.saving.set(false);
      },
    });
  }

  confirmDelete(enc: EncounterResponse): void {
    this.deletingEnc.set(enc);
    this.showDeleteConfirm.set(true);
  }
  cancelDelete(): void {
    this.showDeleteConfirm.set(false);
    this.deletingEnc.set(null);
  }
  executeDelete(): void {
    const enc = this.deletingEnc();
    if (!enc) return;
    this.deleting.set(true);
    this.encounterService.delete(enc.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('ENCOUNTERS.DELETED'));
        this.cancelDelete();
        this.loadEncounters();
        this.deleting.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('ENCOUNTERS.DELETE_FAILED'));
        this.deleting.set(false);
      },
    });
  }

  /**
   * Fired by the cross-tenant scope chip after the super-admin picks a
   * hospital (or "All hospitals"). The chip handles the URL round-trip
   * itself; we just re-fetch under the new scope.
   */
  onScopeChange(_hospitalId: string | null): void {
    this.loadEncounters();
  }

  loadEncounters(): void {
    this.loading.set(true);
    // ── Tenant isolation ──────────────────────────────────────────
    // Use the cross-tenant aware hospital id: null for super-admin in
    // global view, the selected hospital for scoped super-admin, the
    // user's active hospital for everyone else. The matching backend
    // branch in EncounterServiceImpl falls through to an unscoped
    // findAll when null. See docs/super-admin-cross-tenant-design.md.
    const hospitalId = this.roleContext.effectiveHospitalIdForRequest() ?? undefined;
    this.encounterService.list(hospitalId ? { hospitalId } : undefined).subscribe({
      next: (data) => {
        const list = Array.isArray(data) ? data : [];
        this.encounters.set(list);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('ENCOUNTERS.LOAD_FAILED'));
        this.loading.set(false);
      },
    });
  }

  setTab(tab: 'all' | 'open' | 'completed'): void {
    this.activeTab.set(tab);
    this.applyFilter();
  }

  applyFilter(): void {
    let list = this.encounters();
    const tab = this.activeTab();
    if (tab === 'open') {
      list = list.filter(
        (e) =>
          e.status === 'SCHEDULED' ||
          e.status === 'ARRIVED' ||
          e.status === 'TRIAGE' ||
          e.status === 'WAITING_FOR_PHYSICIAN' ||
          e.status === 'IN_PROGRESS' ||
          e.status === 'AWAITING_RESULTS' ||
          e.status === 'READY_FOR_DISCHARGE',
      );
    } else if (tab === 'completed') {
      list = list.filter((e) => e.status === 'COMPLETED');
    }
    const term = this.searchTerm.toLowerCase().trim();
    if (term) {
      list = list.filter(
        (e) =>
          (e.patientName ?? '').toLowerCase().includes(term) ||
          (e.staffName ?? '').toLowerCase().includes(term) ||
          (e.departmentName ?? '').toLowerCase().includes(term) ||
          (e.notes ?? '').toLowerCase().includes(term),
      );
    }
    this.filtered.set(list);
  }

  selectEncounter(enc: EncounterResponse): void {
    this.selectedEncounter.set(enc);
    this.showNoteForm.set(false);
    this.showAddendumForm.set(false);
    this.addendumTargetId.set(null);
    this.addendumContent = '';
    this.noteState.set(enc.note ?? null);
    this.noteEntries.set(enc.noteEntries ?? []);
    this.loadNoteHistory(enc.id);
  }

  closeDetail(): void {
    this.selectedEncounter.set(null);
    this.noteHistory.set([]);
    this.showEligibilityDialog.set(false);
  }

  /** Per-section EncounterNote save (item 5). */
  saveStructuredNote(payload: EncounterNoteRequest): void {
    const enc = this.selectedEncounter();
    if (!enc) return;
    this.savingNote.set(true);
    this.encounterService.addNote(enc.id, payload).subscribe({
      next: (note) => {
        this.toast.success(this.translate.instant('ENCOUNTERS.NOTE_SAVED'));
        this.savingNote.set(false);
        this.showNoteForm.set(false);
        this.keepEarlierEntry(this.noteState(), note ?? null);
        this.noteState.set(note ?? null);
        this.loadNoteHistory(enc.id);
      },
      error: (err) => {
        // Surface the backend refusal verbatim (house rule) — a signed-note
        // lock or client-asserted-signature refusal must reach the clinician.
        this.toast.error(
          err?.error?.message ?? this.translate.instant('ENCOUNTERS.NOTE_SAVE_FAILED'),
        );
        this.savingNote.set(false);
      },
    });
  }

  /* ── Note sign/co-sign ceremony (P3 #20) ── */

  /** QA D961: the certificate dialog. */
  readonly showCertificateDialog = signal(false);

  /**
   * QA D961: a doctor issues a certificate from a visit the patient was seen
   * in (the server refuses a visit closed unseen, cancelled or not started).
   */
  canIssueCertificate(enc: EncounterResponse): boolean {
    const unseen = [
      'SCHEDULED',
      'ARRIVED',
      'TRIAGE',
      'WAITING_FOR_PHYSICIAN',
      'CANCELLED',
      'LEFT_WITHOUT_BEING_SEEN',
    ];
    return (
      this.auth.hasAnyRole(actionRoles('encounterMedicalCertificate')) &&
      !!enc.status &&
      !unseen.includes(enc.status)
    );
  }

  /** QA D927: only the roles `GET /nurse/notes` admits load the panel (a refused read would leave the page). */
  get canReadNursingNotes(): boolean {
    return this.auth.hasAnyRole(NursingNoteService.READER_ROLES);
  }

  /**
   * QA D995: only the note's author signs it and only another clinician
   * co-signs it (the server refuses the rest), so the buttons are offered
   * accordingly instead of leading to a refusal. A note with no recorded
   * author keeps both, and the server decides.
   */
  isNoteAuthor(note: EncounterNoteResponse | null): boolean {
    const me = this.auth.getUserId();
    return !note?.authorUserId || (!!me && note.authorUserId === me);
  }

  isOtherClinicianNote(note: EncounterNoteResponse | null): boolean {
    const me = this.auth.getUserId();
    return !note?.authorUserId || !me || note.authorUserId !== me;
  }

  /** Matching the prescriptions pattern, the backend arbitrates; whoever
   *  is offered the button (see isNoteAuthor) has it checked again there
   *  (author-only for sign, non-author DOCTOR at the hospital for co-sign)
   *  and its refusal is surfaced verbatim. */
  signNote(): void {
    const enc = this.selectedEncounter();
    const current = this.noteState();
    if (!enc || !current?.id || this.signingNote()) return;
    this.signingNote.set(true);
    // #965 review: the entry on screen, by id — without it the server picked
    // the caller's own unsigned entry, a text that was not displayed.
    this.encounterService.signNote(enc.id, current.id).subscribe({
      next: (note) => {
        this.signingNote.set(false);
        this.toast.success(this.translate.instant('ENCOUNTERS.NOTE_SIGN_SUCCESS'));
        this.replaceEntry(note ?? null);
        this.noteState.set(note ?? null);
      },
      error: (err) => {
        this.signingNote.set(false);
        this.toast.error(
          err?.error?.message ?? this.translate.instant('ENCOUNTERS.NOTE_SIGN_FAILED'),
        );
      },
    });
  }

  /** QA D831: signs one earlier entry of the stay, by its id (the server checks the author). */
  signNoteEntry(entry: EncounterNoteResponse): void {
    const enc = this.selectedEncounter();
    if (!enc || this.signingEntryId()) return;
    this.signingEntryId.set(entry.id);
    this.encounterService.signNote(enc.id, entry.id).subscribe({
      next: (signed) => {
        this.signingEntryId.set(null);
        this.toast.success(this.translate.instant('ENCOUNTERS.NOTE_SIGN_SUCCESS'));
        this.replaceEntry(signed ?? null);
        this.loadNoteHistory(enc.id);
      },
      error: (err) => {
        this.signingEntryId.set(null);
        this.toast.error(
          err?.error?.message ?? this.translate.instant('ENCOUNTERS.NOTE_SIGN_FAILED'),
        );
      },
    });
  }

  /** #965 review: co-signs one earlier entry of the stay, by its id (the server checks who may). */
  cosignNoteEntry(entry: EncounterNoteResponse): void {
    const enc = this.selectedEncounter();
    if (!enc || this.cosigningEntryId()) return;
    this.cosigningEntryId.set(entry.id);
    this.encounterService.cosignNote(enc.id, entry.id).subscribe({
      next: (cosigned) => {
        this.cosigningEntryId.set(null);
        this.toast.success(this.translate.instant('ENCOUNTERS.NOTE_COSIGN_SUCCESS'));
        this.replaceEntry(cosigned ?? null);
        this.loadNoteHistory(enc.id);
      },
      error: (err) => {
        this.cosigningEntryId.set(null);
        this.toast.error(
          err?.error?.message ?? this.translate.instant('ENCOUNTERS.NOTE_COSIGN_FAILED'),
        );
      },
    });
  }

  /** Puts a ceremony response in place of the entry it answers, in the list of the stay's entries. */
  private replaceEntry(updated: EncounterNoteResponse | null): void {
    if (!updated?.id) return;
    this.noteEntries.update((list) => list.map((e) => (e.id === updated.id ? updated : e)));
  }

  /**
   * QA D831: when a save on a stay starts a new entry, the entry shown until
   * now stays in the list of the stay's entries instead of disappearing.
   */
  private keepEarlierEntry(
    previous: EncounterNoteResponse | null,
    saved: EncounterNoteResponse | null,
  ): void {
    if (!previous?.id || !saved?.id || previous.id === saved.id) return;
    this.noteEntries.update((list) => {
      const others = list.filter((e) => e.id !== previous.id && e.id !== saved.id);
      return [...others, previous, saved];
    });
  }

  cosignNote(): void {
    const enc = this.selectedEncounter();
    const current = this.noteState();
    if (!enc || !current?.id || this.cosigningNote()) return;
    this.cosigningNote.set(true);
    this.encounterService.cosignNote(enc.id, current.id).subscribe({
      next: (note) => {
        this.cosigningNote.set(false);
        this.toast.success(this.translate.instant('ENCOUNTERS.NOTE_COSIGN_SUCCESS'));
        this.replaceEntry(note ?? null);
        this.noteState.set(note ?? null);
      },
      error: (err) => {
        this.cosigningNote.set(false);
        this.toast.error(
          err?.error?.message ?? this.translate.instant('ENCOUNTERS.NOTE_COSIGN_FAILED'),
        );
      },
    });
  }

  cancelNote(): void {
    this.showNoteForm.set(false);
  }

  openEligibilityDialog(): void {
    this.showEligibilityDialog.set(true);
  }

  closeEligibilityDialog(): void {
    this.showEligibilityDialog.set(false);
  }

  private loadNoteHistory(encounterId: string): void {
    this.noteHistoryLoading.set(true);
    this.encounterService.getNoteHistory(encounterId).subscribe({
      next: (history) => {
        this.noteHistory.set((history ?? []).map(toNoteHistoryView));
        this.noteHistoryLoading.set(false);
      },
      error: () => {
        this.noteHistory.set([]);
        this.noteHistoryLoading.set(false);
      },
    });
  }

  /** Opens the addendum form for one entry: an earlier entry of the stay, or the current one. */
  openAddendumForm(entryId: string | null | undefined): void {
    this.addendumTargetId.set(entryId ?? null);
    this.addendumContent = '';
    this.showAddendumForm.set(true);
  }

  closeAddendumForm(): void {
    this.showAddendumForm.set(false);
    this.addendumTargetId.set(null);
    this.addendumContent = '';
  }

  addAddendum(): void {
    const enc = this.selectedEncounter();
    if (!enc || !this.addendumContent.trim()) return;
    // QA D831: the entry the form was opened for; the current entry when none was named.
    const noteId = this.addendumTargetId() ?? this.noteState()?.id ?? undefined;
    this.addendumSaving.set(true);
    this.encounterService
      .addAddendum(enc.id, { content: this.addendumContent.trim(), attestAccuracy: true }, noteId)
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('ENCOUNTERS.ADDENDUM_RECORDED'));
          this.closeAddendumForm();
          this.addendumSaving.set(false);
          this.loadNoteHistory(enc.id);
        },
        error: () => {
          this.toast.error(this.translate.instant('ENCOUNTERS.ADDENDUM_FAILED'));
          this.addendumSaving.set(false);
        },
      });
  }

  /* ── Status transitions ── */

  /** D96: a doctor picks up a patient who is waiting for one. */
  canStartEncounter(enc: EncounterResponse): boolean {
    return (
      enc.status === 'WAITING_FOR_PHYSICIAN' && this.auth.hasAnyRole(actionRoles('encounterStart'))
    );
  }

  /** D96: a visit ready for discharge can be checked out from here too. */
  canCheckOut(enc: EncounterResponse): boolean {
    return enc.status === 'READY_FOR_DISCHARGE';
  }

  readonly checkoutEncounter = signal<EncounterResponse | null>(null);

  startEncounter(enc: EncounterResponse): void {
    this.transitioning.set(true);
    this.encounterService.startEncounter(enc.id).subscribe({
      next: (updated) => {
        this.toast.success(this.translate.instant('ENCOUNTERS.STARTED'));
        this.transitioning.set(false);
        this.selectedEncounter.set(updated);
        this.loadEncounters();
      },
      error: () => {
        this.toast.error(this.translate.instant('ENCOUNTERS.STATUS_UPDATE_FAILED'));
        this.transitioning.set(false);
      },
    });
  }

  openCheckout(enc: EncounterResponse): void {
    this.checkoutEncounter.set(enc);
  }

  onCheckoutDismissed(): void {
    this.checkoutEncounter.set(null);
  }

  onCheckoutCompleted(): void {
    const enc = this.checkoutEncounter();
    this.checkoutEncounter.set(null);
    this.loadEncounters();
    if (enc) {
      this.encounterService.getById(enc.id).subscribe({
        next: (updated) => this.selectedEncounter.set(updated),
      });
    }
  }

  canCompleteExamination(enc: EncounterResponse): boolean {
    return enc.status === 'IN_PROGRESS';
  }

  /**
   * QA D788: from IN_PROGRESS this ends the consultation, so the API admits it
   * for the doctor, nurse or midwife only; the desk keeps AWAITING_RESULTS.
   */
  canMarkReadyForDischarge(enc: EncounterResponse): boolean {
    if (enc.status === 'AWAITING_RESULTS') {
      return true;
    }
    return (
      enc.status === 'IN_PROGRESS' &&
      this.auth.hasAnyRole([...SERVICE_RULE_ROLES.readyForDischargeFromInProgress])
    );
  }

  completeExamination(enc: EncounterResponse): void {
    this.transitioning.set(true);
    this.encounterService.completeExamination(enc.id).subscribe({
      next: (updated) => {
        this.toast.success(this.translate.instant('ENCOUNTERS.EXAM_COMPLETED'));
        this.transitioning.set(false);
        this.selectedEncounter.set(updated);
        this.loadEncounters();
      },
      error: () => {
        this.toast.error(this.translate.instant('ENCOUNTERS.EXAM_COMPLETE_FAILED'));
        this.transitioning.set(false);
      },
    });
  }

  markReadyForDischarge(enc: EncounterResponse): void {
    this.transitioning.set(true);
    this.encounterService.markReadyForDischarge(enc.id).subscribe({
      next: (updated) => {
        this.toast.success(this.translate.instant('ENCOUNTERS.MARKED_READY_FOR_DISCHARGE'));
        this.transitioning.set(false);
        this.selectedEncounter.set(updated);
        this.loadEncounters();
      },
      error: () => {
        this.toast.error(this.translate.instant('ENCOUNTERS.STATUS_UPDATE_FAILED'));
        this.transitioning.set(false);
      },
    });
  }

  /* ── After-visit summary ── */

  viewAvs(enc: EncounterResponse): void {
    this.avsLoading.set(true);
    this.encounterService.getAvs(enc.id).subscribe({
      next: (summary) => {
        this.avs.set(summary);
        this.avsLoading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('ENCOUNTERS.AVS_NONE'));
        this.avsLoading.set(false);
      },
    });
  }

  closeAvs(): void {
    this.avs.set(null);
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'SCHEDULED':
        return 'status-open';
      case 'ARRIVED':
      case 'TRIAGE':
        return 'status-progress';
      case 'WAITING_FOR_PHYSICIAN':
        return 'status-warning';
      case 'IN_PROGRESS':
        return 'status-progress';
      case 'AWAITING_RESULTS':
        return 'status-warning';
      case 'READY_FOR_DISCHARGE':
        return 'status-ready';
      case 'COMPLETED':
        return 'status-completed';
      case 'CANCELLED':
      case 'LEFT_WITHOUT_BEING_SEEN':
        return 'status-cancelled';
      default:
        return '';
    }
  }

  getTypeIcon(type: string): string {
    switch (type) {
      case 'OUTPATIENT':
        return 'directions_walk';
      case 'INPATIENT':
        return 'hotel';
      case 'EMERGENCY':
        return 'emergency';
      case 'CONSULTATION':
        return 'forum';
      case 'FOLLOW_UP':
        return 'event_repeat';
      case 'SURGERY':
        return 'medical_services';
      case 'LAB':
        return 'science';
      default:
        return 'medical_services';
    }
  }

  countByStatus(status: string): number {
    return this.encounters().filter((e) => e.status === status).length;
  }
}
