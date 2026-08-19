import {
  ChangeDetectionStrategy,
  Component,
  computed,
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
import { PatientPickerComponent } from '../shared/patient-picker/patient-picker.component';
import { nowLocalDatetime } from '../shared/date-utils';
import { UltrasoundTabComponent } from './ultrasound-tab';
import { BirthPlanTabComponent } from './birth-plan-tab';
import { PrenatalTabComponent } from './prenatal-tab';
import { PostpartumTabComponent } from './postpartum-tab';

type BoardWorklist =
  | 'high-risk'
  | 'pending-review'
  | 'specialist-referral'
  | 'psychosocial'
  | 'all';
type ReferralListMode = 'hospital' | 'assigned' | 'patient';
type MaternityTab =
  | 'board'
  | 'referrals'
  | 'ultrasound'
  | 'birth-plans'
  | 'prenatal'
  | 'postpartum';

@Component({
  selector: 'app-maternity',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TranslateModule,
    PatientPickerComponent,
    UltrasoundTabComponent,
    BirthPlanTabComponent,
    PrenatalTabComponent,
    PostpartumTabComponent,
  ],
  templateUrl: './maternity.html',
  styleUrl: './maternity.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MaternityComponent implements OnInit {
  private readonly maternityService = inject(MaternityService);
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
  readonly canListHospitalReferrals = this.roleContext.hasAnyActiveRole([
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_SUPER_ADMIN',
  ]);
  readonly canListAssigned = this.canDecideReferral;

  /* ── Tab visibility (effective backend role lists per domain) ── */
  readonly canSeeReferrals = this.roleContext.hasAnyActiveRole([
    'ROLE_MIDWIFE',
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_SUPER_ADMIN',
  ]);
  readonly canSeeUltrasound = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_MIDWIFE',
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_SUPER_ADMIN',
  ]);
  readonly canSeeBirthPlans = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_HOSPITAL_ADMIN',
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
        this.patientNames = new Map(
          (patients ?? []).map((p) => [p.id, `${p.firstName ?? ''} ${p.lastName ?? ''}`.trim()]),
        );
      },
      error: () => {
        // Names stay blank; the table falls back to the MRN-less id prefix.
      },
    });
  }

  patientName(patientId: string): string {
    return this.patientNames.get(patientId) || `${patientId.substring(0, 8)}…`;
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
      error: () => {
        this.toast.error(this.translate.instant('MATERNITY.REVIEW_ERROR'));
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
      error: () => {
        this.toast.error(this.translate.instant('MATERNITY.RISK_ERROR'));
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

  onHistoryPatientPicked(p: PatientResponse | null): void {
    this.selectedPatient.set(p);
    this.historyForm.patientId = p?.id ?? '';
  }

  submitHistory(): void {
    if (!this.hospitalId) return;
    const form = this.historyForm;
    if (!form.patientId || !form.recordedDate) {
      this.toast.error(this.translate.instant('MATERNITY.HISTORY_REQUIRED_FIELDS'));
      return;
    }
    form.hospitalId = this.hospitalId;
    form.recordedByStaffId = this.auth.getUserProfile()?.staffId ?? undefined;
    this.historySaving.set(true);
    const editingId = this.editingHistoryId();
    const op = editingId
      ? this.maternityService.update(editingId, form)
      : this.maternityService.create(form);
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
      error: () => {
        this.toast.error(this.translate.instant('MATERNITY.HISTORY_SAVE_ERROR'));
        this.historySaving.set(false);
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
    return `${p.firstName ?? ''} ${p.lastName ?? ''}`.trim() || (p.mrn ?? p.id);
  }

  /** Mirrors the backend summary: SUBMITTED and ACKNOWLEDGED both count. */
  isOverdue(referral: ObgynReferralResponse): boolean {
    return (
      (referral.status === 'SUBMITTED' || referral.status === 'ACKNOWLEDGED') &&
      !!referral.slaDueAt &&
      new Date(referral.slaDueAt).getTime() < Date.now()
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
      error: () => {
        this.toast.error(this.translate.instant('MATERNITY.REFERRAL_SAVE_ERROR'));
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
      error: () => {
        this.toast.error(this.translate.instant('MATERNITY.MESSAGE_SEND_ERROR'));
        this.messageSending.set(false);
      },
    });
  }

  openReferralAction(action: 'acknowledge' | 'complete' | 'cancel'): void {
    this.referralAction.set(action);
    this.referralActionText = '';
    this.referralActionUpdateCareTeam = false;
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
      error: () => this.referralActionError(),
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
      error: () => this.referralActionError(),
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

  private referralActionError(): void {
    this.toast.error(this.translate.instant('MATERNITY.REFERRAL_ACTION_ERROR'));
    this.referralActionBusy.set(false);
  }
}
