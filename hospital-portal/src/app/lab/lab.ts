import { PersonNamePipe } from '../shared/pipes/person-name.pipe';
import { textMatches, rankPeopleByName } from '../shared/person-search';
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
import { Subject } from 'rxjs';
import { debounceTime, switchMap } from 'rxjs/operators';
import {
  LabService,
  LabOrderResponse,
  LabOrderRequest,
  LabTestDefinition,
  LabTestDefinitionApprovalRequest,
  LabSpecimen,
  PerformingLab,
  LabResultResponse,
} from '../services/lab.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { PatientService, PatientResponse } from '../services/patient.service';
import {
  EncounterService,
  EncounterResponse,
  isClosedEncounterStatus,
} from '../services/encounter.service';
import { ProfileService } from '../services/profile.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { AuthService } from '../auth/auth.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { PrintLabelService } from '../services/print-label.service';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { encounterOptionLabel } from '../shared/encounter-option-label';
import { shortOrderCode } from '../shared/lab/order-code';
import { actionRoles } from '../core/action-permissions';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { PatientSublinePipe } from '../shared/patient-picker/patient-subline';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  LabOrderState,
  isLabOrderFinished,
  isLabOrderOpen,
  labOrderState,
  labOrderStateClass,
  labOrderStateKey,
} from './shared/lab-order-state';
import { labSeverityFlag, labSeverityTone, LabSeverityFlag } from './shared/lab-severity';
import { formatLabReferenceRange } from './shared/reference-range-text';
import { LabValuePipe } from './shared/lab-value.pipe';
import { ResultRangeComponent } from './shared/result-range.component';
import { LabCorrectionBadgeComponent } from './shared/lab-correction-badge.component';
import { serverDateOrInstant, serverInstant } from '../shared/date-utils';

import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
import { ModalDialogDirective } from '../shared/a11y/modal-dialog.directive';
import { refusalReason } from '../shared/refusal-reason';
import { ListEscapeDirective } from '../shared/a11y/list-escape.directive';
import { DateTimePipe } from '../shared/pipes/date-time.pipe';
/** One recorded step of a lab order's timeline in the drawer. */
export interface LabTimelineStep {
  key: string;
  at: string;
  by?: string | null;
}

@Component({
  selector: 'app-lab',
  standalone: true,
  imports: [
    DateTimePipe,
    ListEscapeDirective,
    SubmitGuardDirective,
    PersonNamePipe,
    ModalDialogDirective,
    PatientSublinePipe,
    ServerTimePipe,
    CommonModule,
    FormsModule,
    TranslateModule,
    EnumLabelPipe,
    LabValuePipe,
    ResultRangeComponent,
    LabCorrectionBadgeComponent,
    RouterLink,
  ],
  templateUrl: './lab.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './lab.scss',
})
export class LabComponent implements OnInit {
  /** QA D227: a readable order code, not the raw id. */
  readonly shortOrderCode = shortOrderCode;
  private readonly labService = inject(LabService);
  private readonly hospitalService = inject(HospitalService);
  private readonly patientService = inject(PatientService);
  private readonly activatedRoute = inject(ActivatedRoute);
  private readonly encounterService = inject(EncounterService);
  /** QA D177: the selected patient's encounters, so the order links one by name, not by a typed id. */
  readonly patientEncounters = signal<EncounterResponse[]>([]);
  private readonly toast = inject(ToastService);
  private readonly roleContext = inject(RoleContextService);
  private readonly auth = inject(AuthService);
  private readonly profileService = inject(ProfileService);
  private readonly printService = inject(PrintLabelService);
  private readonly translate = inject(TranslateService);

  /**
   * Only providers can place lab orders (matches POST /lab-orders @PreAuthorize
   * and RoleValidator.canOrderLabTests). D106: a midwife was offered the form
   * and then refused by the server, so the button is not shown to her.
   */
  readonly canCreateOrder = computed(() => this.auth.hasAnyRole(actionRoles('labOrderCreate')));
  /** QA D527: PUT /lab-orders/{id} is the ordering clinicians'; the lab bench is refused. */
  readonly canEditOrder = computed(() =>
    this.roleContext.hasAnyActiveRole(actionRoles('labOrderUpdate')),
  );
  /** QA D527: DELETE /lab-orders/{id} is an admin's only; a lab technician was offered it. */
  readonly canDeleteOrder = computed(() =>
    this.roleContext.hasAnyActiveRole(actionRoles('labOrderDelete')),
  );
  /**
   * QA D820: a COMPLETED order had no way out but an admin DELETE. The lab
   * manager or director (or a super-admin) voids it as entered in error.
   */
  readonly canVoidOrder = computed(() =>
    this.roleContext.hasAnyActiveRole(actionRoles('labOrderVoid')),
  );

  orders = signal<LabOrderResponse[]>([]);
  filtered = signal<LabOrderResponse[]>([]);
  searchTerm = '';
  loading = signal(true);
  selectedOrder = signal<LabOrderResponse | null>(null);

  activeTab = signal<'all' | 'pending' | 'completed'>('all');

  stats = signal({ total: 0, pending: 0, awaitingValidation: 0, completed: 0, cancelled: 0 });

