import {
  Component,
  Input,
  OnChanges,
  OnInit,
  SimpleChanges,
  computed,
  inject,
  output,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  PatientService,
  PatientAllergy,
  PatientAllergyRequest,
  AllergySeverity,
  AllergyVerificationStatus,
  PatientProblem,
  PatientDiagnosisRequest,
  ProblemStatus,
  ProblemSeverity,
  ChartUpdate,
  ChartUpdateRequest,
  ChartSectionType,
  PatientTimeline,
  PatientLabResult,
  TimelineEntry,
} from '../../services/patient.service';
import { AuthService } from '../../auth/auth.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { CHART_ROLES } from './chart-access';
import { LabService, LabOrderResponse } from '../../services/lab.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { RestrictedRowsComponent } from '../restricted-rows/restricted-rows.component';

type ChartSection = 'allergies' | 'problems' | 'updates' | 'timeline' | 'labs';

/** How many lab rows the section asks for; the backend caps `limit` at 100. */
const LAB_PAGE_SIZE = 25;

@Component({
  selector: 'app-patient-chart',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, EnumLabelPipe, RestrictedRowsComponent],
  templateUrl: './patient-chart.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './patient-chart.component.scss',
})
export class PatientChartComponent implements OnInit, OnChanges {
  @Input({ required: true }) patientId = '';
  /**
   * E9 #64 — bumped by the host when a break-the-glass session is declared
   * or ends. A loaded timeline re-reads with its stated reason; the other
   * sections re-read on their next visit.
   */
  @Input() refreshToken = 0;

  /** E9 #64 — "Ouvrir avec motif" on a restricted line; the host opens the declaration. */
  readonly openRestricted = output<void>();

