import { LabCorrectionBadgeComponent } from '../shared/lab-correction-badge.component';
import { textMatches } from '../../shared/person-search';
import { CdkTrapFocus } from '@angular/cdk/a11y';
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
import { ActivatedRoute } from '@angular/router';
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
import { currentLocale } from '../../shared/i18n/app-locale';
import { shortOrderCode } from '../../shared/lab/order-code';
import {
  nowLocalDatetime,
  toLocalInputValue,
  toServerDateTime,
  serverInstant,
} from '../../shared/date-utils';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { labSeverityFlag, labSeverityTone } from '../shared/lab-severity';
import { LabValuePipe, formatLabNumber } from '../shared/lab-value.pipe';

/**
 * D49: the order code the API sends is the order's UUID. Readers get its first
 * eight characters, enough to tell two orders apart on one screen.
 */
export { shortOrderCode };

/** D49 (moved to shared/date-utils for the admission form, QA D622). */
export { toServerDateTime, toLocalInputValue };

@Component({
  selector: 'app-lab-results',
  standalone: true,
  imports: [
    LabCorrectionBadgeComponent,
    CdkTrapFocus,
    LabValuePipe,
    ServerTimePipe,
    CommonModule,
    FormsModule,
    TranslateModule,
    HospitalScopeChipComponent,
    EnumLabelPipe,
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

  orders = signal<LabOrderResponse[]>([]);
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
    // QA D645: an order whose test definition no longer resolves has nothing a
    // result can be entered against; it is not offered ("— — —" in the picker).
    this.labService.listOrders({ size: 500 }).subscribe((list) =>
      // QA D645/D648: an order whose test definition was deleted carries a
      // "deleted test" label but cannot take a result; it stays out of the picker.
      this.orders.set(
        list.filter((o) => !o.labTestDefinitionMissing && !!(o.labTestName || o.labTestCode)),
      ),
    );
    this.profileService.getAssignments().subscribe({
      next: (assignments) => {
        const active = assignments.find((a: { active: boolean }) => a.active);
        if (active) this.activeAssignmentId = active.id;
      },
    });
  }

  /** Re-fetch under the new scope when the chip emits. */
  onScopeChange(_hospitalId: string | null): void {
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
  }

  onOrderChange(orderId: string): void {
    const order = this.orders().find((o) => o.id === orderId);
    if (order) {
      this.form.patientId = order.patientId ?? '';
    }
  }

  submitForm(): void {
    const selectedOrder = this.orders().find((o) => o.id === this.form.labOrderId);
    this.form.assignmentId = this.activeAssignmentId;
    this.form.patientId = selectedOrder?.patientId ?? this.form.patientId ?? '';
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
      next: () => {
        this.toast.success(
          this.translate.instant(this.editing() ? 'LAB_RESULTS.UPDATED' : 'LAB_RESULTS.CREATED'),
        );
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
        const serverMessage = err?.error?.message;
        this.toast.error(
          typeof serverMessage === 'string' && serverMessage.trim()
            ? serverMessage
            : this.translate.instant('LAB_RESULTS.SAVE_ERROR'),
        );
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
      error: () => {
        this.toast.error(this.translate.instant('LAB_RESULTS.DELETE_ERROR'));
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
    if (this.isSelfRelease(r)) {
      this.selfReleaseTarget.set(r);
      return;
    }
    this.sendRelease(r, false);
  }

  confirmSelfRelease(): void {
    const target = this.selfReleaseTarget();
    if (!target) return;
    this.selfReleaseTarget.set(null);
    this.sendRelease(target, true);
  }

  cancelSelfRelease(): void {
    this.selfReleaseTarget.set(null);
  }

  private sendRelease(r: LabResultResponse, selfRelease: boolean): void {
    this.labService.releaseResult(r.id, selfRelease).subscribe({
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
    this.labService.listResults({ size: 200 }).subscribe({
      next: (list) => {
        this.results.set(list);
        this.computeStats(list);
        this.applyFilter();
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
        this.applyFilter();
      },
      error: () => {
        this.toast.error(this.translate.instant('LAB_RESULTS.CRITICAL_LOAD_ERROR'));
        this.criticalLoading.set(false);
      },
    });
  }

  applyFilter(): void {
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
  }

  viewResult(r: LabResultResponse): void {
    this.selectedResult.set(r);
  }

  closeDetail(): void {
    this.selectedResult.set(null);
  }

  getReleasedByDisplay(r: LabResultResponse): string {
    const actor = (r.releasedByFullName ?? '').trim();
    return actor || this.translate.instant('LAB_RESULTS.AUTOVERIFICATION');
  }

  sortedTrendHistory(r: LabResultResponse): LabResultTrendPoint[] {
    const history = r.trendHistory ?? [];
    return [...history].sort(
      (a, b) => serverInstant(b.resultDate).getTime() - serverInstant(a.resultDate).getTime(),
    );
  }

  formatReferenceRange(range: {
    minValue: number | null;
    maxValue: number | null;
    unit: string | null;
  }): string {
    const min = range.minValue;
    const max = range.maxValue;
    const unit = range.unit ? ` ${range.unit}` : '';

    // QA D1002: the limits in the reader's number format ("0,7 – 1,1 g/L").
    if (min != null && max != null) {
      return `${formatLabNumber(min)} – ${formatLabNumber(max)}${unit}`;
    }
    if (min != null) {
      return `≥ ${formatLabNumber(min)}${unit}`;
    }
    if (max != null) {
      return `≤ ${formatLabNumber(max)}${unit}`;
    }
    return '—';
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

  formatTrendDelta(sortedHistory: LabResultTrendPoint[], index: number): string {
    const current = Number(sortedHistory[index]?.resultValue);
    const previous = Number(sortedHistory[index + 1]?.resultValue);
    if (!Number.isFinite(current) || !Number.isFinite(previous)) return '—';
    const delta = current - previous;
    const sign = delta > 0 ? '+' : '';
    return `${sign}${new Intl.NumberFormat(currentLocale(), { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(delta)}`;
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
        error: () => {
          this.toast.error(this.translate.instant('LAB_RESULTS.SIGN_ERROR'));
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
        this.applyFilter();
        this.closeReadBack();
      },
      error: (err: { error?: { message?: string } }) => {
        // A mismatch is the interesting case, and the server explains it in
        // clinical terms — show that rather than a generic failure.
        this.toast.error(
          err?.error?.message ?? this.translate.instant('LAB_RESULTS.READ_BACK_ERROR'),
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
        this.applyFilter();
      },
      error: () => this.toast.error(this.translate.instant('LAB_RESULTS.ACKNOWLEDGE_ERROR')),
    });
  }

  openCompare(r: LabResultResponse): void {
    this.comparisonLoading.set(true);
    this.labService.compareResult(r.id).subscribe({
      next: (cmp) => {
        this.comparison.set(cmp);
        this.comparisonLoading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('LAB_RESULTS.COMPARE_ERROR'));
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
