import { PersonNamePipe } from '../shared/pipes/person-name.pipe';
import { textMatches } from '../shared/person-search';
import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  computed,
  inject,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule, DOCUMENT } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { Subject, of } from 'rxjs';
import { catchError, debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import {
  AdmissionService,
  AdmissionResponse,
  AdmissionRequest,
  AdmissionType,
  AcuityLevel,
} from '../services/admission.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { StaffService, StaffResponse, isPhysician } from '../services/staff.service';
import { PatientService, PatientResponse } from '../services/patient.service';
import { DepartmentLookupService, DepartmentOption } from '../services/department-lookup.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { HospitalScopeUrlService } from '../core/hospital-scope-url.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { OrderSetPickerComponent } from './order-set-picker/order-set-picker.component';
import { AuthService } from '../auth/auth.service';
import { ActionKey, actionRoles } from '../core/action-permissions';
import { AppliedOrderSetSummary } from '../services/order-set.service';
import { HospitalScopeChipComponent } from '../shared/hospital-scope-chip/hospital-scope-chip.component';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { fromServerDateTime, toLocalInputValue, toServerDateTime } from '../shared/date-utils';
import { BedService, BedResponse } from '../services/bed.service';
import { PatientSublinePipe } from '../shared/patient-picker/patient-subline';
import { ModalDialogDirective } from '../shared/a11y/modal-dialog.directive';
import { refusalReason } from '../shared/refusal-reason';
import { refuseFields } from '../shared/forms/invalid-fields';

/** The bed-release reason's field (QA D1100), marked when it is refused. */
const UNASSIGN_REASON_ID = 'admission-unassign-bed-reason';

interface OrderSetPickerCtx {
  hospitalId: string;
  admissionId: string;
  encounterId: string;
  orderingStaffId: string;
}

/** QA D603: the stay statuses that may still be cancelled (mirrors AdmissionServiceImpl). */
const CANCELLABLE_STATUSES = new Set<string>(['PENDING', 'ACTIVE', 'ON_LEAVE']);

@Component({
  selector: 'app-admissions',
  standalone: true,
  imports: [
    ModalDialogDirective,
    PersonNamePipe,
    PatientSublinePipe,
    CommonModule,
    FormsModule,
    TranslateModule,
    OrderSetPickerComponent,
    HospitalScopeChipComponent,
    EnumLabelPipe,
  ],
  templateUrl: './admissions.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './admissions.scss',
})
export class AdmissionsComponent implements OnInit {
  /** D622: admission times are UTC wall clock; show them in the user's zone. */
  protected readonly fromServerDateTime = fromServerDateTime;
  private readonly admissionService = inject(AdmissionService);
  private readonly hospitalService = inject(HospitalService);
  private readonly staffService = inject(StaffService);
  private readonly departmentLookup = inject(DepartmentLookupService);
  private readonly patientService = inject(PatientService);
  private readonly toast = inject(ToastService);
  private readonly roleContext = inject(RoleContextService);
  private readonly route = inject(ActivatedRoute);
  private readonly translate = inject(TranslateService);
  /** The page's document: the bed-release dialog's field is marked in it (QA D1100). */
  private readonly doc = inject(DOCUMENT);
  private readonly auth = inject(AuthService);
  private readonly scopeUrl = inject(HospitalScopeUrlService);
  private readonly bedService = inject(BedService);

  /** Cross-tenant signals — drive the chip + Hospital column toggle. */
  protected readonly isSuperAdmin = this.roleContext.isSuperAdmin;
  protected readonly globalView = this.roleContext.globalView;
  /**
   * QA D427/D527: each admission action mirrors its endpoint (core/action-permissions):
   * a nurse may update, apply order sets and move beds, but only a doctor admits,
   * discharges or cancels; she was offered Delete and got a 403.
   */
  protected readonly canAdmit = computed(() => this.can('admissionCreate'));
  protected readonly canEditAdmission = computed(() => this.can('admissionUpdate'));
  protected readonly canApplyOrderSets = computed(() => this.can('admissionApplyOrderSets'));
  protected readonly canAssignBed = computed(() => this.can('admissionAssignBed'));
  protected readonly canUnassignBed = computed(() => this.can('admissionUnassignBed'));
  protected readonly canDischarge = computed(() => this.can('admissionDischarge'));
  protected readonly canCancelAdmission = computed(() => this.can('admissionCancel'));

  private can(action: ActionKey): boolean {
    return this.roleContext.hasAnyActiveRole(actionRoles(action));
  }

  admissions = signal<AdmissionResponse[]>([]);
  filtered = signal<AdmissionResponse[]>([]);
  loading = signal(true);
  searchTerm = '';
  activeTab = signal<'all' | 'admitted' | 'discharged'>('all');

  /**
   * When non-null, the order-set picker is rendered as a modal-style
   * overlay. Set by {@link openOrderSetPicker}; cleared by
   * {@link closeOrderSetPicker} or after a successful apply.
   *
   * <p>encounterId is left blank in v0 because AdmissionResponse does
   * not carry the active encounter — fan-out to MEDICATION + LAB tolerates
   * a null encounter; IMAGING items in the set will surface a clear
   * server error. Follow-up work will add `currentEncounterId` to
   * AdmissionResponse so imaging-rich sets work end-to-end here too.
   */
  orderSetPicker = signal<OrderSetPickerCtx | null>(null);

  hospitals = signal<HospitalResponse[]>([]);
  staffMembers = signal<StaffResponse[]>([]);
  /**
   * QA D618 (D176/D527): the server records the caller as the discharging
   * provider (only a super-admin in global view may name another), so the
   * discharge form offers the caller alone. It listed every physician and a
   * pick other than oneself was refused with a 403.
   */
  readonly dischargeProviders = computed<Pick<StaffResponse, 'id' | 'name' | 'email'>[]>(() => {
    if (this.isSuperAdmin() && this.globalView()) {
      return this.staffMembers();
    }
    const profile = this.auth.getUserProfile();
    const myStaffId = profile?.staffId;
    if (!myStaffId) {
      return [];
    }
    const mine = this.staffMembers().find((s) => s.id === myStaffId);
    if (mine) {
      return [mine];
    }
    const name = `${profile?.firstName ?? ''} ${profile?.lastName ?? ''}`.trim();
    return [{ id: myStaffId, name, email: profile?.email ?? '' }];
  });
  private allStaff: StaffResponse[] = [];
  /**
   * QA D918: the physicians offered are those of the stay's hospital. The
   * list was loaded once for the home hospital, so a super-admin working on
   * hospital B was offered A's physicians, which the server refuses. A newer
   * hospital cancels the load of an older one.
   */
  private readonly staffHospital$ = new Subject<string>();
  /** The hospital the staff list was last requested for. */
  private staffHospitalId: string | null = null;
  departments = signal<DepartmentOption[]>([]);
  departmentsLoading = signal(false);
  departmentsFailed = signal(false);

  // Patient picker
  patientQuery = signal('');
  patientSuggestions = signal<PatientResponse[]>([]);
  patientDropdownOpen = signal(false);
  patientSearchLoading = signal(false);
  selectedPatient = signal<PatientResponse | null>(null);
  private readonly patientSearch$ = new Subject<string>();

  /* ── CRUD signals ── */
  showModal = signal(false);
  editing = signal(false);
  saving = signal(false);
  editingId = signal<string | null>(null);
  form: AdmissionRequest = this.emptyForm();

  showDeleteConfirm = signal(false);
  deletingAdm = signal<AdmissionResponse | null>(null);
  deleting = signal(false);

  /* ── Bed assignment (P0 #4) ── */
  bedAssignFor = signal<AdmissionResponse | null>(null);
  availableBeds = signal<BedResponse[]>([]);
  bedsLoading = signal(false);
  selectedBedId = signal<string>('');
  bedAssigning = signal(false);

  /* ── Discharge signals ── */
  showDischargeModal = signal(false);
  dischargingAdm = signal<AdmissionResponse | null>(null);
  discharging = signal(false);
  dischargeForm = {
    dischargeDisposition: 'HOME' as string,
    dischargeSummary: '',
    dischargeDiagnosis: '',
    dischargeInstructions: '',
    dischargingProviderId: '',
  };
  readonly dispositionOptions = [
    { value: 'HOME', labelKey: 'ADMISSIONS.DISPOSITION_HOME' },
    { value: 'HOME_WITH_HOME_HEALTH', labelKey: 'ADMISSIONS.DISPOSITION_HOME_WITH_HOME_HEALTH' },
    {
      value: 'SKILLED_NURSING_FACILITY',
      labelKey: 'ADMISSIONS.DISPOSITION_SKILLED_NURSING_FACILITY',
    },
    {
      value: 'LONG_TERM_CARE_FACILITY',
      labelKey: 'ADMISSIONS.DISPOSITION_LONG_TERM_CARE_FACILITY',
    },
    {
      value: 'REHABILITATION_FACILITY',
      labelKey: 'ADMISSIONS.DISPOSITION_REHABILITATION_FACILITY',
    },
    { value: 'HOSPICE_HOME', labelKey: 'ADMISSIONS.DISPOSITION_HOSPICE_HOME' },
    { value: 'HOSPICE_FACILITY', labelKey: 'ADMISSIONS.DISPOSITION_HOSPICE_FACILITY' },
    { value: 'PSYCHIATRIC_FACILITY', labelKey: 'ADMISSIONS.DISPOSITION_PSYCHIATRIC_FACILITY' },
    { value: 'AGAINST_MEDICAL_ADVICE', labelKey: 'ADMISSIONS.DISPOSITION_AGAINST_MEDICAL_ADVICE' },
    {
      value: 'TRANSFERRED_TO_ANOTHER_HOSPITAL',
      labelKey: 'ADMISSIONS.DISPOSITION_TRANSFERRED_TO_ANOTHER_HOSPITAL',
    },
    { value: 'OTHER', labelKey: 'ADMISSIONS.DISPOSITION_OTHER' },
  ];

  admissionTypes: AdmissionType[] = [
    'EMERGENCY',
    'ELECTIVE',
    'URGENT',
    'NEWBORN',
    'TRANSFER',
    'OBSERVATION',
    'DAY_CASE',
    'LABOR_DELIVERY',
    'PSYCHIATRIC',
  ];
  acuityLevels: AcuityLevel[] = [
    'LEVEL_5_CRITICAL',
    'LEVEL_4_SEVERE',
    'LEVEL_3_MAJOR',
    'LEVEL_2_MODERATE',
    'LEVEL_1_MINIMAL',
  ];

  /**
   * Localized acuity labels, indexed directly by the template
   * (`acuityLabel[a.acuityLevel]`). A getter rather than a field so the
   * labels follow a language switch instead of freezing at construction.
   */
  get acuityLabel(): Record<string, string> {
    return {
      LEVEL_5_CRITICAL: this.translate.instant('ADMISSIONS.ACUITY_CRITICAL'),
      LEVEL_4_SEVERE: this.translate.instant('ADMISSIONS.ACUITY_SEVERE'),
      LEVEL_3_MAJOR: this.translate.instant('ADMISSIONS.ACUITY_MAJOR'),
      LEVEL_2_MODERATE: this.translate.instant('ADMISSIONS.ACUITY_MODERATE'),
      LEVEL_1_MINIMAL: this.translate.instant('ADMISSIONS.ACUITY_MINIMAL'),
    };
  }

  ngOnInit(): void {
    // Cross-tenant: hydrate URL scope before the first list fetch so
    // the auth interceptor sends the right X-Hospital-Id (or omits it
    // for global view). See docs/super-admin-cross-tenant-design.md.
    this.scopeUrl.applyUrlScopeSync(this.route);

    this.load();
    this.loadAssignedHospitals();
    // ── TENANT ISOLATION: scope staff list to the stay's hospital ──
    this.staffHospital$
      .pipe(
        distinctUntilChanged(),
        switchMap((hid) =>
          this.staffService.list(hid).pipe(catchError(() => of([] as StaffResponse[]))),
        ),
      )
      .subscribe((s) => {
        this.allStaff = s ?? [];
        // Physicians only: the server refuses a nurse or a midwife as admitting,
        // attending or discharging provider (QA D84, D176).
        this.staffMembers.set((s ?? []).filter(isPhysician));
      });
    this.loadStaffFor(
      this.roleContext.effectiveHospitalIdForRequest() ?? this.roleContext.activeHospitalId,
    );
    this.initPatientSearch();

    const tab = this.route.snapshot.queryParamMap.get('tab');
    if (tab === 'admitted' || tab === 'discharged') {
      this.activeTab.set(tab);
    }
  }

  emptyForm(): AdmissionRequest {
    return {
      patientId: '',
      hospitalId: '',
      admittingProviderId: '',
      admissionType: 'ELECTIVE',
      acuityLevel: 'LEVEL_2_MODERATE',
      admissionDateTime: '',
      chiefComplaint: '',
    };
  }

  /** ── TENANT ISOLATION: only SUPER_ADMIN may choose from all hospitals ── */
  private loadAssignedHospitals(): void {
    if (this.roleContext.isSuperAdmin()) {
      this.hospitalService.list().subscribe((h) => this.hospitals.set(h ?? []));
    } else {
      this.hospitalService.getMyHospitalAsResponse().subscribe({
        next: (h) => {
          this.hospitals.set([h]);
          this.form.hospitalId = h.id;
          // The modal may already be open when the hospital arrives (D153).
          if (this.showModal() && !this.editing()) this.loadDepartmentsFor(h.id);
        },
      });
    }
  }

  get lockedHospitalName(): string {
    const h = this.hospitals();
    return h.length === 1 ? h[0].name : this.translate.instant('COMMON.NO_HOSPITAL_ASSIGNED');
  }

  get hospitalLocked(): boolean {
    return !this.roleContext.isSuperAdmin();
  }

  // ── Patient picker ──
  initPatientSearch(): void {
    this.patientSearch$
      .pipe(
        debounceTime(220),
        distinctUntilChanged(),
        switchMap((q) => {
          this.patientSearchLoading.set(true);
          // ── TENANT ISOLATION: scope patient search to the form's hospital ──
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
    // QA D918: a physician of the previous hospital is refused at this one.
    this.form.admittingProviderId = '';
    this.loadDepartmentsFor(hospitalId);
    this.loadStaffFor(hospitalId);
  }

  /** QA D918: (re)load the physicians of `hospitalId`; a no-op when unchanged. */
  loadStaffFor(hospitalId: string | null | undefined): void {
    if (!hospitalId || hospitalId === this.staffHospitalId) return;
    this.staffHospitalId = hospitalId;
    // The previous hospital's physicians must not be pickable while this loads.
    this.staffMembers.set([]);
    this.staffHospital$.next(hospitalId);
  }

  /**
   * Same fix as the encounters modal: the list used to be derived from
   * `allStaff`, so a department with nobody assigned to it never appeared.
   *
   * <p>Unlike encounters, department stays OPTIONAL here —
   * AdmissionRequestDTO.departmentId carries no @NotNull, so "any department"
   * is a legitimate answer on this form and the empty option keeps its
   * meaning.
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

  openCreate(): void {
    this.form = this.emptyForm();
    this.editing.set(false);
    this.editingId.set(null);
    this.selectedPatient.set(null);
    this.patientQuery.set('');
    this.departments.set([]);
    // Re-apply locked hospital after emptyForm() reset
    if (this.hospitalLocked) {
      const h = this.hospitals();
      if (h.length === 1) this.form.hospitalId = h[0].id;
    }
    // QA D153: a single-hospital user never picks a hospital, so the
    // (change) that loads the departments never fired and the list stayed
    // empty. Load them for whatever hospital the form already carries.
    this.loadDepartmentsFor(this.form.hospitalId);
    this.loadStaffFor(this.form.hospitalId);
    this.showModal.set(true);
  }

  openOrderSetPicker(a: AdmissionResponse): void {
    if (!a.id || !a.hospitalId) return;
    // The orderer is the signed-in clinician (the person clicking Apply),
    // not the admission's admittingProvider. Resolved via AuthService so
    // the apply request anchors prescriptions / labs / imaging on the
    // correct staff identity for audit + CDS attribution.
    const orderingStaffId = this.auth.getUserProfile()?.staffId ?? '';
    if (!orderingStaffId) {
      this.toast.error(this.translate.instant('ORDER_SETS.MISSING_ORDERING_STAFF'));
      return;
    }
    this.orderSetPicker.set({
      hospitalId: a.hospitalId,
      admissionId: a.id,
      encounterId: a.currentEncounterId ?? '',
      orderingStaffId,
    });
  }

  closeOrderSetPicker(): void {
    this.orderSetPicker.set(null);
  }

  onOrderSetApplied(summary: AppliedOrderSetSummary): void {
    // Compute the real created/skipped counts from the backend summary
    // so the toast shows accurate numbers instead of empty placeholders.
    const count =
      summary.prescriptionIds.length + summary.labOrderIds.length + summary.imagingOrderIds.length;
    this.toast.success(
      this.translate.instant('ORDER_SETS.APPLIED_RESULT', {
        count,
        skipped: summary.skippedItemCount,
      }),
    );
  }

  openEdit(a: AdmissionResponse): void {
    this.form = {
      patientId: a.patientId ?? '',
      hospitalId: a.hospitalId ?? '',
      admittingProviderId: a.admittingProviderId ?? '',
      departmentId: a.departmentId,
      roomBed: a.roomBed,
      admissionType: a.admissionType ?? 'ELECTIVE',
      acuityLevel: a.acuityLevel ?? 'MODERATE',
      // QA D622: the API's UTC wall clock, shown in the user's zone (a date alone left the input blank).
      admissionDateTime: a.admissionDateTime ? toLocalInputValue(a.admissionDateTime) : '',
      chiefComplaint: a.chiefComplaint ?? '',
      admissionNotes: a.admissionNotes,
    };
    this.selectedPatient.set({
      id: a.patientId ?? '',
      firstName: a.patientName?.split(' ')[0] ?? '',
      lastName: a.patientName?.split(' ').slice(1).join(' ') ?? '',
      email: '',
      // QA D912: reopening an admission keeps this hospital's MRN on the chip.
      mrn: a.patientMrn ?? undefined,
    } as PatientResponse);
    this.loadDepartmentsFor(a.hospitalId ?? '');
    this.loadStaffFor(a.hospitalId);
    this.editing.set(true);
    this.editingId.set(a.id);
    this.showModal.set(true);
  }

  closeModal(): void {
    this.showModal.set(false);
  }

  submitForm(): void {
    this.saving.set(true);
    // QA D622: datetime-local is the user's wall clock; the API stores the UTC one.
    const payload: AdmissionRequest = {
      ...this.form,
      admissionDateTime: this.form.admissionDateTime
        ? toServerDateTime(this.form.admissionDateTime)
        : this.form.admissionDateTime,
    };
    const op = this.editing()
      ? this.admissionService.update(this.editingId()!, payload)
      : this.admissionService.create(payload);
    op.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(this.editing() ? 'ADMISSIONS.UPDATED' : 'ADMISSIONS.CREATED'),
        );
        this.closeModal();
        this.saving.set(false);
        this.load();
      },
      // The server says why (a nurse as admitting provider, QA D176) in the
      // caller's language; the generic text is the fallback only.
      error: (err: HttpErrorResponse) => {
        this.toast.error(err?.error?.message ?? this.translate.instant('ADMISSIONS.SAVE_FAILED'));
        this.saving.set(false);
      },
    });
  }

  /**
   * QA D603: only a stay still in progress can be cancelled. A discharged,
   * transferred or deceased stay carries a finalized summary and an invoice;
   * the server refuses the delete, so the row offers none.
   */
  isCancellable(a: AdmissionResponse): boolean {
    return CANCELLABLE_STATUSES.has(a.status);
  }

  confirmDelete(a: AdmissionResponse): void {
    this.deletingAdm.set(a);
    this.showDeleteConfirm.set(true);
  }
  cancelDelete(): void {
    this.showDeleteConfirm.set(false);
    this.deletingAdm.set(null);
  }
  executeDelete(): void {
    this.deleting.set(true);
    this.admissionService.delete(this.deletingAdm()!.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('ADMISSIONS.DELETED'));
        this.cancelDelete();
        this.deleting.set(false);
        this.load();
      },
      error: (err: unknown) => {
        this.toast.error(refusalReason(err) ?? this.translate.instant('ADMISSIONS.DELETE_FAILED'));
        this.deleting.set(false);
      },
    });
  }

  /* ── Discharge ── */
  confirmDischarge(a: AdmissionResponse): void {
    this.dischargingAdm.set(a);
    this.loadStaffFor(a.hospitalId);
    this.dischargeForm = {
      dischargeDisposition: 'HOME',
      dischargeSummary: '',
      dischargeDiagnosis: '',
      dischargeInstructions: '',
      // QA D503 class: the server records the caller as the discharging provider.
      dischargingProviderId: this.auth.getUserProfile()?.staffId ?? '',
    };
    this.showDischargeModal.set(true);
  }

  cancelDischarge(): void {
    this.showDischargeModal.set(false);
    this.dischargingAdm.set(null);
  }

  executeDischarge(): void {
    this.discharging.set(true);
    const adm = this.dischargingAdm()!;
    this.admissionService
      .discharge(adm.id, {
        dischargeDisposition: this.dischargeForm.dischargeDisposition,
        dischargeSummary: this.dischargeForm.dischargeSummary,
        dischargeDiagnosis: this.dischargeForm.dischargeDiagnosis.trim(),
        dischargeInstructions: this.dischargeForm.dischargeInstructions || undefined,
        dischargingProviderId: this.dischargeForm.dischargingProviderId,
      })
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('ADMISSIONS.DISCHARGE_SUCCESS'));
          this.cancelDischarge();
          this.discharging.set(false);
          this.load();
        },
        error: (err: HttpErrorResponse) => {
          this.toast.error(
            err?.error?.message ?? this.translate.instant('ADMISSIONS.DISCHARGE_ERROR'),
          );
          this.discharging.set(false);
        },
      });
  }

  /* ── Bed assignment (P0 #4) ─────────────────────────────── */

  openBedAssign(adm: AdmissionResponse): void {
    this.bedAssignFor.set(adm);
    this.selectedBedId.set('');
    this.availableBeds.set([]);
    this.bedsLoading.set(true);
    this.bedService.getAvailableBeds(adm.patientId).subscribe({
      next: (beds) => {
        this.availableBeds.set(beds);
        this.bedsLoading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('ADMISSIONS.BEDS_LOAD_FAILED'));
        this.bedsLoading.set(false);
      },
    });
  }

  closeBedAssign(): void {
    this.bedAssignFor.set(null);
    this.selectedBedId.set('');
  }

  submitBedAssign(): void {
    const adm = this.bedAssignFor();
    const bedId = this.selectedBedId();
    if (!adm || !bedId) return;
    this.bedAssigning.set(true);
    this.admissionService.assignBed(adm.id, bedId).subscribe({
      next: (updated) => {
        this.toast.success(
          this.translate.instant('ADMISSIONS.BED_ASSIGNED', { bed: updated.roomBed }),
        );
        this.bedAssigning.set(false);
        this.closeBedAssign();
        this.load();
      },
      error: (err: unknown) => {
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('ADMISSIONS.BED_ASSIGN_FAILED'),
        );
        this.bedAssigning.set(false);
      },
    });
  }

  /* ── Free the bed (QA D1100: confirmed, with a reason; the server audits it) ── */
  readonly unassigningAdm = signal<AdmissionResponse | null>(null);
  readonly unassignReason = signal('');
  readonly unassigning = signal(false);

  openUnassignBed(adm: AdmissionResponse): void {
    this.unassignReason.set('');
    this.unassigningAdm.set(adm);
  }

  closeUnassignBed(): void {
    this.unassigningAdm.set(null);
  }

  confirmUnassignBed(): void {
    const adm = this.unassigningAdm();
    if (!adm) return;
    const reason = this.unassignReason().trim();
    if (!reason) {
      const message = this.translate.instant('ADMISSION_BED.RELEASE_REASON_REQUIRED');
      refuseFields(this.doc, [UNASSIGN_REASON_ID], [UNASSIGN_REASON_ID], message);
      return;
    }
    this.unassigning.set(true);
    this.admissionService.unassignBed(adm.id, reason).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('ADMISSIONS.BED_UNASSIGNED'));
        this.unassigning.set(false);
        this.closeUnassignBed();
        this.load();
      },
      error: (err: unknown) => {
        this.unassigning.set(false);
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('ADMISSIONS.BED_ASSIGN_FAILED'),
        );
      },
    });
  }

  /** Re-fetch under the new cross-tenant scope when the chip emits. */
  onScopeChange(_hospitalId: string | null): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    // Re-fetch under the current cross-tenant scope (header alone
    // drives the backend; super-admin global view sends no header,
    // backend falls through to unscoped findAll).
    this.admissionService.getAll().subscribe({
      next: (list) => {
        this.admissions.set(list ?? []);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('ADMISSIONS.LOAD_FAILED'));
        this.loading.set(false);
      },
    });
  }

  setTab(tab: 'all' | 'admitted' | 'discharged'): void {
    this.activeTab.set(tab);
    this.applyFilter();
  }

  applyFilter(): void {
    let list = this.admissions();
    const tab = this.activeTab();
    if (tab === 'admitted') list = list.filter((a) => a.status === 'ACTIVE');
    else if (tab === 'discharged') list = list.filter((a) => a.status === 'DISCHARGED');
    const term = this.searchTerm.toLowerCase().trim();
    if (term) {
      list = list.filter(
        (a) =>
          textMatches(a.patientName, term) ||
          (a.chiefComplaint ?? '').toLowerCase().includes(term) ||
          (a.roomBed ?? '').toLowerCase().includes(term),
      );
    }
    this.filtered.set(list);
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'ACTIVE':
        return 'status-admitted';
      case 'DISCHARGED':
        return 'status-discharged';
      case 'PENDING':
        return 'status-pending';
      case 'TRANSFERRED':
        return 'status-transferred';
      default:
        return '';
    }
  }

  getAcuityClass(level: string): string {
    switch (level) {
      case 'LEVEL_5_CRITICAL':
        return 'acuity-critical';
      case 'LEVEL_4_SEVERE':
        return 'acuity-high';
      case 'LEVEL_3_MAJOR':
        return 'acuity-moderate';
      default:
        return 'acuity-low';
    }
  }

  countByStatus(status: string): number {
    return this.admissions().filter((a) => a.status === status).length;
  }
}