  private readonly patientService = inject(PatientService);
  private readonly labService = inject(LabService);
  private readonly auth = inject(AuthService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  section = signal<ChartSection>('allergies');

  /* ── Role gates (single source: chart-access.ts, mirrors backend @PreAuthorize) ──
   *
   * `computed`, not a field assignment: `hasAnyActiveRole` reads the service's
   * role signals, so a gate evaluated once in the constructor freezes whatever
   * role was active when the chart was first built and never notices a role or
   * hospital-scope change. Several controls on this repo were wrong for
   * exactly that reason.
   */
  readonly canViewAllergies = computed(() =>
    this.roleContext.hasAnyActiveRole([...CHART_ROLES.viewAllergies]),
  );
  readonly canEditAllergies = computed(() =>
    this.roleContext.hasAnyActiveRole([...CHART_ROLES.editAllergies]),
  );
  readonly canViewProblems = computed(() =>
    this.roleContext.hasAnyActiveRole([...CHART_ROLES.viewProblems]),
  );
  readonly canEditProblems = computed(() =>
    this.roleContext.hasAnyActiveRole([...CHART_ROLES.editProblems]),
  );
  readonly canViewUpdates = computed(() =>
    this.roleContext.hasAnyActiveRole([...CHART_ROLES.viewUpdates]),
  );
  readonly canCreateUpdates = computed(() =>
    this.roleContext.hasAnyActiveRole([...CHART_ROLES.createUpdates]),
  );
  readonly canViewTimeline = computed(() =>
    this.roleContext.hasAnyActiveRole([...CHART_ROLES.viewTimeline]),
  );
  /** B6 — `GET /patients/{id}/lab-results`. */
  readonly canViewLabResults = computed(() =>
    this.roleContext.hasAnyActiveRole([...CHART_ROLES.viewLabResults]),
  );
  /** B6 — `GET /lab-orders?patientId=`; a narrower list, see chart-access.ts. */
  readonly canViewLabOrders = computed(() =>
    this.roleContext.hasAnyActiveRole([...CHART_ROLES.viewLabOrders]),
  );
  /** The Labs tab shows whichever of the two reads this role is allowed. */
  readonly canViewLabs = computed(() => this.canViewLabResults() || this.canViewLabOrders());

  /* ── Allergies ── */
  allergies = signal<PatientAllergy[]>([]);
  allergiesLoading = signal(false);
  showAllergyModal = signal(false);
  editingAllergyId = signal<string | null>(null);
  allergySaving = signal(false);
  allergyForm: PatientAllergyRequest = this.emptyAllergyForm();
  showAllergyDeactivate = signal(false);
  deactivatingAllergy = signal<PatientAllergy | null>(null);
  deactivateReason = '';

  readonly severities: AllergySeverity[] = [
    'MILD',
    'MODERATE',
    'SEVERE',
    'LIFE_THREATENING',
    'UNKNOWN',
  ];
  readonly verificationStatuses: AllergyVerificationStatus[] = [
    'UNCONFIRMED',
    'PROVISIONAL',
    'CONFIRMED',
    'REFUTED',
    'ENTERED_IN_ERROR',
  ];

  /* ── Problems ── */
  problems = signal<PatientProblem[]>([]);
  problemsLoading = signal(false);
  includeHistorical = signal(false);
  showProblemModal = signal(false);
  editingProblemId = signal<string | null>(null);
  problemSaving = signal(false);
  problemForm: PatientDiagnosisRequest = this.emptyProblemForm();
  showProblemDelete = signal(false);
  deletingProblem = signal<PatientProblem | null>(null);
  problemDeleteReason = '';

  readonly problemStatuses: ProblemStatus[] = ['ACTIVE', 'RESOLVED', 'INACTIVE', 'RECURRENCE'];
  readonly problemSeverities: ProblemSeverity[] = [
    'UNKNOWN',
    'MILD',
    'MODERATE',
    'SEVERE',
    'LIFE_THREATENING',
  ];

  /* ── Chart updates ── */
  updates = signal<ChartUpdate[]>([]);
  updatesLoading = signal(false);
  expandedUpdateId = signal<string | null>(null);
  showUpdateModal = signal(false);
  updateSaving = signal(false);
  updateForm: ChartUpdateRequest = this.emptyUpdateForm();

  readonly sectionTypes: ChartSectionType[] = [
    'DIAGNOSIS',
    'PROBLEM',
    'ALLERGY',
    'MEDICAL_HISTORY',
    'SURGICAL_HISTORY',
    'SOCIAL_HISTORY',
    'FAMILY_HISTORY',
    'HOSPITALIZATION',
    'IMMUNIZATION',
    'CARE_PLAN',
    'MEDICATION',
    'NOTE',
    'OTHER',
  ];

  /* ── Labs (B6) ──
   *
   * Two reads, each with its own role gate, its own loading flag and its own
   * error flag: a 403 or an outage on one of them must show as an error on
   * that block, never as an empty other block.
   */
  labResults = signal<PatientLabResult[]>([]);
  labResultsLoading = signal(false);
  labResultsError = signal(false);
  labOrders = signal<LabOrderResponse[]>([]);
  labOrdersLoading = signal(false);
  labOrdersError = signal(false);
  /**
   * The hospital scope the labs section was last read for, or null if never.
   *
   * Not a plain "have we loaded" flag: both lab reads are scoped, so a boolean
   * left one hospital's rows on screen after the scope moved. The key is
   * `hospitalId()` — the very value the results request sends — so the section
   * re-reads whenever what it asked for changes, and still refuses to
   * re-fetch a patient who simply has no labs.
   *
   * `hospitalId()` is `activeHospitalId`, the primary assignment, NOT the
   * chip's `effectiveHospitalIdForRequest`. That is the same helper the
   * allergies, problems and updates reads have always used, and the two only
   * differ for a super-admin — whom CHART_VIEW_ROLES does not admit to the
   * chart at all. Moving the whole component onto the effective id is a
   * separate change, not one to make on the labs section alone.
   */
  labsLoadedFor = signal<string | null>(null);
  /** Exposed for the "showing the latest N" hint below each lab table. */
  readonly labPageSize = LAB_PAGE_SIZE;
  /**
   * A full page is the only signal either endpoint gives that it cut the list:
   * `/patients/{id}/lab-results` returns a bare array and `LabService
   * .listOrders` drops the page's `totalElements`. Rendering the hint on a
   * list that happens to hold exactly N rows overstates it slightly; saying
   * nothing on a list that WAS cut reads as a complete history, which is the
   * failure that matters on a chart.
   */
  readonly labResultsTruncated = computed(() => this.labResults().length >= LAB_PAGE_SIZE);
  readonly labOrdersTruncated = computed(() => this.labOrders().length >= LAB_PAGE_SIZE);

  /* ── Timeline ── */
  timeline = signal<PatientTimeline | null>(null);
  timelineLoading = signal(false);
  showTimelineReason = signal(false);
  timelineReason = '';

  ngOnInit(): void {
    this.section.set(this.firstVisibleSection());
    this.loadCurrentSection();
  }

  /**
   * The first tab this role actually renders. The old two-step fallback
   * (allergies → problems → updates) could land a role on a tab it cannot
   * see, which draws an empty chart with every tab hidden; the list below is
   * the tab order in the template, so the default is always the leftmost tab
   * on screen.
   */
  private firstVisibleSection(): ChartSection {
    if (this.canViewAllergies()) return 'allergies';
    if (this.canViewProblems()) return 'problems';
    if (this.canViewUpdates()) return 'updates';
    if (this.canViewLabs()) return 'labs';
    return 'timeline';
  }

  ngOnChanges(changes: SimpleChanges): void {
    const token = changes['refreshToken'];
    if (!token || token.firstChange) return;
    this.refreshAfterAccessChange();
  }

  private hospitalId(): string {
    return this.roleContext.activeHospitalId ?? this.auth.getHospitalId() ?? '';
  }

  setSection(section: ChartSection): void {
    this.section.set(section);
    this.loadCurrentSection();
  }

  private loadCurrentSection(): void {
    switch (this.section()) {
      case 'allergies':
        if (this.canViewAllergies() && this.allergies().length === 0) this.loadAllergies();
        break;
      case 'problems':
        if (this.canViewProblems() && this.problems().length === 0) this.loadProblems();
        break;
      case 'updates':
        if (this.canViewUpdates() && this.updates().length === 0) this.loadUpdates();
        break;
      case 'labs':
        // Keyed on "which scope have we read", not on "is the list empty": a
        // patient with no labs would otherwise re-fetch both endpoints on
        // every visit, and a failed read would be retried silently instead of
        // offering Retry.
        if (this.canViewLabs() && this.labsLoadedFor() !== this.hospitalId()) this.loadLabs();
        break;
      case 'timeline':
        // Timeline requires an access reason first — prompt instead of loading.
        if (this.canViewTimeline() && !this.timeline()) this.openTimelineReason();
        break;
    }
  }

  /* ── Allergies ── */

  loadAllergies(): void {
    this.allergiesLoading.set(true);
    this.patientService.listAllergies(this.patientId, this.hospitalId() || undefined).subscribe({
      next: (list) => {
        this.allergies.set(list ?? []);
        this.allergiesLoading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('CHART.ALLERGIES_LOAD_ERROR'));
        this.allergiesLoading.set(false);
      },
    });
  }

