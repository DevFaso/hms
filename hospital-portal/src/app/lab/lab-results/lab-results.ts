import { ResultEvolutionComponent } from '../shared/result-evolution.component';
import { ResultRangeComponent } from '../shared/result-range.component';
import { refusalReason } from '../../shared/refusal-reason';
import { LabCorrectionBadgeComponent } from '../shared/lab-correction-badge.component';
import { textMatches } from '../../shared/person-search';
import {
  Component,
  inject,
  OnInit,
  signal,
  computed,
  ChangeDetectionStrategy,
} from '@angular/core';
import { actionRoles } from '../../core/action-permissions';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  LabService,
  LabResultResponse,
  LabResultTrendPoint,
  LabResultReferenceRange,
  LabResultRequest,
  LabOrderResponse,
  LabResultComparison,
} from '../../services/lab.service';
import { ToastService } from '../../core/toast.service';
import { ProfileService } from '../../services/profile.service';
import { RoleContextService } from '../../core/role-context.service';
import { AuthService } from '../../auth/auth.service';
import { HospitalScopeUrlService } from '../../core/hospital-scope-url.service';
import { HospitalScopeChipComponent } from '../../shared/hospital-scope-chip/hospital-scope-chip.component';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { shortOrderCode } from '../../shared/lab/order-code';
import { nowLocalDatetime, toLocalInputValue, toServerDateTime } from '../../shared/date-utils';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { labSeverityFlag, labSeverityTone, labReleaseNeedsReview } from '../shared/lab-severity';
import { LabValuePipe, formatLabNumber } from '../shared/lab-value.pipe';
import { formatLabReferenceRange, ReferenceRangeLimits } from '../shared/reference-range-text';
import { MeasurePipe } from '../../shared/pipes/measure.pipe';

import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';
import { ModalDialogDirective } from '../../shared/a11y/modal-dialog.directive';
import { DateTimePipe } from '../../shared/pipes/date-time.pipe';
import { DayMonthPipe } from '../../shared/pipes/day-month.pipe';
import { formatDecimal } from '../../shared/i18n/app-locale';
import { deltaPrecision, previousObservation } from '../shared/result-evolution';
/**
 * D49: the order code the API sends is the order's UUID. Readers get its first
 * eight characters, enough to tell two orders apart on one screen.
 */
export { shortOrderCode };

/** D49 (moved to shared/date-utils for the admission form, QA D622). */
export { toServerDateTime, toLocalInputValue };

/**
 * QA D1176: the order statuses that never take a NEW result — one is already
 * recorded (RESULTED, VERIFIED, COMPLETED: the server answers 409
 * "labresult.alreadyRecorded") or the order is closed (CANCELLED,
 * ENTERED_IN_ERROR).
 */
const ORDER_STATUSES_WITHOUT_NEW_RESULT: ReadonlySet<string> = new Set([
  'RESULTED',
  'VERIFIED',
  'COMPLETED',
  'CANCELLED',
  'ENTERED_IN_ERROR',
]);

/**
 * QA D1204 / D1176 (round 22): the server refuses a hand-entered result on an
 * order whose specimen was never taken or was rejected
 * (LabResultServiceImpl.requireSpecimenTaken: status ORDERED or PENDING,
 * "Aucun prélèvement n'a encore été enregistré…"). The picker offered them
 * and the refusal came only after "Créer". The server exempts point-of-care
 * tests, but that flag is neither sent with an order nor settable from the
 * portal, so no order the portal can show is exempt.
 * `scripts/lib/lab-result-picker.test.mjs` holds this list to the server's.
 */
export const ORDER_STATUSES_AWAITING_SPECIMEN: ReadonlySet<string> = new Set([
  'ORDERED',
  'PENDING',
]);