  hospitals = signal<HospitalResponse[]>([]);
  labTestDefs = signal<LabTestDefinition[]>([]);
  /** Laboratories an order can be sent to (B1); empty until a provider opens the page. */
  performingLabs = signal<PerformingLab[]>([]);
  private activeAssignmentId = '';

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
  form: LabOrderRequest = this.emptyForm();

  // QA D820: void (entered in error)
  voidingOrder = signal<LabOrderResponse | null>(null);
  voidReason = signal('');
  voiding = signal(false);

  showDeleteConfirm = signal(false);
  deletingOrder = signal<LabOrderResponse | null>(null);
  deleting = signal(false);

  /* ── Specimens ── */
  showSpecimensModal = signal(false);
  specimensOrder = signal<LabOrderResponse | null>(null);
  specimens = signal<LabSpecimen[]>([]);
  specimensLoading = signal(false);
  specimenForm = { specimenType: '', currentLocation: '', notes: '' };
  specimenSaving = signal(false);
  receivingSpecimenId = signal<string | null>(null);
  printingSpecimenId = signal<string | null>(null);

  /** QA D962: the specimen being rejected, and the reason typed for it. */
  rejectingSpecimen = signal<LabSpecimen | null>(null);
  rejectReason = signal('');
  rejecting = signal(false);
  /**
   * Lab requests list: the hospital column is shown only when the list spans
   * more than one hospital, or when an order was routed to or from another
   * laboratory (the column carries that routing). A user at one hospital
   * would read the same name on every row otherwise.
   */
  readonly showHospitalColumn = computed(() => {
    const list = this.orders();
    if (list.some((o) => !!o.performingHospitalId)) return true;
    return new Set(list.map((o) => o.hospitalId ?? o.hospitalName ?? '')).size > 1;
  });

