import { InlineDiscardAskComponent } from '../shared/a11y/inline-discard-ask.component';
import { InlineSubFormDirective } from '../shared/a11y/inline-sub-form.directive';
import { ModalDialogDirective } from '../shared/a11y/modal-dialog.directive';
import { personName } from '../shared/pipes/person-name.pipe';
import { isOrderCancellable } from '../shared/order-lifecycle';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  MaternityService,
  MaternalHistoryRequest,
  MaternalHistoryResponse,
  PageResponse,
} from '../services/maternity.service';
import {
  ObgynReferralService,
  ObgynReferralCareContext,
  ObgynReferralCreateRequest,
  ObgynReferralMessage,
  ObgynReferralResponse,
  ObgynReferralStatus,
  ObgynReferralUrgency,
  ObgynTransferType,
  ReferralStatusSummary,
} from '../services/obgyn-referral.service';
import { PatientService, PatientResponse } from '../services/patient.service';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { labelledRefusalReason, refusalReason } from '../shared/refusal-reason';
import { PatientPickerComponent } from '../shared/patient-picker/patient-picker.component';
import {
  localInputInstant,
  nowLocalDatetime,
  withServerDateTimes,
  serverInstant,
} from '../shared/date-utils';
import { UltrasoundTabComponent } from './ultrasound-tab';
import { BirthPlanTabComponent } from './birth-plan-tab';
import { PrenatalTabComponent } from './prenatal-tab';
import { PostpartumTabComponent } from './postpartum-tab';
import { LaborTabComponent } from './labor-tab';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import {
  dueDateFromLmp,
  effectiveDueDate,
  gestationalAge,
  isDelivered,
  ongoingPregnancy,
} from './pregnancy-dates';
import { AuthorshipService } from '../shared/authorship.service';

import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
import { focusWhenRendered } from '../shared/a11y/focus-when-rendered';
/** The history form's fields the server names under `fieldErrors`. */
const HISTORY_LABELS: Record<string, string> = {
  recordedDate: 'MATERNITY.RECORDED',
  'menstrualHistory.lastMenstrualPeriod': 'MATERNITY.LMP',
  'obstetricHistory.gravida': 'MATERNITY.GRAVIDA',
  'obstetricHistory.para': 'MATERNITY.PARA',
  'obstetricHistory.termBirths': 'MATERNITY.TERM_BIRTHS',
  'obstetricHistory.pretermBirths': 'MATERNITY.PRETERM_BIRTHS',
  'obstetricHistory.abortions': 'MATERNITY.ABORTIONS',
  'obstetricHistory.livingChildren': 'MATERNITY.LIVING_CHILDREN',
  'obstetricHistory.previousCesareanSections': 'MATERNITY.CSECTIONS',
};

/** The charting-device clock allowance the server grants a clinical time (NotInClinicalFuture). */
const CLOCK_SKEW_MS = 10 * 60 * 1000;

/**
 * What is wrong with a maternal history form before it is sent, as an i18n
 * key, or null. QA D821: a record dated in the future (the server refuses it
 * beyond its clock allowance). QA D809: "G1 P3" was saved; parity counts
 * births, each of which ended a pregnancy counted in gravidity, and a
 * pregnancy ends in at most one birth or one abortion. The server refuses the
 * same, on the same fields.
 */
export function historyFormProblem(
  form: MaternalHistoryRequest,
  now: Date = new Date(),
): string | null {
  const recorded = localInputInstant(form.recordedDate);
  if (recorded && recorded.getTime() > now.getTime() + CLOCK_SKEW_MS) {
    return 'MATERNITY.RECORDED_FUTURE';
  }
  const counts = form.obstetricHistory ?? {};
  const gravida = counts.gravida;
  if (gravida === undefined || gravida === null) return null;
  const para = counts.para ?? 0;
  if (para > gravida) return 'MATERNITY.PARA_ABOVE_GRAVIDA';
  if (
    counts.abortions !== undefined &&
    counts.abortions !== null &&
    para + counts.abortions > gravida
  ) {
    return 'MATERNITY.OUTCOMES_ABOVE_GRAVIDA';
  }
  return null;
}

type BoardWorklist =
  'high-risk' | 'pending-review' | 'specialist-referral' | 'psychosocial' | 'all';