@Component({
  selector: 'app-lab-results',
  standalone: true,
  imports: [
    ResultEvolutionComponent,
    ResultRangeComponent,
    DateTimePipe,
    SubmitGuardDirective,
    MeasurePipe,
    LabCorrectionBadgeComponent,
    ModalDialogDirective,
    LabValuePipe,
    ServerTimePipe,
    CommonModule,
    FormsModule,
    TranslateModule,
    HospitalScopeChipComponent,
    EnumLabelPipe,
    RouterLink,
  ],
  templateUrl: './lab-results.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './lab-results.scss',
})
export class LabResultsComponent implements OnInit {
  private readonly labService = inject(LabService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly profileService = inject(ProfileService);
  private readonly roleContext = inject(RoleContextService);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly scopeUrl = inject(HospitalScopeUrlService);

  /** Cross-tenant signals — drive the chip + Hospital column toggle. */
  protected readonly isSuperAdmin = this.roleContext.isSuperAdmin;

  /**
   * Mirrors LabResultAuthority.LABORATORY_ROLES + SUPER_ADMIN — the roles the
   * backend lets enter a result for ANY test.
   */
  private static readonly LABORATORY_ROLES: readonly string[] = [
    'ROLE_LAB_SCIENTIST',
    'ROLE_LAB_TECHNICIAN',
    'ROLE_LAB_MANAGER',
    'ROLE_LAB_DIRECTOR',
    'ROLE_QUALITY_MANAGER',
    'ROLE_SUPER_ADMIN',
  ];

  /** Mirrors LabResultAuthority.POINT_OF_CARE_ROLES. */
  private static readonly POINT_OF_CARE_ROLES: readonly string[] = [
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
  ];

  /**
   * Whether to offer "New Result" at all.
   *
   * <p>The button used to be unconditional, so it showed for every role the
   * route admits — including ROLE_ADMIN and ROLE_HOSPITAL_ADMIN, which the
   * create endpoint does not permit. Those users got a 403 on submit from a
   * control the page had offered them.
   *
   * <p>Bedside roles keep the button. Whether they may enter a result for a
   * given TEST depends on that test's point-of-care flag and on whether the
   * hospital has switched enforcement on — neither of which the browser
   * knows. The backend decides, and its refusal explains itself.
   */
  protected canEnterResults(): boolean {
    // Exactly, not with doctor equivalence: LabResultServiceImpl
    // .validateLabResultAuthor checks RoleValidator.isDoctor, which matches
    // the assignment code DOCTOR only, so a surgeon would pass the annotation
    // and be refused on submit.
    return this.roleContext.hasAnyActiveRoleExactly([
      ...LabResultsComponent.LABORATORY_ROLES,
      ...LabResultsComponent.POINT_OF_CARE_ROLES,
    ]);
  }
  /** QA D616: the delete icon only for the roles DELETE /lab-results/{id} admits. */
  readonly canDeleteResult = computed(() =>
    this.roleContext.hasAnyActiveRoleExactly(actionRoles('labResultDelete')),
  );
  protected readonly globalView = this.roleContext.globalView;

  loading = signal(true);
  results = signal<LabResultResponse[]>([]);
  filtered = signal<LabResultResponse[]>([]);
  pageSize = signal(10);
  page = signal(1);
  pageCount = computed(() => Math.max(1, Math.ceil(this.filtered().length / this.pageSize())));
  hasNextPage = computed(() => this.currentPage() * this.pageSize() < this.filtered().length);
  currentPage = computed(() => Math.min(this.page(), this.pageCount()));
  pageStart = computed(() => (this.currentPage() - 1) * this.pageSize());
  pageEnd = computed(() => Math.min(this.pageStart() + this.pageSize(), this.filtered().length));
  pagedResults = computed(() => this.filtered().slice(this.pageStart(), this.pageEnd()));
  detailHistory = signal<LabResultTrendPoint[]>([]);
  detailHistoryLoading = signal(false);
  detailHistoryError = signal(false);

  /**
   * r44: the observation before the open result, from the history the detail
   * read returned (nothing is fetched for it), and the change from it.
   */
  detailPrevious = computed(() => {
    const r = this.selectedResult();
    return r ? previousObservation(this.detailHistory(), r) : null;
  });

  /**
   * r44: the two lines under the value, "Précédent : 1,32 g/L le 12 nov." and
   * "Variation : -0,27 g/L" (or why the change is not given).
   */
  detailPreviousText = computed(() => {
    const r = this.selectedResult();
    const prev = this.detailPrevious();
    if (!r || !prev) return null;
    const value = this.labValue.transform(prev.point.resultValue, prev.point.resultUnit);
    const previous = this.translate.instant('LAB_RESULTS.PREVIOUS_VALUE', {
      value,
      date: this.dayMonth.transform(prev.point.resultDate),
    });
    const change =
      prev.delta === null
        ? this.translate.instant('LAB_RESULTS.CHANGE_WITHHELD')
        : this.translate.instant('LAB_RESULTS.CHANGE', {
            delta: this.formatChange(
              prev.delta,
              r.resultValue,
              prev.point.resultValue,
              r.resultUnit,
            ),
          });
    return { previous, change };
  });
  private readonly labValue = new LabValuePipe();
  private readonly dayMonth = new DayMonthPipe();

  /** "+0,27 g/L" / "-0,27 g/L", at the finer precision of the two recorded values. */
  formatChange(delta: number, current: string, previous: string, unit: string): string {
    const text = formatDecimal(delta, deltaPrecision(current, previous));
    return `${delta > 0 ? '+' : ''}${text} ${(unit ?? '').trim()}`.trim();
  }

  /**
   * r44: the reference ranges as the drawer lists them: the one applied to
   * this patient first, the others apart (collapsed). Each distinct range
   * once (two equal rows add nothing), and a band that states no interval is
   * left out when the patient is outside it (the neonatal glucose rows on an
   * adult); an unknown age keeps it.
   */
  detailRanges = computed(() => {
    const r = this.selectedResult();
    if (!r) return { applied: null, others: [] as LabResultReferenceRange[] };
    const seen = new Set<string>();
    const shown = (r.referenceRanges ?? []).filter((rr) => {
      const noInterval = rr.minValue == null && rr.maxValue == null;
      if (noInterval && rr.patientInAgeBand === false) return false;
      const key = JSON.stringify([
        rr.minValue,
        rr.maxValue,
        rr.unit ?? null,
        rr.gender ?? null,
        rr.ageMin,
        rr.ageMax,
        rr.ageUnit ?? null,
        rr.criticalLow ?? null,
        rr.criticalHigh ?? null,
        rr.notes ?? null,
        rr.source ?? null,
      ]);
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
    const applied = shown.find((rr) => this.isGradedRange(r, rr)) ?? null;
    return { applied, others: shown.filter((rr) => rr !== applied) };
  });

  /** r44: the stored critical limits, "< 0,45 / > 5 g/L"; '' when none is stored. */
  formatCriticalLimits(range: LabResultReferenceRange): string {
    const parts: string[] = [];
    if (range.criticalLow != null) parts.push(`< ${formatLabNumber(range.criticalLow)}`);
    if (range.criticalHigh != null) parts.push(`> ${formatLabNumber(range.criticalHigh)}`);
    if (!parts.length) return '';
    const unit = (range.unit ?? '').trim();
    return parts.join(' / ') + (unit ? ` ${unit}` : '');
  }
  private detailRequest = 0;

  setPageSize(size: number): void {
    this.pageSize.set([10, 25, 50].includes(Number(size)) ? Number(size) : 10);
    this.page.set(1);
  }

  searchTerm = '';
  activeTab = signal<'all' | 'released' | 'pending' | 'critical'>('all');

  stats = signal({ total: 0, released: 0, pending: 0 });

  /* ── Critical queue ── */
  criticalList = signal<LabResultResponse[]>([]);
  criticalLoading = signal(false);

  /* ── Sign ── */
  showSignModal = signal(false);
  signTarget = signal<LabResultResponse | null>(null);
  signForm = { signature: '', notes: '' };
  signSubmitting = signal(false);

  /* ── Comparison ── */
  comparison = signal<LabResultComparison | null>(null);
  comparisonLoading = signal(false);

  /**
   * Who can actually sign — which is NOT who `POST /lab-results/{id}/sign`
   * admits at its annotation.
   *
   * <p>PHYSICIAN and SURGEON are deliberately absent. `RoleExpansion` maps
   * them onto ROLE_DOCTOR while the authorities are built, so they do clear
   * the `@PreAuthorize`; but the handler then calls
   * `LabResultServiceImpl.validateSignPermissions` →
   * `RoleValidator.isDoctor(userId, hospitalId)`, which matches the stored
   * ASSIGNMENT ROLE CODE against `{DOCTOR, ROLE_DOCTOR}` and knows no
   * PHYSICIAN/SURGEON equivalence at that layer. Listing them here would show
   * a surgeon a Sign button, let them fill in the signature modal, and answer
   * 400 "Only attending clinicians may sign lab results." with no way to
   * succeed. Passing the annotation is not the same as passing the endpoint.
   *
   * <p>Whether that equivalence SHOULD exist one layer down is a question
   * about who may sign a lab result across the whole product, not a question
   * this screen gets to answer; it is recorded as standing debt in
   * tasklist.md instead.
   */
  private static readonly SIGN_ROLES = [
    'ROLE_DOCTOR',
    'ROLE_MIDWIFE',
    'ROLE_LAB_SCIENTIST',
    // SUPER_ADMIN is different, and stays: validateSignPermissions returns
    // early for it before any role-code lookup happens, so the button works.
    'ROLE_SUPER_ADMIN',
  ];

  /**
   * Whether to offer the signature control.
   *
   * <p>A method, not a field: `canSign` was evaluated once while the component
   * was being constructed. The role set it read cannot in fact change under
   * this screen — portal roles come from `/me/session-bootstrap` once and are
   * not per-hospital — so this is not a live defect here; it is the same shape
   * #723 fixed on the release button, kept consistent with it rather than left
   * as the one control in this file that answers from a snapshot.
   */
  canSign(): boolean {
    // Exactly — see SIGN_ROLES: validateSignPermissions is isDoctor-only.
    return this.roleContext.hasAnyActiveRoleExactly(LabResultsComponent.SIGN_ROLES);
  }
  /** POST /lab-results/{id}/release backend role list (LabResultAuthority.RELEASE_EXPRESSION). */
  private static readonly RELEASE_ROLES = [
    'ROLE_LAB_SCIENTIST',
    'ROLE_LAB_MANAGER',
    'ROLE_LAB_DIRECTOR',
    'ROLE_SUPER_ADMIN',
  ];
  /** GET /lab-results/hospital/{id}/critical/unacknowledged backend role list. */
  private static readonly CRITICAL_QUEUE_ROLES = [
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_LAB_SCIENTIST',
    'ROLE_LAB_DIRECTOR',
    'ROLE_QUALITY_MANAGER',
  ];
  /** POST /{id}/acknowledge — intersection of @PreAuthorize and SecurityConfig matcher. */
  private static readonly ACKNOWLEDGE_ROLES = [
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_LAB_SCIENTIST',
    'ROLE_LAB_MANAGER',
  ];
  /**
   * POST /{id}/critical-read-back — deliberately NOT the acknowledge list.
   * Read-back is the ordering clinician confirming what they were told; the
   * backend excludes the lab roles (lab attestation is not clinician receipt),
   * so showing them the button meant a control that always 403'd. Hospital
   * admins lost this with E9 #67: read-back is a clinician's act.
   */
  private static readonly READ_BACK_ROLES = [
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_SUPER_ADMIN',
  ];

  /*
   * The three below are methods, read live on every check — never fields
   * captured while the component was being constructed. A field is a role
   * snapshot: it survives a change of active role or hospital scope in place
   * and then answers for a context the user has left (the defect #722/#724
   * fixed for release and sign on this screen).
   */
  canSeeCritical(): boolean {
    return this.roleContext.hasAnyActiveRole(LabResultsComponent.CRITICAL_QUEUE_ROLES);
  }

  canAcknowledge(): boolean {
    return this.roleContext.hasAnyActiveRole(LabResultsComponent.ACKNOWLEDGE_ROLES);
  }

  canReadBack(): boolean {
    return this.roleContext.hasAnyActiveRole(LabResultsComponent.READ_BACK_ROLES);
  }

  private hospitalId(): string | null {
    return this.roleContext.activeHospitalId ?? this.auth.getHospitalId();
  }

  /** The orders "New result" offers (QA D1176), plus the order of a result being edited or corrected. */
  orders = signal<LabOrderResponse[]>([]);
  /** Every order a result can be filed against, whatever its status. */
  private resultableOrders: LabOrderResponse[] = [];
  private activeAssignmentId = '';

  /* ── CRUD signals ── */
  showModal = signal(false);
  editing = signal(false);
  editingId = signal<string | null>(null);
  /** D81: the released result a new entry corrects; null for an ordinary entry. */
  correcting = signal<LabResultResponse | null>(null);
  saving = signal(false);
  form: LabResultRequest = this.emptyForm();

  showDeleteConfirm = signal(false);
  /** D58: the result whose self-release is being confirmed; null when no dialog is open. */
  selfReleaseTarget = signal<LabResultResponse | null>(null);
  readonly shortOrderCode = shortOrderCode;
  deletingResult = signal<LabResultResponse | null>(null);
  deleting = signal(false);

  /* ── Detail panel ── */
  selectedResult = signal<LabResultResponse | null>(null);

  ngOnInit(): void {
    // Cross-tenant: hydrate URL scope before the first list fetch so
    // the auth interceptor sends the right X-Hospital-Id (or omits it
    // for global view). See docs/super-admin-cross-tenant-design.md.
    this.scopeUrl.applyUrlScopeSync(this.route);

    this.loadResults();
    this.openFromQuery();
    // QA D645: an order whose test definition no longer resolves has nothing a
    // result can be entered against; it is not offered ("— — —" in the picker).
    // QA D1347: every order in scope (all pages), not the 500 newest.
    this.labService.listOrders().subscribe((list) => {
      // QA D645/D648: an order whose test definition was deleted carries a
      // "deleted test" label but cannot take a result; it stays out of the picker.
      this.resultableOrders = list.filter(
        (o) => !o.labTestDefinitionMissing && !!(o.labTestName || o.labTestCode),
      );
      this.orders.set(this.pickableOrders());
    });
    this.profileService.getAssignments().subscribe({
      next: (assignments) => {
        const active = assignments.find((a: { active: boolean }) => a.active);
        if (active) this.activeAssignmentId = active.id;
      },
    });
  }

  /**
   * `/lab-results?resultId=…` opens that result's detail: the lab requests
   * drawer links each validated value to its full result here. A result the
   * reader may not see is refused by the server and nothing opens.
   */
  private openFromQuery(): void {
    const resultId = this.route.snapshot.queryParamMap.get('resultId');
    if (!resultId) return;
    this.labService.getResult(resultId).subscribe({
      next: (r) => {
        if (r?.id === resultId) this.viewResult(r);
      },
      error: () => {
        /* the list stays shown */
      },
    });
  }

  /** Re-fetch under the new scope when the chip emits. */
  onScopeChange(_hospitalId: string | null): void {
    this.closeDetail();
    this.page.set(1);
    this.loadResults();
  }

  emptyForm(): LabResultRequest {
    return {
      labOrderId: '',
      assignmentId: this.activeAssignmentId ?? '',
      patientId: '',
      resultValue: '',
      resultUnit: '',
      resultDate: nowLocalDatetime(),
      notes: '',
    };
  }

  /* ── CRUD ── */

  /**
   * QA D1176: "New result" listed every order; one that already carries a
   * result (or is closed) was refused with a 409 only after the form was
   * filled. Such orders are corrected from their released result instead.
   */
  private pickableOrders(keepOrderId?: string | null): LabOrderResponse[] {
    return this.resultableOrders.filter(
      (o) =>
        o.id === keepOrderId ||
        (!ORDER_STATUSES_WITHOUT_NEW_RESULT.has(o.status) &&
          !ORDER_STATUSES_AWAITING_SPECIMEN.has(o.status)),
    );
  }

  openCreate(): void {
    this.form = this.emptyForm();
    this.editing.set(false);
    this.editingId.set(null);
    this.correcting.set(null);
    this.showModal.set(true);
  }

  /**
   * D81: a released result cannot be edited or deleted; its correction is a
   * new result on the same order that names it, so the released row is
   * marked superseded and both stay on record.
   */
  openCorrection(r: LabResultResponse): void {
    this.orders.set(this.pickableOrders(r.labOrderId));
    this.form = {
      ...this.emptyForm(),
      labOrderId: r.labOrderId ?? '',
      patientId: r.patientId ?? '',
      resultUnit: r.resultUnit ?? '',
      supersedesResultId: r.id,
    };
    this.editing.set(false);
    this.editingId.set(null);
    this.correcting.set(r);
    this.showModal.set(true);
  }

  /** A released result that no correction has replaced yet. */
  canCorrect(r: LabResultResponse): boolean {
    return this.canEnterResults() && !!r.released && !r.supersededByResultId;
  }

  openEdit(r: LabResultResponse): void {
    this.orders.set(this.pickableOrders(r.labOrderId));
    this.form = {
      labOrderId: r.labOrderId ?? '',
      assignmentId: this.activeAssignmentId,
      patientId: r.patientId ?? '',
      resultValue: r.resultValue ?? '',
      resultUnit: r.resultUnit ?? '',
      resultDate: r.resultDate ? toLocalInputValue(r.resultDate) : nowLocalDatetime(),
      notes: r.notes ?? '',
    };
    this.editing.set(true);
    this.editingId.set(r.id);
    this.correcting.set(null);
    this.showModal.set(true);
  }

  closeModal(): void {
    this.showModal.set(false);
    // The order an edit or a correction added back leaves the "New result" picker.
    if (this.resultableOrders.length > 0) this.orders.set(this.pickableOrders());
  }

  onOrderChange(orderId: string): void {
    const order = this.orders().find((o) => o.id === orderId);
    if (order) {
      this.form.patientId = order.patientId ?? '';
      // QA D1095: units come from the test definition. The field is
      // prefilled with it (and required when the test has one), so a value is
      // never saved bare.
      this.form.resultUnit = order.labTestUnit ?? '';
    }
  }

  /** QA D1095: the selected order's test unit, which the unit field requires. */
  /** QA D1394: the value states a limit ("> 6,5"), which the server grades from its bound. */
  valueHasComparator(): boolean {
    return /^\s*[<>\u2264\u2265]/.test(this.form.resultValue ?? '');
  }

  selectedTestUnit(): string {
    return (
      this.orders()
        .find((o) => o.id === this.form.labOrderId)
        ?.labTestUnit?.trim() ?? ''
    );
  }

  submitForm(): void {
    const selectedOrder = this.orders().find((o) => o.id === this.form.labOrderId);
    this.form.assignmentId = this.activeAssignmentId;
    this.form.patientId = selectedOrder?.patientId ?? this.form.patientId ?? '';
    // QA D1095: a test with a unit takes its result in a unit (blank was saved as "0,40").
    if (!this.form.resultUnit?.trim() && this.selectedTestUnit()) {
      this.form.resultUnit = this.selectedTestUnit();
    }
    if (!this.form.labOrderId || !this.form.assignmentId || !this.form.patientId) {
      this.toast.error(this.translate.instant('LAB_RESULTS.SAVE_VALIDATION_ERROR'));
      return;
    }
    this.saving.set(true);

    const id = this.editingId();
    const payload: LabResultRequest = {
      ...this.form,
      resultDate: toServerDateTime(this.form.resultDate),
    };
    const op =
      this.editing() && id
        ? this.labService.updateResult(id, payload)
        : this.labService.createResult(payload);

    op.subscribe({
      next: (saved) => {
        this.toast.success(
          this.translate.instant(this.editing() ? 'LAB_RESULTS.UPDATED' : 'LAB_RESULTS.CREATED'),
        );
        // QA D1394: the server graded it and could not: say so at once, not at release.
        if (labReleaseNeedsReview(saved ?? {})) {
          this.toast.warning(this.translate.instant('LAB_RESULTS.SAVED_NOT_GRADED'));
        }
        this.closeModal();
        this.saving.set(false);
        this.loadResults();
      },
      error: (err) => {
        // Surface the server's own message when it has one. The lab entry
        // guard explains WHY a result was refused and what to do about it
        // ("ask an administrator to mark this test as point-of-care"), and a
        // generic "could not save" throws that away and leaves the ward
        // thinking the system is broken.
        this.toast.error(refusalReason(err) ?? this.translate.instant('LAB_RESULTS.SAVE_ERROR'));
        this.saving.set(false);
      },
    });
  }

  confirmDelete(r: LabResultResponse): void {
    this.deletingResult.set(r);
    this.showDeleteConfirm.set(true);
  }

  cancelDelete(): void {
    this.showDeleteConfirm.set(false);
    this.deletingResult.set(null);
  }

  executeDelete(): void {
    const result = this.deletingResult();
    if (!result) return;
    this.deleting.set(true);
    this.labService.deleteResult(result.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('LAB_RESULTS.DELETED'));
        this.cancelDelete();
        this.deleting.set(false);
        this.loadResults();
      },
      error: (err: unknown) => {
        this.toast.error(refusalReason(err) ?? this.translate.instant('LAB_RESULTS.DELETE_ERROR'));
        this.deleting.set(false);
      },
    });
  }