  /** Lab requests drawer: the open order's results, as the server graded them. */
  detailResults = signal<LabResultResponse[]>([]);
  detailResultsLoading = signal(false);
  /**
   * The results the drawer shows a value for: released (validated) and not
   * replaced by a released correction. An unreleased value is never shown here,
   * even to the laboratory: until validation the drawer says so instead.
   */
  readonly releasedDetailResults = computed(() =>
    this.detailResults().filter((r) => r.released && !r.supersededByResultId),
  );
  /** The order still waits for a result to be validated (D1743: never "finished" before it). */
  readonly detailAwaitingValidation = computed(
    () => labOrderState(this.selectedOrder()?.status) === 'AWAITING_VALIDATION',
  );
  /** The specimen the order's timeline follows: the newest one not rejected. */
  readonly currentDetailSpecimen = computed<LabSpecimen | null>(() => {
    const live = this.detailSpecimens().filter((sp) => sp.status !== 'REJECTED');
    return (
      [...live].sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? ''))[0] ?? null
    );
  });
  /**
   * The order's timeline: only the steps the backend recorded, in order, each
   * with its time and, where recorded, who. Nothing is inferred or invented.
   */
  readonly detailTimeline = computed<LabTimelineStep[]>(() => {
    const o = this.selectedOrder();
    if (!o) return [];
    const steps: LabTimelineStep[] = [];
    if (o.orderDatetime) {
      steps.push({ key: 'LAB.TIMELINE.ORDERED', at: o.orderDatetime, by: o.orderingStaffName });
    }
    const specimen = this.currentDetailSpecimen();
    if (specimen?.collectedAt) {
      steps.push({
        key: 'LAB.TIMELINE.COLLECTED',
        at: specimen.collectedAt,
        by: specimen.collectedByName,
      });
    }
    if (specimen?.receivedAt) {
      steps.push({
        key: 'LAB.TIMELINE.RECEIVED',
        at: specimen.receivedAt,
        by: specimen.receivedByName,
      });
    }
    const resultedAt = this.detailResults()
      .filter((r) => !r.supersededByResultId)
      .map((r) => r.resultDate)
      .filter((d): d is string => !!d)
      .sort()[0];
    if (resultedAt) {
      steps.push({ key: 'LAB.TIMELINE.RESULTED', at: resultedAt });
    }
    const released = this.lastRelease();
    if (released?.releasedAt) {
      steps.push({
        key: 'LAB.TIMELINE.VALIDATED',
        at: released.releasedAt,
        by: released.releasedByFullName,
      });
    }
    return steps;
  });
  /**
   * Turnaround time: from the order to the validation of its last shown
   * result, only when both are recorded. Null otherwise; never estimated.
   */
  readonly detailTurnaround = computed<string | null>(() => {
    const o = this.selectedOrder();
    const released = this.lastRelease();
    if (!o?.orderDatetime || !released?.releasedAt) return null;
    const ms =
      serverInstant(released.releasedAt).getTime() - serverInstant(o.orderDatetime).getTime();
    if (!Number.isFinite(ms) || ms < 0) return null;
    return this.formatDuration(ms);
  });

  /** QA D962: the specimens of the order open in the detail panel, for their rejection reasons. */
  detailSpecimens = signal<LabSpecimen[]>([]);
  readonly rejectedDetailSpecimens = computed(() =>
    this.detailSpecimens().filter((s) => s.status === 'REJECTED'),
  );

  /** Matches the specimen create/receive @PreAuthorize (lab staff; E9 #67: no admins). */
  readonly canManageSpecimens = this.roleContext.hasAnyActiveRole(
    actionRoles('labSpecimenCollect'),
  );

  /** Matches the specimen reject @PreAuthorize (QA D962). */
  readonly canRejectSpecimens = this.roleContext.hasAnyActiveRole(actionRoles('labSpecimenReject'));

  /**
   * QA D963: exactly the priorities the server accepts
   * (LabOrderServiceImpl.LAB_PRIORITIES). ASAP was offered and refused with a 400.
   * Labelled through the labOrderPriority enum domain, never shown raw.
   */
  readonly priorities = ['ROUTINE', 'URGENT', 'STAT'];

  /** QA D963: LabOrderChannel, labelled through the labOrderChannel enum domain. */
  readonly orderChannels = [
    'PORTAL',
    'ELECTRONIC',
    'PHONE',
    'FAX',
    'EMAIL',
    'WRITTEN',
    'WALK_IN',
    'OTHER',
  ];

  /** The newest validation among the shown results (the end of the turnaround). */
  private lastRelease(): LabResultResponse | null {
    return (
      [...this.releasedDetailResults()]
        .filter((r) => !!r.releasedAt)
        .sort((a, b) => (b.releasedAt ?? '').localeCompare(a.releasedAt ?? ''))[0] ?? null
    );
  }

  /** "2 h 05 min", "3 j 4 h", "45 min": the turnaround in the reader's language. */
  formatDuration(ms: number): string {
    const totalMinutes = Math.floor(ms / 60000);
    const days = Math.floor(totalMinutes / 1440);
    const hours = Math.floor((totalMinutes % 1440) / 60);
    const minutes = totalMinutes % 60;
    if (days > 0) return this.translate.instant('LAB.TAT_DAYS_HOURS', { days, hours });
    if (hours > 0) {
      return this.translate.instant('LAB.TAT_HOURS_MINUTES', {
        hours,
        minutes: String(minutes).padStart(2, '0'),
      });
    }
    return this.translate.instant('LAB.TAT_MINUTES', { minutes });
  }

  /**
   * The patient's age on the drawer, from the date of birth the order carries:
   * days under a month, months under two years, years after. Empty when unknown.
   */
  patientAge(dateOfBirth: string | null | undefined, today: Date = new Date()): string {
    if (!dateOfBirth) return '';
    const dob = serverDateOrInstant(dateOfBirth);
    if (Number.isNaN(dob.getTime()) || dob > today) return '';
    let months = (today.getFullYear() - dob.getFullYear()) * 12 + today.getMonth() - dob.getMonth();
    if (today.getDate() < dob.getDate()) months--;
    if (months < 1) {
      const days = Math.floor((today.getTime() - dob.getTime()) / 86400000);
      return this.translate.instant('LAB.AGE_DAYS', { count: days });
    }
    if (months < 24) return this.translate.instant('LAB.AGE_MONTHS', { count: months });
    return this.translate.instant('LAB.AGE_YEARS', { count: Math.floor(months / 12) });
  }

  /** "R35 — Polyurie" when the label is known, else the code alone. */
  diagnosisText(o: LabOrderResponse): string {
    const code = (o.primaryDiagnosisCode ?? '').trim();
    if (!code) return '—';
    const label = (o.primaryDiagnosisLabel ?? '').trim();
    return label ? `${code} — ${label}` : code;
  }

  /** The state an order status reads as (see lab-order-state.ts). */
  stateOf(status: string | null | undefined): LabOrderState | null {
    return labOrderState(status);
  }

  /** The label key of an order's state; null for a status this page does not know. */
  stateKey(status: string | null | undefined): string | null {
    const state = labOrderState(status);
    return state ? labOrderStateKey(state) : null;
  }

  /** The result's grade, as every lab screen reads it (shared lab-severity). */
  resultFlag(r: LabResultResponse): LabSeverityFlag {
    return labSeverityFlag(r);
  }

  /** The badge class of a result's grade, as the lab-results page draws it. */
  resultFlagClass(r: LabResultResponse): string {
    switch (labSeverityTone(labSeverityFlag(r))) {
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

  /**
   * The reference text beside a value: the range the server graded it against
   * (LabResultMapper.gradedReferenceRange), else, for a qualitative test, its
   * expected answer. Null when neither exists.
   */
  resultReference(r: LabResultResponse): string | null {
    const graded = r.gradedReferenceRange;
    if (graded && (graded.minValue != null || graded.maxValue != null)) {
      return formatLabReferenceRange(graded);
    }
    return r.expectedResult?.trim() || null;
  }

  /** QA D963: a STAT or URGENT order is flagged wherever the lab reads it. */
  isUrgentPriority(priority: string | null | undefined): boolean {
    return priority === 'STAT' || priority === 'URGENT';
  }

  ngOnInit(): void {
    this.loadOrders();
    this.loadAssignedHospitals();
    this.initPatientSearch();
    this.openFromQuery();
    this.labService.listTestDefinitions().subscribe((defs) => this.labTestDefs.set(defs));
    if (this.canCreateOrder()) {
      this.labService.listPerformingLabs().subscribe({
        next: (labs) => this.performingLabs.set(labs ?? []),
        error: () => this.performingLabs.set([]),
      });
    }
    this.profileService.getAssignments().subscribe({
      next: (assignments) => {
        const active = assignments.find((a) => a.active);
        if (active) this.activeAssignmentId = active.id;
      },
    });
  }

  /**
   * QA D956: `/lab?new=1&patientId=…` opens the order form on that patient.
   * The worklist's "Demander des Analyses" pointed at `/lab/new`, a route that
   * does not exist, so the wildcard sent the doctor back to the dashboard.
   */
  private openFromQuery(): void {
    const params = this.activatedRoute.snapshot.queryParamMap;
    if (params.get('new') !== '1' || !this.canCreateOrder()) return;
    this.openCreate();
    const patientId = params.get('patientId');
    if (!patientId) return;
    this.patientService.getById(patientId, this.patientSearchHospitalId()).subscribe({
      next: (patient) => {
        if (patient?.id === patientId) this.selectPatient(patient);
      },
      error: () => {
        /* the form stays open; the doctor searches for the patient */
      },
    });
  }

  emptyForm(): LabOrderRequest {
    const profile = this.auth.getUserProfile();
    return {
      patientId: '',
      hospitalId: '',
      testName: '',
      status: 'ORDERED',
      clinicalIndication: '',
      medicalNecessityNote: '',
      orderingStaffId: profile?.staffId ?? '',
      labTestDefinitionId: '',
      assignmentId: this.activeAssignmentId,
      primaryDiagnosisCode: '',
      orderChannel: 'PORTAL',
      providerSignature: '',
      documentationSharedWithLab: null,
      performingHospitalId: '',
    };
  }

  /**
   * B1: an order is "incoming" when another hospital sent it to this
   * laboratory, and "outgoing" when this hospital sent it out.
   *
   * Read through effectiveHospitalIdForRequest, the id the API is actually
   * called with — not activeHospitalId, which for a super-admin is the
   * primary hospital rather than the one the chip is pinned to. Reading the
   * wrong one inverted every label for a chip-scoped super-admin and offered
   * them Edit and Delete on incoming orders that the backend then 404s.
   */
  isIncomingExternal(o: LabOrderResponse): boolean {
    const active = this.roleContext.effectiveHospitalIdForRequest();
    return !!o.performingHospitalId && !!active && o.performingHospitalId === active;
  }

  /**
   * QA D601/D616: an order that carries results is part of the chart and the
   * API refuses to delete it (409); the icon is not offered there.
   */
  hasResultsStage(o: LabOrderResponse): boolean {
    return ['RESULTED', 'VERIFIED', 'COMPLETED'].includes(String(o.status ?? '').toUpperCase());
  }

  /**
   * QA D820: an order voided as entered in error is kept exactly as it was
   * voided; the server refuses an edit (400) and a delete (409) on it, so
   * neither is offered. Nor is either offered on an order another hospital
   * sent here, which belongs to the ordering hospital.
   */
  isEditable(o: LabOrderResponse): boolean {
    // QA D1428 class: the server refuses an edit of a resulted or cancelled order.
    const closed = ['ENTERED_IN_ERROR', 'VERIFIED', 'COMPLETED', 'CANCELLED'].includes(o.status);
    return !this.isIncomingExternal(o) && !closed;
  }

  /** As {@link isEditable}, and never on an order that carries results (QA D601/D616). */
  isDeletable(o: LabOrderResponse): boolean {
    return this.isEditable(o) && !this.hasResultsStage(o);
  }

  isSentOut(o: LabOrderResponse): boolean {
    return !!o.performingHospitalId && !this.isIncomingExternal(o);
  }

  /**
   * The hospital on this side of the relationship — the one whose worklist
   * this is. For an order another hospital sent here that is the performing
   * laboratory (us), not the hospital that ordered it.
   */
  actingHospitalName(o: LabOrderResponse): string {
    const name = this.isIncomingExternal(o) ? o.performingHospitalName : o.hospitalName;
    return name || '—';
  }

  /** The hospital on the other side: who ordered it, or where it was sent. */
  counterpartHospitalName(o: LabOrderResponse): string {
    const name = this.isIncomingExternal(o) ? o.hospitalName : o.performingHospitalName;
    return name || '—';
  }

  /** The label that side carries: "Ordered by" when it came to us, "Sent to" when it left us. */
  counterpartLabelKey(o: LabOrderResponse): string {
    return this.isIncomingExternal(o) ? 'LAB.ORDERED_BY' : 'LAB.SENT_TO';
  }

  /**
   * D48: only an ACTIVE definition may be ordered (the API refuses the rest).
   * The test an edited order already names stays listed, so the select keeps
   * showing it.
   */
  orderableTestDefs(): LabTestDefinition[] {
    const current = this.form.labTestDefinitionId;
    return this.labTestDefs().filter(
      (d) => (d.isActive && d.approvalStatus === 'ACTIVE') || (!!current && d.id === current),
    );
  }

  onTestDefChange(defId: string): void {
    const def = this.labTestDefs().find((d) => d.id === defId);
    if (def) {
      this.form.testName = def.testName;
      this.form.testCode = def.testCode;
    }
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
    this.loadPatientEncounters(p.id);
  }

  /** QA D177: the encounters the order may be linked to, newest first. */
  private loadPatientEncounters(patientId: string): void {
    this.patientEncounters.set([]);
    this.form.encounterId = '';
    const hospitalId = this.patientSearchHospitalId();
    this.encounterService.list({ patientId, hospitalId }).subscribe({
      next: (list) => {
        if (this.form.patientId !== patientId) return;
        // QA D1094: any treating clinician of the hospital orders on an OPEN
        // visit, a colleague's included; a closed visit takes no new order
        // (the server refuses it), so it is not offered.
        this.patientEncounters.set(
          [...list]
            .filter((e) => !isClosedEncounterStatus(e.status))
            .sort((a, b) => (b.encounterDate ?? '').localeCompare(a.encounterDate ?? '')),
        );
      },
      error: () => this.patientEncounters.set([]),
    });
  }

  /**
   * QA D177: the date and department of an encounter, for the picker (the
   * type is a pipe). QA D1309: and its time, so same-day visits differ.
   */
  encounterLabel(e: EncounterResponse): string {
    return encounterOptionLabel(e);
  }

  onHospitalChange(hospitalId: string): void {
    this.form.hospitalId = hospitalId;
    this.clearPatient();
    this.patientSuggestions.set([]);
    this.patientDropdownOpen.set(false);
  }

  clearPatient(): void {
    this.selectedPatient.set(null);
    this.form.patientId = '';
    this.patientQuery.set('');
    this.form.encounterId = '';
    this.patientEncounters.set([]);
  }

  patientInitials(p: PatientResponse): string {
    return ((p.firstName?.[0] ?? '') + (p.lastName?.[0] ?? '')).toUpperCase() || '?';
  }

  private patientSearchHospitalId(): string | undefined {
    // QA D912 (#967 review): the chip's hospital for a super-admin, not their home one.
    return this.form.hospitalId || this.roleContext.effectiveHospitalIdForRequest() || undefined;
  }

  openCreate(): void {
    this.form = this.emptyForm();
    this.editing.set(false);
    this.editingId.set(null);
    this.selectedPatient.set(null);
    this.patientQuery.set('');
    // Re-apply locked hospital after emptyForm() reset
    if (this.hospitalLocked) {
      const h = this.hospitals();
      if (h.length === 1) this.form.hospitalId = h[0].id;
    }
    this.showModal.set(true);
  }

  openEdit(o: LabOrderResponse): void {
    const profile = this.auth.getUserProfile();
    this.form = {
      patientId: '',
      hospitalId: '',
      testName: o.labTestName ?? '',
      testCode: o.labTestCode,
      status: o.status ?? 'ORDERED',
      clinicalIndication: o.clinicalIndication ?? '',
      medicalNecessityNote: o.medicalNecessityNote ?? '',
      notes: o.notes,
      orderingStaffId: profile?.staffId ?? '',
      labTestDefinitionId: '',
      assignmentId: this.activeAssignmentId,
      primaryDiagnosisCode: o.primaryDiagnosisCode ?? '',
      orderChannel: o.orderChannel ?? 'PORTAL',
      providerSignature: '',
      documentationSharedWithLab: null,
      performingHospitalId: o.performingHospitalId ?? '',
    };
    this.selectedPatient.set({
      id: '',
      firstName: o.patientFullName?.split(' ')[0] ?? '',
      lastName: o.patientFullName?.split(' ').slice(1).join(' ') ?? '',
      // QA D912: no MRN on the row, and QA D527 keeps the patient's email off
      // lab orders, so the chip is the name alone (no empty line under it).
    } as PatientResponse);
    this.editing.set(true);
    this.editingId.set(o.id);
    this.showModal.set(true);
  }

  closeModal(): void {
    this.showModal.set(false);
  }

  submitForm(): void {
    this.saving.set(true);
    // The select is always sent, and "" is an explicit null rather than an
    // absent field: absent tells the API to leave the routing as it is, so
    // omitting it would make "this hospital's laboratory" unreachable on an
    // order that had been sent out.
    const payload: LabOrderRequest = {
      ...this.form,
      performingHospitalId: this.form.performingHospitalId || null,
    };
    const op = this.editing()
      ? this.labService.updateOrder(this.editingId()!, payload)
      : this.labService.createOrder(payload);
    op.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(this.editing() ? 'LAB.TOAST.UPDATED' : 'LAB.TOAST.CREATED'),
        );
        this.closeModal();
        this.saving.set(false);
        this.loadOrders();
      },
      error: (err: unknown) => {
        // QA D1094: a refusal the server explains (a closed visit, another
        // patient's visit) is shown in the reader's language; only a failure
        // with no message falls back to the generic toast.
        const status = (err as { status?: number } | null)?.status ?? 0;
        const message = (err as { error?: { message?: unknown } } | null)?.error?.message;
        this.toast.error(
          status >= 400 && status < 500 && typeof message === 'string' && message.trim()
            ? message
            : this.translate.instant('LAB.TOAST.SAVE_FAILED'),
        );
        this.saving.set(false);
      },
    });
  }

  /**
   * QA D820: offered on this hospital's own orders while open or completed;
   * not on one already voided, nor on a cancelled one (already closed, and
   * the server keeps its cancel reason).
   */
  isVoidable(o: LabOrderResponse): boolean {
    return (
      !this.isIncomingExternal(o) && o.status !== 'ENTERED_IN_ERROR' && o.status !== 'CANCELLED'
    );
  }

  openVoid(o: LabOrderResponse): void {
    this.voidReason.set('');
    this.voidingOrder.set(o);
  }

  closeVoid(): void {
    this.voidingOrder.set(null);
    this.voidReason.set('');
  }

  onVoidReasonInput(event: Event): void {
    this.voidReason.set((event.target as HTMLTextAreaElement).value);
  }

  executeVoid(): void {
    const order = this.voidingOrder();
    const reason = this.voidReason().trim();
    if (!order || !reason) return;
    this.voiding.set(true);
    this.labService.voidOrder(order.id, reason).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('LAB.VOID.DONE'));
        this.voiding.set(false);
        this.closeVoid();
        this.loadOrders();
      },
      error: (err: { error?: { message?: string } }) => {
        // The server's refusal is written for the reader (localised).
        this.toast.error(err?.error?.message ?? this.translate.instant('LAB.VOID.FAILED'));
        this.voiding.set(false);
      },
    });
  }

  confirmDelete(o: LabOrderResponse): void {
    this.deletingOrder.set(o);
    this.showDeleteConfirm.set(true);
  }
  cancelDelete(): void {
    this.showDeleteConfirm.set(false);
    this.deletingOrder.set(null);
  }
  executeDelete(): void {
    this.deleting.set(true);
    this.labService.deleteOrder(this.deletingOrder()!.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('LAB.TOAST.DELETED'));
        this.cancelDelete();
        this.deleting.set(false);
        this.loadOrders();
      },
      error: (err: unknown) => {
        this.toast.error(refusalReason(err) ?? this.translate.instant('LAB.TOAST.DELETE_FAILED'));
        this.deleting.set(false);
      },
    });
  }

  loadOrders(): void {
    this.loading.set(true);
    // QA D1347: every order in scope (listOrders without a page reads every
    // page), not the 200 newest; the counters count every order the server has.
    this.labService.listOrders().subscribe({
      next: (list) => {
        this.orders.set(list);
        this.computeStats(list);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('LAB.TOAST.LOAD_FAILED'));
        this.loading.set(false);
      },
    });
  }

  /** Still owed by the laboratory, validation included ("En cours"). */
  isPending(order: LabOrderResponse): boolean {
    return isLabOrderOpen(order.status);
  }

  /** Every result validated ("Terminées"); a result awaiting validation is not. */
  isCompleted(order: LabOrderResponse): boolean {
    return isLabOrderFinished(order.status);
  }

  private computeStats(list: LabOrderResponse[]): void {
    this.stats.set({
      total: list.length,
      pending: list.filter((o) => this.isPending(o)).length,
      awaitingValidation: list.filter((o) => labOrderState(o.status) === 'AWAITING_VALIDATION')
        .length,
      completed: list.filter((o) => this.isCompleted(o)).length,
      cancelled: list.filter((o) => o.status === 'CANCELLED').length,
    });
  }

  setTab(tab: 'all' | 'pending' | 'completed'): void {
    this.activeTab.set(tab);
    this.applyFilter();
  }

  applyFilter(): void {
    let list = this.orders();
    const tab = this.activeTab();
    if (tab === 'pending') {
      list = list.filter((o) => this.isPending(o));
    } else if (tab === 'completed') {
      list = list.filter((o) => this.isCompleted(o));
    }
    const term = this.searchTerm.toLowerCase().trim();
    if (term) {
      list = list.filter(
        (o) =>
          (o.labOrderCode ?? '').toLowerCase().includes(term) ||
          textMatches(o.patientFullName, term) ||
          (o.labTestName ?? '').toLowerCase().includes(term) ||
          (o.status ?? '').toLowerCase().includes(term),
      );
    }
    this.filtered.set(list);
  }

  viewOrder(order: LabOrderResponse): void {
    this.selectedOrder.set(order);
    this.detailResults.set([]);
    // The single-order read adds what the list does not carry (the diagnosis label).
    this.labService.getOrder(order.id).subscribe({
      next: (detail) => {
        if (detail?.id === order.id && this.selectedOrder()?.id === order.id) {
          this.selectedOrder.set(detail);
        }
      },
      error: () => {
        /* the row's own fields stay shown */
      },
    });
    const state = labOrderState(order.status);
    if (state === 'AWAITING_VALIDATION' || state === 'COMPLETED') {
      this.detailResultsLoading.set(true);
      this.labService.listOrderResults(order.id).subscribe({
        next: (list) => {
          if (this.selectedOrder()?.id !== order.id) return;
          this.detailResults.set((list ?? []).filter((r) => r.labOrderId === order.id));
          this.detailResultsLoading.set(false);
        },
        error: () => {
          this.detailResults.set([]);
          this.detailResultsLoading.set(false);
        },
      });
    }
    // QA D962: a rejected specimen and its reason show on the order, so the
    // ordering doctor sees why a new sample is needed.
    this.detailSpecimens.set([]);
    this.labService.listSpecimens(order.id).subscribe({
      next: (list) => {
        if (this.selectedOrder()?.id === order.id) this.detailSpecimens.set(list ?? []);
      },
      error: () => this.detailSpecimens.set([]),
    });
  }

  closeDetail(): void {
    this.selectedOrder.set(null);
    this.detailSpecimens.set([]);
    this.detailResults.set([]);
  }

  getStatusClass(status: string): string {
    return labOrderStateClass(labOrderState(status));
  }

  // ── Lab Test Definition Approval Workflow ────────────────────────────

  showApprovalModal = signal(false);
  approvalTarget = signal<LabTestDefinition | null>(null);
  approvalAction = signal<LabTestDefinitionApprovalRequest['action'] | ''>('');
  approvalRejectionReason = signal('');
  processingApproval = signal(false);

  openApprovalModal(def: LabTestDefinition): void {
    this.approvalTarget.set(def);
    this.approvalAction.set('');
    this.approvalRejectionReason.set('');
    this.showApprovalModal.set(true);
  }

  closeApprovalModal(): void {
    this.showApprovalModal.set(false);
    this.approvalTarget.set(null);
  }

  submitApprovalAction(): void {
    const def = this.approvalTarget();
    const action = this.approvalAction();
    if (!def || !action) return;

    const req: LabTestDefinitionApprovalRequest = {
      action: action as LabTestDefinitionApprovalRequest['action'],
    };
    if (action === 'REJECT') {
      req.rejectionReason = this.approvalRejectionReason();
    }

    this.processingApproval.set(true);
    this.labService.submitApprovalAction(def.id, req).subscribe({
      next: (updated) => {
        this.toast.success(
          this.translate.instant('LAB.TOAST.DEFINITION_STATUS_UPDATED', {
            status: this.translate.instant(`LAB.APPROVAL_STATUS.${updated.approvalStatus}`),
          }),
        );
        this.labService.listTestDefinitions().subscribe((defs) => this.labTestDefs.set(defs));
        this.closeApprovalModal();
        this.processingApproval.set(false);
      },
      error: (err: unknown) => {
        this.toast.error(refusalReason(err) ?? this.translate.instant('LAB.TOAST.APPROVAL_FAILED'));
        this.processingApproval.set(false);
      },
    });
  }

  getApprovalStatusClass(status: string): string {
    switch (status) {
      case 'ACTIVE':
        return 'status-badge status-completed';
      case 'APPROVED':
        return 'status-badge status-collected';
      case 'DRAFT':
        return 'status-badge status-pending';
      case 'REJECTED':
        return 'status-badge status-cancelled';
      default:
        return 'status-badge';
    }
  }

  // ── Specimen tracking ────────────────────────────────────────────────

  openSpecimens(order: LabOrderResponse): void {
    this.specimensOrder.set(order);
    // QA D177: the test's own sample type, so the collector confirms it
    // rather than retyping it (still editable for an unusual specimen).
    this.specimenForm = {
      specimenType: this.sampleTypeOf(order),
      currentLocation: '',
      notes: '',
    };
    this.showSpecimensModal.set(true);
    this.loadSpecimens(order.id);
  }

  private sampleTypeOf(order: LabOrderResponse): string {
    const def = this.labTestDefs().find((d) => d.testCode === order.labTestCode);
    return def?.sampleType ?? '';
  }

  closeSpecimens(): void {
    this.showSpecimensModal.set(false);
    this.specimensOrder.set(null);
    this.specimens.set([]);
  }

  private loadSpecimens(orderId: string): void {
    this.specimensLoading.set(true);
    this.labService.listSpecimens(orderId).subscribe({
      next: (list) => {
        this.specimens.set(list ?? []);
        this.specimensLoading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('LAB.TOAST.SPECIMENS_LOAD_FAILED'));
        this.specimensLoading.set(false);
      },
    });
  }

  collectSpecimen(): void {
    const order = this.specimensOrder();
    if (!order || !this.specimensOrderAcceptsSpecimens() || !this.specimenForm.specimenType.trim())
      return;
    this.specimenSaving.set(true);
    this.labService
      .createSpecimen(order.id, {
        specimenType: this.specimenForm.specimenType.trim(),
        currentLocation: this.specimenForm.currentLocation.trim() || undefined,
        notes: this.specimenForm.notes.trim() || undefined,
      })
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('LAB.TOAST.SPECIMEN_COLLECTED'));
          this.specimenForm = { specimenType: '', currentLocation: '', notes: '' };
          this.specimenSaving.set(false);
          this.loadSpecimens(order.id);
        },
        error: (err: { error?: { errors?: Record<string, string>; message?: string } }) => {
          // QA D1050: the server's own reason (a field too long), in the reader's
          // language, instead of a generic failure; the form keeps what was typed.
          const fieldMessage = Object.values(err?.error?.errors ?? {})[0];
          this.toast.error(
            fieldMessage ?? this.translate.instant('LAB.TOAST.SPECIMEN_COLLECT_FAILED'),
          );
          this.specimenSaving.set(false);
        },
      });
  }

  /** Specimen label PDF (P3 #23b) — first reader of the stored barcode_value. */
  printSpecimenLabel(specimen: LabSpecimen): void {
    if (this.printingSpecimenId()) return;
    this.printingSpecimenId.set(specimen.id);
    this.printService.getSpecimenLabelPdf(specimen.id).subscribe({
      next: (blob) => {
        this.printingSpecimenId.set(null);
        this.printService.openForPrint(blob);
      },
      error: (err) => {
        this.printingSpecimenId.set(null);
        this.toast.error(err?.error?.message ?? this.translate.instant('LAB.PRINT_LABEL_FAILED'));
      },
    });
  }

  receiveSpecimen(specimen: LabSpecimen): void {
    const order = this.specimensOrder();
    if (!order) return;
    this.receivingSpecimenId.set(specimen.id);
    this.labService.receiveSpecimen(specimen.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('LAB.TOAST.SPECIMEN_RECEIVED'));
        this.receivingSpecimenId.set(null);
        this.loadSpecimens(order.id);
      },
      error: (err: unknown) => {
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('LAB.TOAST.SPECIMEN_RECEIVE_FAILED'),
        );
        this.receivingSpecimenId.set(null);
      },
    });
  }

  /**
   * QA D962: a specimen still on its way or in the laboratory can be refused,
   * while the order has no result yet (the server's
   * LabOrderLifecycle.acceptsSpecimenRejection: not voided, resulted,
   * verified or completed).
   */
  canReject(specimen: LabSpecimen): boolean {
    return (
      this.canRejectSpecimens &&
      this.specimensOrderAcceptsRejection() &&
      (specimen.status === 'COLLECTED' ||
        specimen.status === 'IN_TRANSIT' ||
        specimen.status === 'RECEIVED')
    );
  }

  openReject(specimen: LabSpecimen): void {
    this.rejectReason.set('');
    this.rejectingSpecimen.set(specimen);
  }

  closeReject(): void {
    this.rejectingSpecimen.set(null);
    this.rejectReason.set('');
  }

  onRejectReasonInput(event: Event): void {
    this.rejectReason.set((event.target as HTMLTextAreaElement).value);
  }

  executeReject(): void {
    const specimen = this.rejectingSpecimen();
    const order = this.specimensOrder();
    const reason = this.rejectReason().trim();
    if (!specimen || !order || !reason) return;
    this.rejecting.set(true);
    this.labService.rejectSpecimen(specimen.id, reason).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('LAB.REJECT_SPECIMEN.DONE'));
        this.rejecting.set(false);
        this.closeReject();
        this.loadSpecimens(order.id);
        // The order is back to awaiting collection: the list must say so.
        this.loadOrders();
      },
      error: (err: { error?: { message?: string } }) => {
        // The server's refusal is written for the reader (localised).
        this.toast.error(
          err?.error?.message ?? this.translate.instant('LAB.REJECT_SPECIMEN.FAILED'),
        );
        this.rejecting.set(false);
      },
    });
  }

  /**
   * QA D820: an order voided as entered in error takes no further specimen,
   * and a specimen already collected on it is not received (the server
   * refuses both, and a receipt would queue an analyzer work order).
   */
  /**
   * QA D1109: an order takes a new specimen only while it awaits or is in
   * analysis (LabOrderLifecycle.acceptsNewSpecimen). Once a result exists it is
   * answered (a repeat is a new order), and a cancelled or voided order takes
   * nothing; the form was offered on released orders and left dangling tubes.
   */
  private static readonly SPECIMEN_OPEN_STATUSES = new Set([
    'ORDERED',
    'PENDING',
    'COLLECTED',
    'RECEIVED',
    'IN_PROGRESS',
  ]);

  specimensOrderAcceptsSpecimens(): boolean {
    const status = this.specimensOrder()?.status;
    return !!status && LabComponent.SPECIMEN_OPEN_STATUSES.has(status);
  }

  /** Not once a result exists, nor on a cancelled or voided order (the same set). */
  specimensOrderAcceptsRejection(): boolean {
    return this.specimensOrderAcceptsSpecimens();
  }

  /** QA D1109: a tube already collected is received unless its order was cancelled or voided. */
  canReceive(specimen: LabSpecimen): boolean {
    const status = this.specimensOrder()?.status;
    return (
      status !== 'ENTERED_IN_ERROR' &&
      status !== 'CANCELLED' &&
      (specimen.status === 'COLLECTED' || specimen.status === 'IN_TRANSIT')
    );
  }

  getSpecimenStatusClass(status: string): string {
    switch (status) {
      case 'RECEIVED':
      case 'COMPLETED':
        return 'status-badge status-completed';
      case 'COLLECTED':
      case 'IN_TRANSIT':
        return 'status-badge status-collected';
      case 'PROCESSING':
        return 'status-badge status-in_progress';
      case 'REJECTED':
        return 'status-badge status-cancelled';
      default:
        return 'status-badge status-pending';
    }
  }
}