type ReferralListMode = 'hospital' | 'assigned' | 'patient';
type MaternityTab =
  'board' | 'referrals' | 'ultrasound' | 'birth-plans' | 'prenatal' | 'labor' | 'postpartum';

@Component({
  selector: 'app-maternity',
  standalone: true,
  imports: [
    InlineDiscardAskComponent,
    InlineSubFormDirective,
    SubmitGuardDirective,
    ModalDialogDirective,
    ServerTimePipe,
    CommonModule,
    FormsModule,
    TranslateModule,
    PatientPickerComponent,
    UltrasoundTabComponent,
    BirthPlanTabComponent,
    PrenatalTabComponent,
    PostpartumTabComponent,
    LaborTabComponent,
  ],
  templateUrl: './maternity.html',
  styleUrl: './maternity.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MaternityComponent implements OnInit {
  private readonly maternityService = inject(MaternityService);
  private readonly hostEl = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly authorship = inject(AuthorshipService);
  private readonly referralService = inject(ObgynReferralService);
  private readonly patientService = inject(PatientService);
  private readonly auth = inject(AuthService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  /* ── Context ── */
  private hospitalId: string | null = null;
  private patientNames = new Map<string, string>();

  /* ── Role gates (effective backend roles — permission authorities are dead) ── */
  readonly canManageHistory = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_SUPER_ADMIN',
  ]);
  /** mark-reviewed + calculate-risk are DOCTOR/SUPER_ADMIN only. */
  readonly canReview = this.roleContext.hasAnyActiveRole(['ROLE_DOCTOR', 'ROLE_SUPER_ADMIN']);
  /** pending-review + specialist-referral worklists exclude NURSE. */
  readonly canSeeRestrictedWorklists = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_MIDWIFE',
    'ROLE_SUPER_ADMIN',
  ]);
  readonly canCreateReferral = this.roleContext.hasAnyActiveRole([
    'ROLE_MIDWIFE',
    'ROLE_DOCTOR',
    'ROLE_SUPER_ADMIN',
  ]);
  readonly canDecideReferral = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_SUPER_ADMIN',
  ]);
  readonly canCancelReferral = this.roleContext.hasAnyActiveRole([
    'ROLE_MIDWIFE',
    'ROLE_SUPER_ADMIN',
  ]);
  readonly canMessage = this.canCreateReferral;
  readonly canListHospitalReferrals = this.roleContext.hasAnyActiveRole(['ROLE_SUPER_ADMIN']);
  readonly canListAssigned = this.canDecideReferral;

  /* ── Tab visibility (effective backend role lists per domain) ── */
  readonly canSeeReferrals = this.roleContext.hasAnyActiveRole([
    'ROLE_MIDWIFE',
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_SUPER_ADMIN',
  ]);
  readonly canSeeUltrasound = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_MIDWIFE',
    'ROLE_SUPER_ADMIN',
  ]);
  readonly canSeeBirthPlans = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_SUPER_ADMIN',
  ]);
  readonly canSeePrenatal = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_RECEPTIONIST',
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_SUPER_ADMIN',
  ]);
  readonly canSeePostpartum = this.canSeeBirthPlans;
  readonly canSeeLabor = this.canSeeBirthPlans;

  activeTab = signal<MaternityTab>('board');

  /* ── Board (maternal histories) ── */
  worklist = signal<BoardWorklist>('high-risk');
  histories = signal<MaternalHistoryResponse[]>([]);
  historiesLoading = signal(false);
  boardPage = signal(0);
  boardTotalPages = signal(0);
  boardTotal = signal(0);

  viewedHistory = signal<MaternalHistoryResponse | null>(null);
  historyVersions = signal<MaternalHistoryResponse[]>([]);
  historyVersionsLoading = signal(false);
  historyVersionsError = signal(false);

  showHistoryModal = signal(false);
  editingHistoryId = signal<string | null>(null);
  historySaving = signal(false);
  historyForm: MaternalHistoryRequest = this.emptyHistoryForm();
  selectedPatient = signal<PatientResponse | null>(null);
  /**
   * QA D826: the current history of the patient picked in "Nouvel historique",
   * when she already has one. The form then offers to open that record (a
   * patient has one history, versioned by update) instead of a create the
   * server refuses.
   */
  existingHistory = signal<MaternalHistoryResponse | null>(null);
  /**
   * QA D826 (#958 review): the patient's history is held by another facility.
   * The update only writes over a record of the acting hospital, so this form
   * cannot record a new version: it says so and keeps Save disabled.
   */
  historyHeldElsewhere = signal(false);
  reviewBusyId = signal<string | null>(null);

  /* ── Referrals ── */
  referralMode = signal<ReferralListMode>('patient');
  referrals = signal<ObgynReferralResponse[]>([]);
  referralsLoading = signal(false);
  referralPage = signal(0);
  referralTotalPages = signal(0);
  referralPatient = signal<PatientResponse | null>(null);
  summary = signal<ReferralStatusSummary | null>(null);

  showReferralModal = signal(false);
  referralSaving = signal(false);
  referralForm: ObgynReferralCreateRequest = this.emptyReferralForm();
  referralFormPatient = signal<PatientResponse | null>(null);

  viewedReferral = signal<ObgynReferralResponse | null>(null);
  messages = signal<ObgynReferralMessage[]>([]);
  messagesLoading = signal(false);
  messageDraft = '';
  messageSending = signal(false);

  /** acknowledge | complete share a plan-summary prompt; cancel uses reason. */
  referralAction = signal<'acknowledge' | 'complete' | 'cancel' | null>(null);
  referralActionText = '';
  referralActionUpdateCareTeam = false;
  referralActionBusy = signal(false);

  readonly worklists: BoardWorklist[] = [
    'high-risk',
    'pending-review',
    'specialist-referral',
    'psychosocial',
    'all',
  ];
  readonly careContexts: ObgynReferralCareContext[] = ['ANTENATAL', 'INTRAPARTUM', 'POSTPARTUM'];
  readonly urgencies: ObgynReferralUrgency[] = ['ROUTINE', 'PRIORITY', 'URGENT'];
  readonly transferTypes: ObgynTransferType[] = ['CONSULTATION', 'SHARED_CARE', 'TRANSFER_OF_CARE'];

  readonly visibleWorklists = computed(() =>
    this.canSeeRestrictedWorklists
      ? this.worklists
      : this.worklists.filter((w) => w !== 'pending-review' && w !== 'specialist-referral'),
  );

  ngOnInit(): void {
    this.hospitalId = this.roleContext.activeHospitalId ?? this.auth.getHospitalId();
    if (!this.canManageHistory) {
      this.activeTab.set(
        this.canSeeReferrals ? 'referrals' : this.canSeePrenatal ? 'prenatal' : 'board',
      );
    }
    this.referralMode.set(
      this.canListHospitalReferrals ? 'hospital' : this.canListAssigned ? 'assigned' : 'patient',
    );
    if (this.canManageHistory) {
      this.loadPatientNames();
      this.loadBoard();
    }
    // Referral data loads lazily on first tab activation: eager loading hit
    // a guaranteed 403 on the summary endpoint for receptionists (who only
    // see the prenatal tab) and wasted two round-trips for everyone else.
    if (this.activeTab() === 'referrals') {
      this.initReferralsTab();
    }
  }

  setTab(tab: MaternityTab): void {
    this.activeTab.set(tab);
    if (tab === 'referrals') {
      this.initReferralsTab();
    }
  }

  private referralsInitialized = false;

  private initReferralsTab(): void {
    if (this.referralsInitialized || !this.canSeeReferrals) return;
    this.referralsInitialized = true;
    this.loadSummary();
    if (this.referralMode() !== 'patient') {
      this.loadReferrals();
    }
  }

  /* ── Board ── */

  private loadPatientNames(): void {
    if (!this.hospitalId) return;
    this.patientService.list(this.hospitalId).subscribe({
      next: (patients) => {
        this.patientNames = new Map((patients ?? []).map((p) => [p.id, personName(p)]));
      },
      error: () => {
        // Names stay blank; the table says the name is unavailable.
      },
    });
  }

  /**
   * QA D1005: the due date follows the LMP (Naegele, as the server computes
   * it) unless the midwife typed her own; the gestational age is shown beside
   * it and on the list.
   */
  onLmpChange(lmp: string): void {
    const menstrual = this.historyForm.menstrualHistory ?? (this.historyForm.menstrualHistory = {});
    const previousAuto = dueDateFromLmp(menstrual.lastMenstrualPeriod);
    menstrual.lastMenstrualPeriod = lmp;
    if (!menstrual.estimatedDueDate || menstrual.estimatedDueDate === previousAuto) {
      menstrual.estimatedDueDate = dueDateFromLmp(lmp) || undefined;
    }
  }

  formGestationalAge(): { weeks: number; days: number } | null {
    return gestationalAge(effectiveDueDate(this.historyForm.menstrualHistory));
  }

  dueDateOf(history: MaternalHistoryResponse): string {
    return effectiveDueDate(history.menstrualHistory);
  }

  /** QA D1067: a delivered woman's history shows no running gestational age. */
  gestationalAgeOf(history: MaternalHistoryResponse): { weeks: number; days: number } | null {
    return ongoingPregnancy(history);
  }

  /** QA D1067: the delivery that ended this history's pregnancy, if any. */
  deliveredAtOf(history: MaternalHistoryResponse): string | null {
    return isDelivered(history) ? (history.deliveredAt ?? null) : null;
  }

  patientName(patientId: string): string {
    // QA D992 class: never a slice of the UUID; say the name is unavailable.
    return (
      this.patientNames.get(patientId) ||
      (this.translate.instant('MATERNITY.PATIENT_NAME_UNAVAILABLE') as string)
    );
  }

  setWorklist(w: BoardWorklist): void {
    this.worklist.set(w);
    this.boardPage.set(0);
    this.loadBoard();
  }

  boardPrev(): void {
    if (this.boardPage() > 0) {
      this.boardPage.update((p) => p - 1);
      this.loadBoard();
    }
  }

  boardNext(): void {
    if (this.boardPage() + 1 < this.boardTotalPages()) {
      this.boardPage.update((p) => p + 1);
      this.loadBoard();
    }
  }

  loadBoard(): void {
    if (!this.hospitalId || !this.canManageHistory) return;
    const hospitalId = this.hospitalId;
    const page = this.boardPage();
    this.historiesLoading.set(true);
    const source =
      this.worklist() === 'high-risk'
        ? this.maternityService.highRisk(hospitalId, page)
        : this.worklist() === 'pending-review'
          ? this.maternityService.pendingReview(hospitalId, page)
          : this.worklist() === 'specialist-referral'
            ? this.maternityService.specialistReferral(hospitalId, page)
            : this.worklist() === 'psychosocial'
              ? this.maternityService.psychosocialConcerns(hospitalId, page)
              : this.maternityService.search(hospitalId, { page, size: 20 });
    source.subscribe({
      next: (result) => {
        this.applyBoardPage(result);
        this.historiesLoading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('MATERNITY.BOARD_LOAD_ERROR'));
        this.historiesLoading.set(false);
      },
    });
  }

  private applyBoardPage(result: PageResponse<MaternalHistoryResponse>): void {
    this.histories.set(result?.content ?? []);
    this.boardTotalPages.set(result?.totalPages ?? 0);
    this.boardTotal.set(result?.totalElements ?? 0);
  }

  riskClass(category: string | null | undefined): string {
    switch (category) {
      case 'HIGH':
        return 'risk-badge risk-high';
      case 'MODERATE':
        return 'risk-badge risk-moderate';
      case 'LOW':
        return 'risk-badge risk-low';
      default:
        return 'risk-badge';
    }
  }

  openHistoryDetail(history: MaternalHistoryResponse): void {
    this.viewedHistory.set(history);
    this.historyVersions.set([]);
    this.historyVersionsLoading.set(true);
    this.historyVersionsError.set(false);
    this.maternityService.versionsForPatient(history.patientId).subscribe({
      next: (versions) => {
        this.historyVersions.set(versions ?? []);
        this.historyVersionsLoading.set(false);
      },
      error: () => {
        this.historyVersions.set([]);
        this.historyVersionsLoading.set(false);
        this.historyVersionsError.set(true);
      },
    });
  }

  closeHistoryDetail(): void {
    this.viewedHistory.set(null);
  }

  markReviewed(history: MaternalHistoryResponse): void {
    this.reviewBusyId.set(history.id);
    this.maternityService.markReviewed(history.id).subscribe({
      next: (updated) => {
        this.toast.success(this.translate.instant('MATERNITY.MARKED_REVIEWED'));
        this.reviewBusyId.set(null);
        this.replaceHistory(updated);
      },
      error: (err: unknown) => {
        this.toast.error(refusalReason(err) ?? this.translate.instant('MATERNITY.REVIEW_ERROR'));
        this.reviewBusyId.set(null);
      },
    });
  }

  calculateRisk(history: MaternalHistoryResponse): void {
    this.reviewBusyId.set(history.id);
    this.maternityService.calculateRisk(history.id).subscribe({
      next: (updated) => {
        this.toast.success(this.translate.instant('MATERNITY.RISK_CALCULATED'));
        this.reviewBusyId.set(null);
        this.replaceHistory(updated);
      },
      error: (err: unknown) => {
        this.toast.error(refusalReason(err) ?? this.translate.instant('MATERNITY.RISK_ERROR'));
        this.reviewBusyId.set(null);
      },
    });
  }

  private replaceHistory(updated: MaternalHistoryResponse): void {
    this.histories.update((list) => list.map((h) => (h.id === updated.id ? updated : h)));
    if (this.viewedHistory()?.id === updated.id) {
      this.viewedHistory.set(updated);
    }
  }

  /* ── History create/edit ── */

  emptyHistoryForm(): MaternalHistoryRequest {
    return {
      patientId: '',
      hospitalId: '',
      recordedDate: nowLocalDatetime(),
      updateReason: '',
      menstrualHistory: {},
      obstetricHistory: {},
      complicationsHistory: {},
      medicalHistory: {},
      lifestyleFactors: {},
      psychosocialFactors: {},
      clinicalNotes: '',
      dataComplete: false,
      requiresSpecialistReferral: false,
      specialistReferralReason: '',
    };
  }

  openCreateHistory(): void {
    this.historyForm = this.emptyHistoryForm();
    this.editingHistoryId.set(null);
    this.selectedPatient.set(null);
    this.existingHistory.set(null);
    this.historyHeldElsewhere.set(false);
    this.showHistoryModal.set(true);
  }

  openEditHistory(history: MaternalHistoryResponse): void {
    this.historyForm = {
      patientId: history.patientId,
      hospitalId: history.hospitalId,
      recordedDate: nowLocalDatetime(),
      updateReason: '',
      menstrualHistory: { ...(history.menstrualHistory ?? {}) },
      obstetricHistory: { ...(history.obstetricHistory ?? {}) },
      complicationsHistory: { ...(history.complicationsHistory ?? {}) },
      medicalHistory: { ...(history.medicalHistory ?? {}) },
      medicationsImmunizations: history.medicationsImmunizations
        ? { ...history.medicationsImmunizations }
        : undefined,
      familyHistory: history.familyHistory ? { ...history.familyHistory } : undefined,
      lifestyleFactors: { ...(history.lifestyleFactors ?? {}) },
      psychosocialFactors: { ...(history.psychosocialFactors ?? {}) },
      clinicalNotes: history.clinicalNotes ?? '',
      dataComplete: history.dataComplete ?? false,
      requiresSpecialistReferral: history.requiresSpecialistReferral ?? false,
      specialistReferralReason: history.specialistReferralReason ?? '',
    };
    this.editingHistoryId.set(history.id);
    this.existingHistory.set(null);
    this.historyHeldElsewhere.set(false);
    this.selectedPatient.set({
      id: history.patientId,
      firstName: this.patientName(history.patientId),
      lastName: '',
      email: '',
    } as PatientResponse);
    this.showHistoryModal.set(true);
  }

  closeHistoryModal(): void {
    this.showHistoryModal.set(false);
  }

  /**
   * QA D1124: why the history form refuses, shown next to "Save"; null when
   * it may be sent. The button is never silently disabled.
   */
  historyBlockReason(): string | null {
    if (!this.historyForm.patientId) return this.translate.instant('SUBMIT_GUARD.PATIENT');
    if (this.editingHistoryId()) return null;
    if (this.existingHistory()) return this.translate.instant('MATERNITY.HISTORY_ALREADY_EXISTS');
    if (this.historyHeldElsewhere())
      return this.translate.instant('MATERNITY.HISTORY_HELD_ELSEWHERE');
    return null;
  }

  onHistoryPatientPicked(p: PatientResponse | null): void {
    this.selectedPatient.set(p);
    this.historyForm.patientId = p?.id ?? '';
    this.existingHistory.set(null);
    this.historyHeldElsewhere.set(false);
    if (p && !this.editingHistoryId()) {
      this.lookUpExistingHistory(p.id);
    }
  }

  /**
   * QA D826: finds the picked patient's current history; none (404) leaves the
   * create open. Only a record of this hospital can be updated here (the server
   * refuses an update of another facility's record), so a current history held
   * elsewhere gets the "held by another facility" notice instead of an edit
   * that would fail. After a 409 the server has proved a history exists, so a
   * lookup that finds nothing readable (404) means it is held elsewhere too.
   */
  private lookUpExistingHistory(patientId: string, afterConflict = false): void {
    this.maternityService.currentForPatient(patientId).subscribe({
      next: (current) => {
        // A late answer for a patient no longer picked is dropped.
        if (this.historyForm.patientId !== patientId || this.editingHistoryId()) return;
        if (current && current.hospitalId === this.hospitalId) {
          this.existingHistory.set(current);
          if (afterConflict) {
            this.toast.error(this.translate.instant('MATERNITY.HISTORY_ALREADY_EXISTS'));
          }
        } else if (current || afterConflict) {
          this.markHistoryHeldElsewhere(afterConflict);
        }
      },
      error: (err: unknown) => {
        if (this.historyForm.patientId !== patientId || this.editingHistoryId()) return;
        if (afterConflict && (err as { status?: number } | null)?.status === 404) {
          this.markHistoryHeldElsewhere(true);
        } else if (afterConflict) {
          this.toast.error(this.translate.instant('MATERNITY.HISTORY_SAVE_ERROR'));
        }
        /* before any save, no current history: the create stays available */
      },
    });
  }

  private markHistoryHeldElsewhere(announce: boolean): void {
    this.historyHeldElsewhere.set(true);
    if (announce) {
      this.toast.error(this.translate.instant('MATERNITY.HISTORY_HELD_ELSEWHERE'));
    }
  }

  /** QA D826: from the "already has a history" notice, edit that record instead. */
  openExistingHistory(): void {
    const current = this.existingHistory();
    if (current) {
      this.openEditHistory(current);
    }
  }

  submitHistory(): void {
    if (!this.hospitalId) return;
    // QA D826: a history held by another facility cannot be written here.
    if (!this.editingHistoryId() && this.historyHeldElsewhere()) return;
    const form = this.historyForm;
    if (!form.patientId || !form.recordedDate) {
      this.toast.error(this.translate.instant('MATERNITY.HISTORY_REQUIRED_FIELDS'));
      return;
    }
    const problem = historyFormProblem(form);
    if (problem) {
      this.toast.error(this.translate.instant(problem));
      return;
    }
    form.hospitalId = this.hospitalId;
    form.recordedByStaffId = this.auth.getUserProfile()?.staffId ?? undefined;
    this.historySaving.set(true);
    const editingId = this.editingHistoryId();
    const op = editingId
      ? this.maternityService.update(editingId, withServerDateTimes(form, ['recordedDate']))
      : this.maternityService.create(withServerDateTimes(form, ['recordedDate']));
    op.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(
            editingId ? 'MATERNITY.HISTORY_UPDATED' : 'MATERNITY.HISTORY_CREATED',
          ),
        );
        this.historySaving.set(false);
        this.closeHistoryModal();
        this.loadBoard();
      },
      error: (err: unknown) => {
        this.historySaving.set(false);
        if (!editingId && (err as { status?: number } | null)?.status === 409) {
          // QA D826: she already has a history (picked before the lookup
          // answered, written meanwhile, or held by another facility): the
          // lookup decides between "open it" and "held elsewhere", and says so.
          this.lookUpExistingHistory(form.patientId, true);
          return;
        }
        this.toast.error(
          labelledRefusalReason(err, this.translate, HISTORY_LABELS) ??
            this.translate.instant('MATERNITY.HISTORY_SAVE_ERROR'),
        );
      },
    });
  }

  /* ── Referrals ── */

  setReferralMode(mode: ReferralListMode): void {
    this.referralMode.set(mode);
    this.referralPage.set(0);
    if (mode === 'patient' && !this.referralPatient()) {
      this.referrals.set([]);
      this.referralTotalPages.set(0);
      return;
    }
    this.loadReferrals();
  }

  onReferralPatientPicked(p: PatientResponse | null): void {
    this.referralPatient.set(p);
    this.referralPage.set(0);
    if (p) {
      this.loadReferrals();
    } else {
      this.referrals.set([]);
      this.referralTotalPages.set(0);
    }
  }

  referralPrev(): void {
    if (this.referralPage() > 0) {
      this.referralPage.update((p) => p - 1);
      this.loadReferrals();
    }
  }

  referralNext(): void {
    if (this.referralPage() + 1 < this.referralTotalPages()) {
      this.referralPage.update((p) => p + 1);
      this.loadReferrals();
    }
  }

  loadReferrals(): void {
    const mode = this.referralMode();
    const page = this.referralPage();
    let source;
    if (mode === 'hospital') {
      if (!this.hospitalId || !this.canListHospitalReferrals) return;
      source = this.referralService.byHospital(this.hospitalId, page);
    } else if (mode === 'assigned') {
      const userId = this.auth.getUserProfile()?.id;
      if (!userId || !this.canListAssigned) return;
      source = this.referralService.assignedTo(userId, page);
    } else {
      const patient = this.referralPatient();
      if (!patient) return;
      source = this.referralService.byPatient(patient.id, page);
    }
    this.referralsLoading.set(true);
    source.subscribe({
      next: (result) => {
        this.referrals.set(result?.content ?? []);
        this.referralTotalPages.set(result?.totalPages ?? 0);
        this.referralsLoading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('MATERNITY.REFERRALS_LOAD_ERROR'));
        this.referralsLoading.set(false);
      },
    });
  }

  loadSummary(): void {
    this.referralService.summary().subscribe({
      next: (summary) => this.summary.set(summary),
      error: () => this.summary.set(null),
    });
  }

  referralStatusClass(status: ObgynReferralStatus): string {
    switch (status) {
      case 'SUBMITTED':
        return 'status-badge status-submitted';
      case 'ACKNOWLEDGED':
        return 'status-badge status-acknowledged';
      case 'IN_PROGRESS':
        return 'status-badge status-in-progress';
      case 'COMPLETED':
        return 'status-badge status-completed';
      default:
        return 'status-badge status-cancelled';
    }
  }

  urgencyClass(urgency: ObgynReferralUrgency | undefined): string {
    switch (urgency) {
      case 'URGENT':
        return 'risk-badge risk-high';
      case 'PRIORITY':
        return 'risk-badge risk-moderate';
      default:
        return 'risk-badge risk-low';
    }
  }

  patientDisplay(referral: ObgynReferralResponse): string {
    const p = referral.patient;
    if (!p) return '—';
    return personName(p) || (p.mrn ?? p.id);
  }

  /** Mirrors the backend summary: SUBMITTED and ACKNOWLEDGED both count. */
  isOverdue(referral: ObgynReferralResponse): boolean {
    return (
      (referral.status === 'SUBMITTED' || referral.status === 'ACKNOWLEDGED') &&
      !!referral.slaDueAt &&
      serverInstant(referral.slaDueAt).getTime() < Date.now()
    );
  }

  /* ── Referral create ── */

  emptyReferralForm(): ObgynReferralCreateRequest {
    return {
      patientId: '',
      hospitalId: '',
      careContext: 'ANTENATAL',
      referralReason: '',
      clinicalIndication: '',
      urgency: 'ROUTINE',
      historySummary: '',
      ongoingMidwiferyCare: true,
      transferType: 'CONSULTATION',
      generateLetter: true,
    };
  }

  openCreateReferral(): void {
    this.referralForm = this.emptyReferralForm();
    this.referralFormPatient.set(null);
    this.showReferralModal.set(true);
  }

  closeReferralModal(): void {
    this.showReferralModal.set(false);
  }

  onReferralFormPatientPicked(p: PatientResponse | null): void {
    this.referralFormPatient.set(p);
    this.referralForm.patientId = p?.id ?? '';
  }

  submitReferral(): void {
    if (!this.hospitalId) return;
    const form = this.referralForm;
    if (!form.patientId || !form.referralReason.trim()) {
      this.toast.error(this.translate.instant('MATERNITY.REFERRAL_REQUIRED_FIELDS'));
      return;
    }
    // The backend rejects PRIORITY/URGENT referrals without at least one
    // attachment, and the create request has no attachments field — block
    // client-side with an explanation until attachment upload ships.
    if (form.urgency !== 'ROUTINE') {
      this.toast.error(this.translate.instant('MATERNITY.URGENCY_ATTACHMENT_REQUIRED'));
      return;
    }
    form.hospitalId = this.hospitalId;
    form.referralReason = form.referralReason.trim();
    this.referralSaving.set(true);
    this.referralService.create(form).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('MATERNITY.REFERRAL_CREATED'));
        this.referralSaving.set(false);
        this.closeReferralModal();
        this.loadSummary();
        this.loadReferrals();
      },
      error: (err: unknown) => {
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('MATERNITY.REFERRAL_SAVE_ERROR'),
        );
        this.referralSaving.set(false);
      },
    });
  }

  /* ── Referral detail + lifecycle ── */

  openReferral(referral: ObgynReferralResponse): void {
    this.viewedReferral.set(referral);
    this.messageDraft = '';
    this.loadMessages(referral.id);
  }

  closeReferral(): void {
    this.viewedReferral.set(null);
    this.messages.set([]);
    this.referralAction.set(null);
  }

  private loadMessages(referralId: string): void {
    this.messagesLoading.set(true);
    this.referralService.messages(referralId).subscribe({
      next: (messages) => {
        this.messages.set(messages ?? []);
        this.messagesLoading.set(false);
      },
      error: () => {
        this.messages.set([]);
        this.messagesLoading.set(false);
      },
    });
  }

  sendMessage(): void {
    const referral = this.viewedReferral();
    const body = this.messageDraft.trim();
    if (!referral || !body) return;
    this.messageSending.set(true);
    this.referralService.postMessage(referral.id, body).subscribe({
      next: (message) => {
        this.messages.update((list) => [...list, message]);
        this.messageDraft = '';
        this.messageSending.set(false);
      },
      error: (err: unknown) => {
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('MATERNITY.MESSAGE_SEND_ERROR'),
        );
        this.messageSending.set(false);
      },
    });
  }

  openReferralAction(action: 'acknowledge' | 'complete' | 'cancel'): void {
    this.referralAction.set(action);
    this.referralActionText = '';
    this.referralActionUpdateCareTeam = false;
    // QA D1144 sibling: the keyboard goes to the reason the action asks for.
    focusWhenRendered(this.hostEl.nativeElement, '#referral-action-text');
  }

  /** QA D1081: an OB/GYN referral is cancelled by the midwife who sent it. */
  canCancelThisReferral(r: ObgynReferralResponse): boolean {
    return this.canCancelReferral && isOrderCancellable(r.status) && this.authorship.isAuthor(r);
  }

  cancelReferralAction(): void {
    this.referralAction.set(null);
  }

  startReferral(): void {
    const referral = this.viewedReferral();
    if (!referral) return;
    this.referralActionBusy.set(true);
    this.referralService.start(referral.id).subscribe({
      next: (updated) => this.afterReferralAction(updated, 'MATERNITY.REFERRAL_STARTED'),
      error: (err: unknown) => this.referralActionError(err),
    });
  }

  submitReferralAction(): void {
    const referral = this.viewedReferral();
    const action = this.referralAction();
    const text = this.referralActionText.trim();
    if (!referral || !action || !text) return;
    this.referralActionBusy.set(true);
    const op =
      action === 'acknowledge'
        ? this.referralService.acknowledge(referral.id, this.auth.getUserProfile()?.id ?? '', text)
        : action === 'complete'
          ? this.referralService.complete(referral.id, text, this.referralActionUpdateCareTeam)
          : this.referralService.cancel(referral.id, text);
    const successKey =
      action === 'acknowledge'
        ? 'MATERNITY.REFERRAL_ACKNOWLEDGED'
        : action === 'complete'
          ? 'MATERNITY.REFERRAL_COMPLETED'
          : 'MATERNITY.REFERRAL_CANCELLED';
    op.subscribe({
      next: (updated) => this.afterReferralAction(updated, successKey),
      error: (err: unknown) => this.referralActionError(err),
    });
  }

  private afterReferralAction(updated: ObgynReferralResponse, successKey: string): void {
    this.toast.success(this.translate.instant(successKey));
    this.referralActionBusy.set(false);
    this.referralAction.set(null);
    this.viewedReferral.set(updated);
    this.referrals.update((list) => list.map((r) => (r.id === updated.id ? updated : r)));
    this.loadSummary();
  }

  private referralActionError(err?: unknown): void {
    // QA D930 class: a cancel refused to anyone but the referring midwife says so.
    this.toast.error(
      refusalReason(err) ?? this.translate.instant('MATERNITY.REFERRAL_ACTION_ERROR'),
    );
    this.referralActionBusy.set(false);
  }
}