  /**
   * B1: releasing is the running laboratory's attestation of its own work, so
   * an order sent to another hospital is released there, not here. The button
   * was offered to everyone on an unreleased row and answered 400 for the
   * ordering hospital's lab staff — a control that refuses the person it is
   * shown to teaches them to distrust the screen.
   */
  canReleaseResult(r: LabResultResponse): boolean {
    // Both halves read live. A role snapshot taken at construction goes stale
    // the moment the scope changes in place, and pairing it with a live scope
    // signal brought the button back for a user the endpoint refuses.
    if (r.released || !this.roleContext.hasAnyActiveRole(LabResultsComponent.RELEASE_ROLES)) {
      return false;
    }
    const performing = r.performingHospitalId;
    if (!performing) {
      return true;
    }
    // A super-admin is unscoped by design across this product and the backend
    // bypasses the hospital check for them outright; in global view they have
    // no effective hospital id, so comparing one would hide the control from
    // the single role that can always use it.
    if (this.roleContext.isSuperAdmin()) {
      return true;
    }
    return performing === this.roleContext.effectiveHospitalIdForRequest();
  }

  /**
   * D58: the reader entered this result. A self-release is allowed (many
   * laboratories here are one technician) but never one click: it is confirmed
   * in a dialog that says so, and the API records it as a self-release.
   */
  isSelfRelease(r: LabResultResponse): boolean {
    const me = this.auth.getUserId();
    return !!me && !!r.enteredByUserId && r.enteredByUserId === me;
  }

