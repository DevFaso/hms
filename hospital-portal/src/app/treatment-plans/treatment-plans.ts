import { PersonNamePipe } from '../shared/pipes/person-name.pipe';
import { textMatches, rankPeopleByName } from '../shared/person-search';
import {
  Component,
  ElementRef,
  inject,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { Subject } from 'rxjs';
import { debounceTime, switchMap } from 'rxjs/operators';
import {
  TreatmentPlanService,
  TreatmentPlanResponse,
  TreatmentPlanRequest,
} from '../services/treatment-plan.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { StaffService, isClinicalProvider, StaffResponse } from '../services/staff.service';
import { PatientService, PatientResponse } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { RoleContextService } from '../core/role-context.service';
import { HospitalScopeUrlService } from '../core/hospital-scope-url.service';
import { HospitalScopeChipComponent } from '../shared/hospital-scope-chip/hospital-scope-chip.component';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { hasValue, missingRequiredMessage } from '../shared/required-fields';
import { refuseFields } from '../shared/forms/invalid-fields';
import { labelledRefusalReason, refusedFieldReasons } from '../shared/refusal-reason';
import { PatientSublinePipe } from '../shared/patient-picker/patient-subline';
import { ModalDialogDirective } from '../shared/a11y/modal-dialog.directive';
import { AuthService } from '../auth/auth.service';
import { AuthorshipService } from '../shared/authorship.service';

import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
import { ListEscapeDirective } from '../shared/a11y/list-escape.directive';
@Component({
  selector: 'app-treatment-plans',
  standalone: true,
  imports: [
    ListEscapeDirective,
    SubmitGuardDirective,
    PersonNamePipe,
    ModalDialogDirective,
    PatientSublinePipe,
    ServerTimePipe,
    CommonModule,
    FormsModule,
    TranslateModule,
    HospitalScopeChipComponent,
    EnumLabelPipe,
  ],
  templateUrl: './treatment-plans.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './treatment-plans.scss',
})
export class TreatmentPlansComponent implements OnInit {
  private readonly tpService = inject(TreatmentPlanService);
  private readonly hospitalService = inject(HospitalService);
  private readonly staffService = inject(StaffService);
  private readonly patientService = inject(PatientService);
  private readonly toast = inject(ToastService);
  private readonly roleContext = inject(RoleContextService);
  private readonly route = inject(ActivatedRoute);
  private readonly scopeUrl = inject(HospitalScopeUrlService);
  private readonly translate = inject(TranslateService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly authorship = inject(AuthorshipService);
  private readonly auth = inject(AuthService);

  /** Cross-tenant signals — drive the chip + Hospital column toggle. */
  protected readonly isSuperAdmin = this.roleContext.isSuperAdmin;
  protected readonly globalView = this.roleContext.globalView;

  plans = signal<TreatmentPlanResponse[]>([]);
  filtered = signal<TreatmentPlanResponse[]>([]);
  loading = signal(true);
  searchTerm = '';
  activeTab = signal<'all' | 'active' | 'draft' | 'completed'>('all');
  selectedPlan = signal<TreatmentPlanResponse | null>(null);

  hospitals = signal<HospitalResponse[]>([]);
  staffList = signal<StaffResponse[]>([]);
  /** Every staff row of the scope, the caller's own included whatever their role. */
  private readonly allStaff = signal<StaffResponse[]>([]);
  /** The author named by the plan being edited, when it is not in the staff list. */
  private editAuthorName = '';

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
  editId = '';
  form: TreatmentPlanRequest = this.emptyForm();

  ngOnInit(): void {
    // Cross-tenant: hydrate URL scope before the first list fetch so
    // the auth interceptor sends the right X-Hospital-Id (or omits it
    // for global view). See docs/super-admin-cross-tenant-design.md.
    this.scopeUrl.applyUrlScopeSync(this.route);

    this.load();
    this.loadAssignedHospitals();
    this.staffService.list().subscribe((s) => {
      this.allStaff.set(s ?? []);
      this.staffList.set((s ?? []).filter(isClinicalProvider));
    });
    this.initPatientSearch();
  }

  /** Re-fetch under the new cross-tenant scope when the chip emits. */
  onScopeChange(_hospitalId: string | null): void {
    this.load();
  }

  /**
   * Status options for the plan form's select. Labels come from the shared
   * `PORTAL.ENUM.TREATMENT_PLAN_STATUS` block so the dropdown reads in the
   * user's language; the wire value stays the raw enum.
   */
  get treatmentPlanStatuses(): { value: string; label: string }[] {
    // QA D1127: the author never sets APPROVED (the server refuses it); another
    // clinician approves from the plan's detail. Shown only on a plan that is
    // already approved, so the edit keeps reading its status.
    const statuses = ['DRAFT', 'IN_REVIEW', 'REVISIONS_REQUIRED', 'ARCHIVED', 'CANCELLED'];
    if (this.form?.status === 'APPROVED') {
      statuses.splice(3, 0, 'APPROVED');
    }
    return statuses.map((value) => ({
      value,
      label: this.translate.instant(`PORTAL.ENUM.TREATMENT_PLAN_STATUS.${value}`),
    }));
  }

  emptyForm(): TreatmentPlanRequest {
    return {
      patientId: '',
      hospitalId: '',
      authorStaffId: '',
      problemStatement: '',
      timelineStartDate: '',
      timelineReviewDate: '',
      status: 'DRAFT',
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
        switchMap((q) => {
          this.patientSearchLoading.set(true);
          return this.patientService.list(this.patientSearchHospitalId(), q);
        }),
      )
      .subscribe({
        next: (list) => {
          // QA D1239: ranked by how the name answers the query, then cut.
          this.patientSuggestions.set(rankPeopleByName(list, this.patientQuery()).slice(0, 8));
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

  /**
   * QA D1332 (round 29, user decision): a treatment plan's author is the
   * signed-in clinician, as real hospitals record it. The server refuses any
   * other author ("you can only act in your own name") unless the caller is a
   * super-admin, so only a super-admin is offered the staff list; everyone
   * else sees their own name, read-only, and it is sent for them.
   */
  get authorChoosable(): boolean {
    return this.roleContext.isSuperAdmin();
  }

  /** The caller's own staff row at the form's hospital, or '' when they have none there. */
  ownAuthorStaffId(): string {
    const userId = this.auth.getUserProfile()?.id;
    if (!userId) return '';
    const hospitalId = this.form.hospitalId;
    return (
      this.allStaff().find(
        (st) => st.userId === userId && (!hospitalId || st.hospitalId === hospitalId),
      )?.id ?? ''
    );
  }

  /** The author shown read-only: the plan's on an edit, the caller on a create. */
  authorDisplayName(): string {
    const id = this.editing() ? this.form.authorStaffId : this.ownAuthorStaffId();
    const row = this.allStaff().find((st) => st.id === id);
    if (row) return row.name;
    return this.editing() ? this.editAuthorName : '';
  }

  openCreate(): void {
    this.form = this.emptyForm();
    this.editing.set(false);
    this.editId = '';
    this.selectedPatient.set(null);
    this.patientQuery.set('');
    // Re-apply locked hospital after emptyForm() reset
    if (this.hospitalLocked) {
      const h = this.hospitals();
      if (h.length === 1) this.form.hospitalId = h[0].id;
    }
    this.showModal.set(true);
  }

  /**
   * QA D1081: the plan's author rewrites it. The row names the author by
   * user when the server sends it, else by staff record.
   */
  canEditPlan(p: TreatmentPlanResponse): boolean {
    const myStaffId = this.auth.getUserProfile()?.staffId ?? null;
    return this.authorship.isAuthor(p, !p.authorStaffId || p.authorStaffId === myStaffId);
  }

  openEdit(p: TreatmentPlanResponse): void {
    this.form = {
      patientId: p.patientId ?? '',
      hospitalId: p.hospitalId ?? '',
      authorStaffId: p.authorStaffId ?? '',
      problemStatement: p.problemStatement ?? '',
      timelineStartDate: p.timelineStartDate ?? '',
      timelineReviewDate: p.timelineReviewDate ?? '',
      status: p.status ?? 'DRAFT',
    };
    this.editId = p.id;
    this.editAuthorName = p.authorStaffName ?? '';
    this.selectedPatient.set(null);
    this.patientQuery.set('');
    this.editing.set(true);
    this.showModal.set(true);
  }

  closeModal(): void {
    this.showModal.set(false);
  }

  /** QA D890: the plan's required fields, by the label the form shows them under. */
  private static readonly FIELD_LABELS: Readonly<Record<string, string>> = {
    patientId: 'PATIENTS.PATIENT',
    hospitalId: 'ENCOUNTERS.HOSPITAL',
    authorStaffId: 'TREATMENT.AUTHOR_STAFF',
    problemStatement: 'TREATMENT.PROBLEM_STATEMENT',
  };

  /** QA D1306 (round 28): the control each required field is entered in. */
  private static readonly FIELD_IDS: Readonly<Record<string, string>> = {
    patientId: 'tp-patientSearch',
    hospitalId: 'tp-hospitalId',
    authorStaffId: 'tp-authorStaff',
    problemStatement: 'tp-problem',
  };

  /**
   * The control of each required field as rendered: the author is the staff
   * select for a super-admin, the read-only name of the caller otherwise (QA D1332).
   */
  private fieldIds(): Readonly<Record<string, string>> {
    return {
      ...TreatmentPlansComponent.FIELD_IDS,
      authorStaffId: this.authorChoosable ? 'tp-authorStaff' : 'tp-authorSelf',
    };
  }

  submitForm(): void {
    // QA D1332: on a create the author is the caller (the staff list arrives
    // after the dialog may have opened, so it is read now); an edit keeps the
    // plan's author, as the server requires.
    if (!this.editing() && !this.authorChoosable) {
      this.form.authorStaffId = this.ownAuthorStaffId();
    }
    // QA D890: an empty form was posted and answered with a generic toast.
    const missing = missingRequiredMessage(this.translate, [
      { labelKey: 'PATIENTS.PATIENT', value: this.form.patientId },
      { labelKey: 'ENCOUNTERS.HOSPITAL', value: this.form.hospitalId },
      { labelKey: 'TREATMENT.AUTHOR_STAFF', value: this.form.authorStaffId },
      { labelKey: 'TREATMENT.PROBLEM_STATEMENT', value: this.form.problemStatement },
    ]);
    const fieldIds = this.fieldIds();
    const allIds = Object.values(fieldIds);
    if (missing) {
      // QA D1306 (round 28): the blank fields are marked, not only a toast.
      const values = this.form as unknown as Record<string, unknown>;
      const blankIds = Object.entries(fieldIds)
        .filter(([field]) => !hasValue(values[field]))
        .map(([, id]) => id);
      refuseFields(
        this.host.nativeElement,
        blankIds,
        allIds,
        this.translate.instant('COMMON.FIELD_REQUIRED'),
      );
      this.toast.error(missing);
      return;
    }
    refuseFields(this.host.nativeElement, [], allIds, '');
    this.saving.set(true);
    const op = this.editing()
      ? this.tpService.update(this.editId, this.form)
      : this.tpService.create(this.form);
    op.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(
            this.editing() ? 'TREATMENT.PLAN_UPDATED' : 'TREATMENT.PLAN_CREATED',
          ),
        );
        this.closeModal();
        this.saving.set(false);
        this.load();
      },
      error: (err: unknown) => {
        // D1256 class (round 29): the fields a 400 names are marked too, each
        // with the server's own reason, not only listed in the toast.
        const refused = refusedFieldReasons(err, fieldIds);
        if (refused.length > 0) {
          refuseFields(
            this.host.nativeElement,
            refused.map(([id, message]) => ({ id, message })),
            allIds,
          );
        }
        this.toast.error(
          labelledRefusalReason(err, this.translate, TreatmentPlansComponent.FIELD_LABELS) ??
            this.translate.instant('TREATMENT.SAVE_FAILED'),
        );
        this.saving.set(false);
      },
    });
  }

  load(): void {
    this.loading.set(true);
    this.tpService.getAll({ size: 200 }).subscribe({
      next: (list) => {
        this.plans.set(list ?? []);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('TREATMENT.LOAD_FAILED'));
        this.loading.set(false);
      },
    });
  }

  setTab(tab: 'all' | 'active' | 'draft' | 'completed'): void {
    this.activeTab.set(tab);
    this.applyFilter();
  }

  applyFilter(): void {
    let list = this.plans();
    const tab = this.activeTab();
    if (tab === 'active') list = list.filter((p) => p.status === 'APPROVED');
    else if (tab === 'draft')
      list = list.filter((p) => ['DRAFT', 'IN_REVIEW', 'REVISIONS_REQUIRED'].includes(p.status));
    else if (tab === 'completed') list = list.filter((p) => p.status === 'ARCHIVED');
    const term = this.searchTerm.toLowerCase().trim();
    if (term) {
      list = list.filter(
        (p) =>
          textMatches(p.patientName, term) ||
          (p.problemStatement ?? '').toLowerCase().includes(term) ||
          textMatches(p.authorStaffName, term),
      );
    }
    this.filtered.set(list);
  }

  viewDetail(p: TreatmentPlanResponse): void {
    this.selectedPlan.set(p);
  }

  /** QA D1127: a plan not yet approved, opened by a clinician other than its author. */
  canApprovePlan(p: TreatmentPlanResponse): boolean {
    const myStaffId = this.auth.getUserProfile()?.staffId ?? null;
    return (
      !!myStaffId &&
      !this.canEditPlan(p) &&
      ['DRAFT', 'IN_REVIEW', 'REVISIONS_REQUIRED'].includes(p.status)
    );
  }

  readonly approving = signal(false);

  /**
   * QA D1127 (round 20): the approval is another clinician's review (the
   * server refuses the author's, and refuses APPROVED on the create and the
   * edit). It moves the plan to APPROVED on the server.
   */
  approvePlan(p: TreatmentPlanResponse): void {
    const myStaffId = this.auth.getUserProfile()?.staffId ?? null;
    if (!myStaffId) {
      return;
    }
    this.approving.set(true);
    this.tpService.addReview(p.id, { reviewerStaffId: myStaffId, action: 'APPROVED' }).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('TREATMENT.PLAN_APPROVED'));
        this.approving.set(false);
        this.closeDetail();
        this.load();
      },
      error: (err: unknown) => {
        this.toast.error(
          labelledRefusalReason(err, this.translate, TreatmentPlansComponent.FIELD_LABELS) ??
            this.translate.instant('TREATMENT.SAVE_FAILED'),
        );
        this.approving.set(false);
      },
    });
  }
  closeDetail(): void {
    this.selectedPlan.set(null);
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'DRAFT':
        return 'status-draft';
      case 'IN_REVIEW':
        return 'status-pending';
      case 'APPROVED':
        return 'status-active';
      case 'ARCHIVED':
        return 'status-completed';
      case 'REVISIONS_REQUIRED':
        return 'status-rejected';
      case 'CANCELLED':
        return 'status-rejected';
      default:
        return '';
    }
  }

  countByGroup(group: string): number {
    if (group === 'active') return this.plans().filter((p) => p.status === 'APPROVED').length;
    if (group === 'draft')
      return this.plans().filter((p) =>
        ['DRAFT', 'IN_REVIEW', 'REVISIONS_REQUIRED'].includes(p.status),
      ).length;
    if (group === 'completed') return this.plans().filter((p) => p.status === 'ARCHIVED').length;
    return 0;
  }
}