  emptyAllergyForm(): PatientAllergyRequest {
    return {
      allergenDisplay: '',
      category: '',
      severity: 'UNKNOWN',
      verificationStatus: 'UNCONFIRMED',
      reaction: '',
      reactionNotes: '',
      onsetDate: undefined,
      active: true,
    };
  }

  openAddAllergy(): void {
    this.allergyForm = this.emptyAllergyForm();
    this.editingAllergyId.set(null);
    this.showAllergyModal.set(true);
  }

  openEditAllergy(a: PatientAllergy): void {
    this.allergyForm = {
      allergenDisplay: a.allergenDisplay,
      allergenCode: a.allergenCode,
      category: a.category ?? '',
      severity: (a.severity as AllergySeverity) ?? 'UNKNOWN',
      verificationStatus: (a.verificationStatus as AllergyVerificationStatus) ?? 'UNCONFIRMED',
      reaction: a.reaction ?? '',
      reactionNotes: a.reactionNotes ?? '',
      onsetDate: a.onsetDate ?? undefined,
      active: a.active ?? true,
    };
    this.editingAllergyId.set(a.id);
    this.showAllergyModal.set(true);
  }

  closeAllergyModal(): void {
    this.showAllergyModal.set(false);
  }

  submitAllergy(): void {
    if (!this.allergyForm.allergenDisplay.trim()) {
      this.toast.error(this.translate.instant('CHART.ALLERGEN_REQUIRED'));
      return;
    }
    this.allergySaving.set(true);
    const req: PatientAllergyRequest = {
      ...this.allergyForm,
      hospitalId: this.hospitalId() || undefined,
      onsetDate: this.allergyForm.onsetDate || undefined,
    };
    const id = this.editingAllergyId();
    const op = id
      ? this.patientService.updateAllergy(this.patientId, id, req)
      : this.patientService.addAllergy(this.patientId, req);
    op.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(id ? 'CHART.ALLERGY_UPDATED' : 'CHART.ALLERGY_ADDED'),
        );
        this.allergySaving.set(false);
        this.closeAllergyModal();
        this.loadAllergies();
      },
      error: () => {
        this.toast.error(this.translate.instant('CHART.ALLERGY_SAVE_ERROR'));
        this.allergySaving.set(false);
      },
    });
  }

  openDeactivateAllergy(a: PatientAllergy): void {
    this.deactivatingAllergy.set(a);
    this.deactivateReason = '';
    this.showAllergyDeactivate.set(true);
  }

  closeDeactivateAllergy(): void {
    this.showAllergyDeactivate.set(false);
    this.deactivatingAllergy.set(null);
  }

  submitDeactivateAllergy(): void {
    const target = this.deactivatingAllergy();
    if (!target || !this.deactivateReason.trim()) {
      this.toast.error(this.translate.instant('CHART.REASON_REQUIRED'));
      return;
    }
    this.patientService
      .deactivateAllergy(this.patientId, target.id, this.deactivateReason.trim())
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('CHART.ALLERGY_DEACTIVATED'));
          this.closeDeactivateAllergy();
          this.loadAllergies();
        },
        error: () => this.toast.error(this.translate.instant('CHART.ALLERGY_SAVE_ERROR')),
      });
  }

  /* ── Problems ── */

  loadProblems(): void {
    this.problemsLoading.set(true);
    this.patientService
      .listDiagnoses(this.patientId, {
        hospitalId: this.hospitalId() || undefined,
        includeHistorical: this.includeHistorical(),
      })
      .subscribe({
        next: (list) => {
          this.problems.set(list ?? []);
          this.problemsLoading.set(false);
        },
        error: () => {
          this.toast.error(this.translate.instant('CHART.PROBLEMS_LOAD_ERROR'));
          this.problemsLoading.set(false);
        },
      });
  }

  toggleHistorical(): void {
    this.includeHistorical.update((v) => !v);
    this.loadProblems();
  }

  emptyProblemForm(): PatientDiagnosisRequest {
    return {
      hospitalId: '',
      problemDisplay: '',
      problemCode: '',
      icdVersion: 'ICD-10',
      status: 'ACTIVE',
      severity: 'UNKNOWN',
      onsetDate: undefined,
      notes: '',
      chronic: false,
    };
  }

  openAddProblem(): void {
    this.problemForm = this.emptyProblemForm();
    this.editingProblemId.set(null);
    this.showProblemModal.set(true);
  }

  openEditProblem(p: PatientProblem): void {
    this.problemForm = {
      hospitalId: p.hospitalId ?? '',
      problemDisplay: p.problemDisplay,
      problemCode: p.problemCode ?? '',
      icdVersion: p.icdVersion ?? 'ICD-10',
      status: (p.status as ProblemStatus) ?? 'ACTIVE',
      severity: (p.severity as ProblemSeverity) ?? 'UNKNOWN',
      onsetDate: p.onsetDate ?? undefined,
      notes: p.notes ?? '',
      chronic: p.chronic ?? false,
    };
    this.editingProblemId.set(p.id);
    this.showProblemModal.set(true);
  }

  closeProblemModal(): void {
    this.showProblemModal.set(false);
  }

  submitProblem(): void {
    if (!this.problemForm.problemDisplay.trim()) {
      this.toast.error(this.translate.instant('CHART.PROBLEM_REQUIRED'));
      return;
    }
    this.problemSaving.set(true);
    const req: PatientDiagnosisRequest = {
      ...this.problemForm,
      hospitalId: this.hospitalId(),
      onsetDate: this.problemForm.onsetDate || undefined,
    };
    const id = this.editingProblemId();
    const op = id
      ? this.patientService.updateDiagnosis(this.patientId, id, req)
      : this.patientService.addDiagnosis(this.patientId, req);
    op.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(id ? 'CHART.PROBLEM_UPDATED' : 'CHART.PROBLEM_ADDED'),
        );
        this.problemSaving.set(false);
        this.closeProblemModal();
        this.loadProblems();
      },
      error: () => {
        this.toast.error(this.translate.instant('CHART.PROBLEM_SAVE_ERROR'));
        this.problemSaving.set(false);
      },
    });
  }

  openDeleteProblem(p: PatientProblem): void {
    this.deletingProblem.set(p);
    this.problemDeleteReason = '';
    this.showProblemDelete.set(true);
  }

  closeDeleteProblem(): void {
    this.showProblemDelete.set(false);
    this.deletingProblem.set(null);
  }

  submitDeleteProblem(): void {
    const target = this.deletingProblem();
    if (!target || !this.problemDeleteReason.trim()) {
      this.toast.error(this.translate.instant('CHART.REASON_REQUIRED'));
      return;
    }
    this.patientService
      .deleteDiagnosis(this.patientId, target.id, this.problemDeleteReason.trim())
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('CHART.PROBLEM_DELETED'));
          this.closeDeleteProblem();
          this.loadProblems();
        },
        error: () => this.toast.error(this.translate.instant('CHART.PROBLEM_SAVE_ERROR')),
      });
  }

  /* ── Chart updates ── */

  loadUpdates(): void {
    this.updatesLoading.set(true);
    this.patientService
      .listChartUpdates(this.patientId, { hospitalId: this.hospitalId() || undefined, size: 20 })
      .subscribe({
        next: (page) => {
          this.updates.set(page?.content ?? []);
          this.updatesLoading.set(false);
        },
        error: () => {
          this.toast.error(this.translate.instant('CHART.UPDATES_LOAD_ERROR'));
          this.updatesLoading.set(false);
        },
      });
  }

  toggleUpdate(update: ChartUpdate): void {
    this.expandedUpdateId.update((current) => (current === update.id ? null : update.id));
  }

  emptyUpdateForm(): ChartUpdateRequest {
    return { hospitalId: '', updateReason: '', summary: '', notifyCareTeam: false, sections: [] };
  }

  openCreateUpdate(): void {
    this.updateForm = this.emptyUpdateForm();
    this.showUpdateModal.set(true);
  }

  closeUpdateModal(): void {
    this.showUpdateModal.set(false);
  }

  addUpdateSection(): void {
    this.updateForm.sections!.push({ sectionType: 'NOTE', display: '', narrative: '' });
  }

  removeUpdateSection(index: number): void {
    this.updateForm.sections!.splice(index, 1);
  }

  submitUpdate(): void {
    if (!this.updateForm.updateReason.trim()) {
      this.toast.error(this.translate.instant('CHART.UPDATE_REASON_REQUIRED'));
      return;
    }
    this.updateSaving.set(true);
    this.patientService
      .createChartUpdate(this.patientId, { ...this.updateForm, hospitalId: this.hospitalId() })
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('CHART.UPDATE_CREATED'));
          this.updateSaving.set(false);
          this.closeUpdateModal();
          this.loadUpdates();
        },
        error: () => {
          this.toast.error(this.translate.instant('CHART.UPDATE_SAVE_ERROR'));
          this.updateSaving.set(false);
        },
      });
  }

  /* ── Labs (B6) ── */

  /**
   * Both lab reads, each guarded by its own role gate so a role that may read
   * one and not the other never fires a request it is certain to be refused.
   */
  loadLabs(): void {
    this.labsLoadedFor.set(this.hospitalId());
    if (this.canViewLabResults()) this.loadLabResults();
    if (this.canViewLabOrders()) this.loadLabOrders();
  }

  /**
   * Each block's Retry re-reads ONLY that block. One shared retry re-fired
   * both, so retrying a failed results read replaced the orders table the
   * clinician was reading with a spinner and re-fetched it for nothing.
   */
  loadLabResults(): void {
    this.labResultsLoading.set(true);
    this.labResultsError.set(false);
    this.patientService
      .listLabResults(this.patientId, {
        hospitalId: this.hospitalId() || undefined,
        limit: LAB_PAGE_SIZE,
      })
      .subscribe({
        next: (list) => {
          this.labResults.set(list ?? []);
          this.labResultsLoading.set(false);
        },
        error: () => {
          // An explicit error state, not an empty list: a 403 or an outage
          // rendered as "no labs" is how a released result reaches nobody.
          this.labResultsError.set(true);
          this.labResultsLoading.set(false);
        },
      });
  }

  loadLabOrders(): void {
    this.labOrdersLoading.set(true);
    this.labOrdersError.set(false);
    this.labService.listOrders({ patientId: this.patientId, size: LAB_PAGE_SIZE }).subscribe({
      next: (list) => {
        this.labOrders.set(list ?? []);
        this.labOrdersLoading.set(false);
      },
      error: () => {
        this.labOrdersError.set(true);
        this.labOrdersLoading.set(false);
      },
    });
  }

  /**
   * A row the laboratory has not released. It is rendered as pending with no
   * value and no normal/abnormal colouring: the staff path DOES return the
   * preliminary value an analyzer posted, and showing it next to released
   * rows — or worse, showing a released-looking row with a blank value — is
   * the defect that shipped once on the patient portal.
   */
  isPendingResult(result: PatientLabResult): boolean {
    return !result.released;
  }

  /** Colour class for a RELEASED row only; a pending row gets none. */
  labStatusClass(result: PatientLabResult): string {
    if (this.isPendingResult(result)) return 'lab-badge lab-pending';
    switch (result.status) {
      case 'CRITICAL':
        return 'lab-badge lab-critical';
      case 'ABNORMAL':
      case 'ABNORMAL_HIGH':
      case 'ABNORMAL_LOW':
        return 'lab-badge lab-abnormal';
      case 'NORMAL':
        return 'lab-badge lab-normal';
      default:
        return 'lab-badge';
    }
  }

  /**
   * i18n key for a result status. A pending row always reads PENDING whatever
   * grading the payload carried, so a preliminary NORMAL can never be read as
   * a released normal result.
   */
  labStatusKey(result: PatientLabResult): string {
    if (this.isPendingResult(result)) return 'CHART.LAB_STATUS_PENDING';
    switch (result.status) {
      case 'NORMAL':
      case 'ABNORMAL':
      case 'ABNORMAL_HIGH':
      case 'ABNORMAL_LOW':
      case 'CRITICAL':
      case 'PENDING':
        return 'CHART.LAB_STATUS_' + result.status;
      default:
        return 'CHART.LAB_STATUS_UNKNOWN';
    }
  }

  /* ── Timeline ── */

  openTimelineReason(): void {
    this.timelineReason = '';
    this.showTimelineReason.set(true);
  }

  closeTimelineReason(): void {
    this.showTimelineReason.set(false);
  }

  submitTimelineReason(): void {
    if (!this.timelineReason.trim()) {
      this.toast.error(this.translate.instant('CHART.REASON_REQUIRED'));
      return;
    }
    this.showTimelineReason.set(false);
    this.loadTimeline(this.timelineReason.trim());
  }

  private loadTimeline(reason: string): void {
    this.timelineLoading.set(true);
    this.patientService.getDoctorTimeline(this.patientId, reason).subscribe({
      next: (timeline) => {
        this.timeline.set(timeline);
        this.timelineLoading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('CHART.TIMELINE_LOAD_ERROR'));
        this.timelineLoading.set(false);
      },
    });
  }

  /**
   * E9 #64 — a break-the-glass session was declared or ended, so what this
   * chart may show has changed. The timeline already holds a stated reason
   * and re-reads with it; the cached sections are dropped so each re-reads
   * when next shown, and the one on screen re-reads now.
   */
  private refreshAfterAccessChange(): void {
    const reason = this.timelineReason.trim();
    if (this.timeline() && reason) this.loadTimeline(reason);
    this.allergies.set([]);
    this.problems.set([]);
    this.updates.set([]);
    this.labResults.set([]);
    this.labOrders.set([]);
    this.labsLoadedFor.set(null);
    if (this.section() !== 'timeline') this.loadCurrentSection();
  }

  timelineIcon(category: string): string {
    switch (category) {
      case 'ENCOUNTER':
        return 'stethoscope';
      case 'PRESCRIPTION':
        return 'medication';
      case 'LAB_RESULT':
        return 'science';
      case 'ALLERGY':
        return 'warning';
      default:
        return 'event_note';
    }
  }

  /**
   * E8 #50 — provenance a clinician reads without clicking.
   *
   * The hospital name is rendered for every row, local ones included: a badge
   * that appears only on foreign rows makes "no badge" ambiguous between "my
   * hospital" and "provenance missing", which is exactly the doubt this is
   * meant to remove.
   */
  entryHospital(entry: TimelineEntry): string | null {
    return entry.metadata?.sourceHospitalName ?? null;
  }

  /** Attending, prescriber, or the clinician who ORDERED the result. */
  entryClinician(entry: TimelineEntry): string | null {
    return entry.metadata?.clinician ?? null;
  }

  isForeignEntry(entry: TimelineEntry): boolean {
    return entry.metadata?.foreign === true;
  }

  /**
   * E9 #61 — provenance on the allergies, problems and updates rows, the way
   * the timeline already shows it: the hospital name on every row, and a row
   * from another hospital marked structurally. The comparison is on the id,
   * never the name; a row with no hospital id is local, not foreign.
   */
  isForeignRow(row: { hospitalId?: string }): boolean {
    const mine = this.hospitalId();
    return !!row.hospitalId && !!mine && row.hospitalId !== mine;
  }

  severityClass(severity?: string): string {
    switch (severity) {
      case 'LIFE_THREATENING':
      case 'SEVERE':
        return 'sev-badge sev-high';
      case 'MODERATE':
        return 'sev-badge sev-mid';
      case 'MILD':
        return 'sev-badge sev-low';
      default:
        return 'sev-badge';
    }
  }

  problemStatusClass(status?: string): string {
    switch (status) {
      case 'ACTIVE':
      case 'RECURRENCE':
        return 'status-badge status-active-problem';
      case 'RESOLVED':
        return 'status-badge status-resolved';
      default:
        return 'status-badge';
    }
  }
}