  releaseResult(r: LabResultResponse): void {
    // QA D1092: a value no range can grade is released only once someone
    // has read the warning, in the same confirmation dialog.
    if (this.isSelfRelease(r) || labReleaseNeedsReview(r)) {
      this.selfReleaseTarget.set(r);
      return;
    }
    this.sendRelease(r, false);
  }

  confirmSelfRelease(): void {
    const target = this.selfReleaseTarget();
    if (!target) return;
    this.selfReleaseTarget.set(null);
    this.sendRelease(target, this.isSelfRelease(target), labReleaseNeedsReview(target));
  }

  cancelSelfRelease(): void {
    this.selfReleaseTarget.set(null);
  }

  private sendRelease(r: LabResultResponse, selfRelease: boolean, ungradedConfirmed = false): void {
    this.labService.releaseResult(r.id, selfRelease, ungradedConfirmed).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('LAB_RESULTS.RELEASED'));
        this.loadResults();
      },
      error: (err: unknown) => {
        // 409: the API knows this reader entered the result — ask, explicitly.
        if (!selfRelease && (err as { status?: number } | null)?.status === 409) {
          this.selfReleaseTarget.set(r);
          return;
        }
        this.toast.error(this.translate.instant('LAB_RESULTS.RELEASE_ERROR'));
      },
    });
  }

  /* ── Data loading ── */

  loadResults(): void {
    this.loading.set(true);
    // QA D1347: every result in scope (all pages), not the 200 newest, so the
    // counters and the search reach every one.
    this.labService.listAllResults().subscribe({
      next: ({ rows: list }) => {
        this.results.set(list);
        this.computeStats(list);
        this.applyFilter(false);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('LAB_RESULTS.LOAD_ERROR'));
        this.loading.set(false);
      },
    });
  }

  private computeStats(list: LabResultResponse[]): void {
    this.stats.set({
      total: list.length,
      released: list.filter((r) => r.released).length,
      pending: list.filter((r) => !r.released).length,
    });
  }

  setTab(tab: 'all' | 'released' | 'pending' | 'critical'): void {
    this.activeTab.set(tab);
    if (tab === 'critical') {
      this.loadCritical();
    }
    this.applyFilter();
  }

  loadCritical(): void {
    const hospitalId = this.hospitalId();
    if (!hospitalId || !this.canSeeCritical()) return;
    this.criticalLoading.set(true);
    this.labService.criticalUnacknowledged(hospitalId).subscribe({
      next: (list) => {
        this.criticalList.set(list ?? []);
        this.criticalLoading.set(false);
        this.applyFilter(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('LAB_RESULTS.CRITICAL_LOAD_ERROR'));
        this.criticalLoading.set(false);
      },
    });
  }

  applyFilter(resetPage = true): void {
    const tab = this.activeTab();
    let list = tab === 'critical' ? this.criticalList() : this.results();
    if (tab === 'released') {
      list = list.filter((r) => r.released);
    } else if (tab === 'pending') {
      list = list.filter((r) => !r.released);
    }
    const term = this.searchTerm.toLowerCase().trim();
    if (term) {
      list = list.filter(
        (r) =>
          (r.labOrderCode ?? '').toLowerCase().includes(term) ||
          textMatches(r.patientFullName, term) ||
          (r.labTestName ?? '').toLowerCase().includes(term) ||
          (r.resultValue ?? '').toLowerCase().includes(term),
      );
    }
    this.filtered.set(list);
    if (resetPage) this.page.set(1);
    else this.page.set(this.currentPage());
  }

  viewResult(r: LabResultResponse): void {
    this.selectedResult.set(r);
    const request = ++this.detailRequest;
    this.detailHistory.set([]);
    this.detailHistoryLoading.set(true);
    this.detailHistoryError.set(false);
    this.labService.getResult(r.id).subscribe({
      next: (detail) => {
        if (request !== this.detailRequest) return;
        this.detailHistoryLoading.set(false);
        if (detail?.id !== r.id || detail.patientId !== r.patientId) {
          this.detailHistoryError.set(true);
          return;
        }
        this.selectedResult.set(detail);
        this.detailHistory.set(
          detail.trendHistory?.length
            ? detail.trendHistory
            : [
                {
                  resultDate: detail.resultDate,
                  resultValue: detail.resultValue,
                  resultUnit: detail.resultUnit,
                  corrected: !!detail.supersedesResultId,
                },
              ],
        );
      },
      error: () => {
        if (request !== this.detailRequest) return;
        this.detailHistoryLoading.set(false);
        this.detailHistoryError.set(true);
      },
    });
  }

  closeDetail(): void {
    ++this.detailRequest;
    this.selectedResult.set(null);
  }

  getReleasedByDisplay(r: LabResultResponse): string {
    const actor = (r.releasedByFullName ?? '').trim();
    return actor || this.translate.instant('LAB_RESULTS.AUTOVERIFICATION');
  }

  /**
   * The shared printing of a range (see {@link formatLabReferenceRange}); r44:
   * a band with no interval says so and names its unit, since two such bands
   * differed only by their unit and both read "—".
   */
  formatReferenceRange(range: ReferenceRangeLimits): string {
    if (range.minValue == null && range.maxValue == null) {
      const none = this.translate.instant('LAB_RESULTS.NO_INTERVAL');
      return range.unit ? `${none} (${range.unit})` : none;
    }
    return formatLabReferenceRange(range);
  }

  formatReferenceRangeAge(range: {
    ageMin?: number | null;
    ageMax?: number | null;
    ageUnit?: string | null;
  }): string {
    const ageMin = range.ageMin;
    const ageMax = range.ageMax;
    // Round 18: a band in days or months says so ("0–7 jours"); years stay as before.
    const unit = (range.ageUnit ?? 'YEARS').toUpperCase();
    // Round 18: free-text source and the unit word come from fixed keys.
    const suffix =
      unit === 'DAYS'
        ? ' ' + this.translate.instant('LAB_TEST_CONFIG.AGE_UNIT_DAYS')
        : unit === 'MONTHS'
          ? ' ' + this.translate.instant('LAB_TEST_CONFIG.AGE_UNIT_MONTHS')
          : '';
    if (ageMin != null && ageMax != null) return `${ageMin}–${ageMax}${suffix}`;
    if (ageMin != null) return `${ageMin}+${suffix}`;
    if (ageMax != null) return `0–${ageMax}${suffix}`;
    return this.translate.instant('LAB_RESULTS.ALL_AGES');
  }

  /** Round 18: whether this listed range is the one the result was graded against. */
  isGradedRange(result: LabResultResponse, range: LabResultReferenceRange): boolean {
    const graded = result.gradedReferenceRange;
    if (!graded) return false;
    return (
      graded.minValue === range.minValue &&
      graded.maxValue === range.maxValue &&
      (graded.unit ?? null) === (range.unit ?? null) &&
      (graded.gender ?? null) === (range.gender ?? null) &&
      (graded.ageMin ?? null) === (range.ageMin ?? null) &&
      (graded.ageMax ?? null) === (range.ageMax ?? null) &&
      (graded.ageUnit ?? null) === (range.ageUnit ?? null)
    );
  }

  /* ── Sign / acknowledge / compare ── */

  openSign(r: LabResultResponse): void {
    this.signTarget.set(r);
    this.signForm = { signature: '', notes: '' };
    this.showSignModal.set(true);
  }

  closeSign(): void {
    this.showSignModal.set(false);
    this.signTarget.set(null);
  }

  submitSign(): void {
    const target = this.signTarget();
    if (!target) return;
    this.signSubmitting.set(true);
    this.labService
      .signResult(target.id, {
        signature: this.signForm.signature.trim() || undefined,
        notes: this.signForm.notes.trim() || undefined,
      })
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('LAB_RESULTS.SIGNED'));
          this.signSubmitting.set(false);
          this.closeSign();
          this.loadResults();
        },
        error: (err: unknown) => {
          this.toast.error(refusalReason(err) ?? this.translate.instant('LAB_RESULTS.SIGN_ERROR'));
          this.signSubmitting.set(false);
        },
      });
  }

  /* ── Critical-value read-back (P0 #5) ── */

  readBackTarget = signal<LabResultResponse | null>(null);
  readBackValue = signal('');
  readBackSubmitting = signal(false);

  /**
   * A critical result asks for a read-back rather than a bare acknowledge: the
   * clinician repeats the value and the server checks it. Acknowledge stays for
   * everything else, where there is no number worth verifying.
   *
   * The server's authority is `criticalNotifiedAt` — the stamp its notifier set
   * when it decided the result was critical — and the server now REFUSES a bare
   * acknowledge on such a result. `severityFlag` alone is not enough: the
   * mapper computes it from reference ranges and returns UNSPECIFIED when the
   * test has none, so gating only on the flag offered an acknowledge button
   * that always failed for exactly the results that matter most.
   */
  isCritical(r: LabResultResponse): boolean {
    return (
      !!r.criticalNotifiedAt || labSeverityFlag({ severityFlag: r.severityFlag }) === 'CRITICAL'
    );
  }

  openReadBack(r: LabResultResponse): void {
    this.readBackTarget.set(r);
    // Deliberately blank, never prefilled with the result: the check only
    // catches a transcription error if the clinician types what they heard.
    this.readBackValue.set('');
  }

  closeReadBack(): void {
    this.readBackTarget.set(null);
    this.readBackValue.set('');
    this.readBackSubmitting.set(false);
  }

  submitReadBack(): void {
    const target = this.readBackTarget();
    const value = this.readBackValue().trim();
    if (!target || !value || this.readBackSubmitting()) {
      return;
    }
    this.readBackSubmitting.set(true);
    this.labService.readBackCriticalValue(target.id, value).subscribe({
      next: (updated) => {
        this.toast.success(this.translate.instant('LAB_RESULTS.READ_BACK_CONFIRMED'));
        const patch = (list: LabResultResponse[]): LabResultResponse[] =>
          list.map((row) => (row.id === target.id ? { ...row, ...updated } : row));
        this.results.update(patch);
        this.criticalList.update((list) => list.filter((row) => row.id !== target.id));
        this.applyFilter(false);
        this.closeReadBack();
      },
      error: (err: { error?: { message?: string } }) => {
        // A mismatch is the interesting case, and the server explains it in
        // clinical terms — show that rather than a generic failure.
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('LAB_RESULTS.READ_BACK_ERROR'),
        );
        this.readBackSubmitting.set(false);
      },
    });
  }

  acknowledge(r: LabResultResponse): void {
    this.labService.acknowledgeResult(r.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('LAB_RESULTS.ACKNOWLEDGED'));
        // Patch the row in place — no need to refetch 200 results for one flag.
        const patch = (list: LabResultResponse[]): LabResultResponse[] =>
          list.map((row) => (row.id === r.id ? { ...row, acknowledged: true } : row));
        this.results.update(patch);
        this.criticalList.update((list) => list.filter((row) => row.id !== r.id));
        this.applyFilter(false);
      },
      error: (err: unknown) =>
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('LAB_RESULTS.ACKNOWLEDGE_ERROR'),
        ),
    });
  }

  openCompare(r: LabResultResponse): void {
    this.comparisonLoading.set(true);
    this.labService.compareResult(r.id).subscribe({
      next: (cmp) => {
        this.comparison.set(cmp);
        this.comparisonLoading.set(false);
      },
      error: (err: unknown) => {
        this.toast.error(refusalReason(err) ?? this.translate.instant('LAB_RESULTS.COMPARE_ERROR'));
        this.comparisonLoading.set(false);
      },
    });
  }

  closeCompare(): void {
    this.comparison.set(null);
  }

  trendIcon(direction: string | null | undefined): string {
    switch (direction) {
      case 'INCREASING':
        return 'trending_up';
      case 'DECREASING':
        return 'trending_down';
      case 'STABLE':
        return 'trending_flat';
      case 'FLUCTUATING':
        return 'show_chart';
      default:
        return 'help';
    }
  }

  /** QA D969: drawn from the one grade reading every lab screen shares (lab-severity.ts). */
  getSeverityClass(flag: string): string {
    switch (labSeverityTone(labSeverityFlag({ severityFlag: flag }))) {
      case 'critical':
        return 'severity-badge severity-critical';
      case 'abnormal':
        return 'severity-badge severity-high';
      case 'normal':
        return 'severity-badge severity-normal';
      default:
        return 'severity-badge';
    }
  }
}
