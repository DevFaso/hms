import { InlineDiscardAskComponent } from '../shared/a11y/inline-discard-ask.component';
import { InlineSubFormDirective } from '../shared/a11y/inline-sub-form.directive';
import { ModalDialogDirective } from '../shared/a11y/modal-dialog.directive';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { CANCEL_REASON_REQUIRED_KEY, isOrderCancellable } from '../shared/order-lifecycle';
import { blankFields, refuseFields } from '../shared/forms/invalid-fields';
import { hasClinicalContent } from '../shared/forms/clinical-content';
import { focusWhenRendered } from '../shared/a11y/focus-when-rendered';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  UltrasoundService,
  UltrasoundFindingCategory,
  UltrasoundOrderRequest,
  UltrasoundOrderResponse,
  UltrasoundOrderStatus,
  UltrasoundReportRequest,
  UltrasoundReportResponse,
  UltrasoundScanType,
} from '../services/ultrasound.service';
import { PatientResponse } from '../services/patient.service';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { PatientPickerComponent } from '../shared/patient-picker/patient-picker.component';
import { localDateString } from '../shared/date-utils';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { refusalReason } from '../shared/refusal-reason';
import { AuthorshipService } from '../shared/authorship.service';

import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
type UltrasoundWorklist = 'all' | 'pending' | 'high-risk' | 'follow-up' | 'anomalies';

/** The report form: the finding stays empty until the clinician picks one (QA D1150). */
export type UltrasoundReportForm = Omit<UltrasoundReportRequest, 'findingCategory'> & {
  findingCategory: UltrasoundReportRequest['findingCategory'] | '';
};

/** The fields the report form may refuse. */
const REPORT_FIELD_IDS = ['rep-date', 'rep-category', 'rep-summary'];
/** QA D1447: the order form's fields checked before sending. */
const ORDER_CHECKED_IDS = ['us-date', 'us-scan-count'];

/** The measured or written findings of a report: at least one is required (QA D1150). */
const FINDING_FIELDS: readonly (keyof UltrasoundReportRequest)[] = [
  'fetalHeartRate',
  'gestationalAgeAtScan',
  'gestationalAgeDays',
  'numberOfFetuses',
  'estimatedFetalWeightGrams',
  'biparietalDiameterMm',
  'headCircumferenceMm',
  'abdominalCircumferenceMm',
  'femurLengthMm',
  'nuchalTranslucencyMm',
  'crownRumpLengthMm',
  'findingsSummary',
  'interpretation',
  'anomalyDescription',
  'anatomyFindings',
];

/**
 * QA D1525 (round 38): cardiac activity, movement and tone are tri-state
 * observations the clinician states — true present/normal, false
 * absent/abnormal, null "not assessed" (the default). They are shown and
 * sent; the server keeps null as null. Before, the form had no control for
 * them (QA D1182 called them UNSHOWN_DEFAULT_FLAGS and never sent them) and
 * the server defaulted every missing flag to "yes". Nothing defaults them
 * now, so a stated one — "absent" included — is a finding.
 */
export const TRI_STATE_FLAGS = [
  'fetalCardiacActivity',
  'fetalMovementObserved',
  'fetalToneNormal',
] as const;
export type TriStateFlag = (typeof TRI_STATE_FLAGS)[number];

/**
 * Literal keys in fields the i18n:referenced gate reads (labelKey, trueKey,
 * falseKey, notAssessedKey, legacyKey), so removing one fails the gate. One
 * set per observation, so French and Spanish answers agree with their noun
 * ("Mouvements fœtaux : présents", "Activité cardiaque : non évaluée").
 */
const FLAG_KEYS: Record<
  TriStateFlag,
  { labelKey: string; trueKey: string; falseKey: string; notAssessedKey: string }
> = {
  fetalCardiacActivity: {
    labelKey: 'ULTRASOUND.FLAG_CARDIAC_ACTIVITY',
    trueKey: 'ULTRASOUND.FLAG_CARDIAC_PRESENT',
    falseKey: 'ULTRASOUND.FLAG_CARDIAC_ABSENT',
    notAssessedKey: 'ULTRASOUND.FLAG_CARDIAC_NOT_ASSESSED',
  },
  fetalMovementObserved: {
    labelKey: 'ULTRASOUND.FLAG_FETAL_MOVEMENT',
    trueKey: 'ULTRASOUND.FLAG_MOVEMENT_PRESENT',
    falseKey: 'ULTRASOUND.FLAG_MOVEMENT_ABSENT',
    notAssessedKey: 'ULTRASOUND.FLAG_MOVEMENT_NOT_ASSESSED',
  },
  fetalToneNormal: {
    labelKey: 'ULTRASOUND.FLAG_FETAL_TONE',
    trueKey: 'ULTRASOUND.FLAG_TONE_NORMAL',
    falseKey: 'ULTRASOUND.FLAG_TONE_ABNORMAL',
    notAssessedKey: 'ULTRASOUND.FLAG_TONE_NOT_ASSESSED',
  },
};
/** Noun-neutral: a report saved before D1525 answered nothing. */
const LEGACY_KEYS = {
  legacyKey: 'ULTRASOUND.FLAG_NOT_STATED_LEGACY',
};
const FETUS_KEYS = {
  notAssessedKey: 'ULTRASOUND.FETUSES_NOT_COUNTED',
};

/** The tri-state answers counted as findings when stated (QA D1525). */
const STATED_ANSWERS: readonly (keyof UltrasoundReportRequest)[] = [
  ...TRI_STATE_FLAGS,
  'nasalBonePresent',
  'anomaliesDetected',
];

/**
 * Whether the report records at least one finding (QA D1150; D1525: a stated
 * observation, "absent" included, a fetus count or "no anomalies" count — the
 * D1182 exclusion only existed because those were defaults).
 */
export function hasUltrasoundFinding(
  form: UltrasoundReportForm | Partial<UltrasoundReportRequest>,
): boolean {
  const record = form as Record<string, unknown>;
  return (
    hasClinicalContent(form, FINDING_FIELDS) ||
    STATED_ANSWERS.some((answer) => typeof record[answer] === 'boolean')
  );
}

/**
 * QA D1525: NORMAL contradicts absent cardiac activity, an abnormal tone or
 * detected anomalies (the server refuses the same, on the category).
 */
export function normalContradictsObservation(
  form: UltrasoundReportForm | Partial<UltrasoundReportRequest>,
): boolean {
  return (
    form.findingCategory === 'NORMAL' &&
    (form.fetalCardiacActivity === false ||
      form.fetalToneNormal === false ||
      form.anomaliesDetected === true)
  );
}

@Component({
  selector: 'app-ultrasound-tab',
  standalone: true,
  imports: [
    InlineDiscardAskComponent,
    InlineSubFormDirective,
    SubmitGuardDirective,
    ModalDialogDirective,
    ServerTimePipe,
    EnumLabelPipe,
    CommonModule,
    FormsModule,
    TranslateModule,
    PatientPickerComponent,
  ],
  templateUrl: './ultrasound-tab.html',
  styleUrl: './maternity.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UltrasoundTabComponent implements OnInit {
  private readonly ultrasoundService = inject(UltrasoundService);
  private readonly authorship = inject(AuthorshipService);
  private readonly auth = inject(AuthService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  private hospitalId: string | null = null;

  /** Order + report writes = DOCTOR/MIDWIFE/SUPER_ADMIN (effective backend roles). */
  /** QA D1081: an ultrasound order is rewritten or cancelled by its author only. */
  isOrderAuthor(o: UltrasoundOrderResponse): boolean {
    return this.authorship.isAuthor(o);
  }

  /** QA D1098: the author cancels an order that has no report yet, on the shared rule. */
  canCancelThisOrder(o: UltrasoundOrderResponse): boolean {
    return this.canOrder && isOrderCancellable(o.status) && this.isOrderAuthor(o);
  }

  readonly canOrder = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_MIDWIFE',
    'ROLE_SUPER_ADMIN',
  ]);
  /** Report review = DOCTOR/SUPER_ADMIN. */
  readonly canReviewReport = this.roleContext.hasAnyActiveRole(['ROLE_DOCTOR', 'ROLE_SUPER_ADMIN']);

  worklist = signal<UltrasoundWorklist>('all');
  readonly worklists: UltrasoundWorklist[] = [
    'all',
    'pending',
    'high-risk',
    'follow-up',
    'anomalies',
  ];

  orders = signal<UltrasoundOrderResponse[]>([]);
  reports = signal<UltrasoundReportResponse[]>([]);
  loading = signal(false);

  /** Report worklists render a different table shape. */
  isReportList(): boolean {
    return this.worklist() === 'follow-up' || this.worklist() === 'anomalies';
  }

  readonly scanTypes: UltrasoundScanType[] = [
    'NUCHAL_TRANSLUCENCY',
    'ANATOMY_SCAN',
    'GROWTH_SCAN',
    'BIOPHYSICAL_PROFILE',
    'DOPPLER_STUDY',
    'CERVICAL_LENGTH',
    'HIGH_RISK_FOLLOW_UP',
    'OTHER',
  ];
  readonly findingCategories: UltrasoundFindingCategory[] = [
    'NORMAL',
    'VARIANT',
    'MONITORING_REQUIRED',
    'ABNORMAL',
    'CONCERNING_FOR_ANOMALY',
    'URGENT',
  ];
  readonly priorities = ['ROUTINE', 'URGENT', 'STAT'];

  /* ── Order modal ── */
  showOrderModal = signal(false);
  editingOrderId = signal<string | null>(null);
  orderSaving = signal(false);
  orderForm: UltrasoundOrderRequest = this.emptyOrderForm();
  orderPatient = signal<PatientResponse | null>(null);

  /* ── Detail / cancel ── */
  viewedOrder = signal<UltrasoundOrderResponse | null>(null);
  showCancelPrompt = signal(false);
  cancelReason = '';
  cancelBusy = signal(false);

  /* ── Report modal ── */
  showReportModal = signal(false);
  reportTargetOrder = signal<UltrasoundOrderResponse | null>(null);
  reportSaving = signal(false);
  reportForm: UltrasoundReportForm = this.emptyReportForm();
  readonly triStateFlags = TRI_STATE_FLAGS;
  reportActionBusy = signal(false);

  ngOnInit(): void {
    this.hospitalId = this.roleContext.activeHospitalId ?? this.auth.getHospitalId();
    this.load();
  }

  setWorklist(w: UltrasoundWorklist): void {
    this.worklist.set(w);
    this.load();
  }

  load(): void {
    if (!this.hospitalId) return;
    const hospitalId = this.hospitalId;
    this.loading.set(true);
    if (this.isReportList()) {
      const source =
        this.worklist() === 'follow-up'
          ? this.ultrasoundService.followUpRequired(hospitalId)
          : this.ultrasoundService.anomalies(hospitalId);
      source.subscribe({
        next: (list) => {
          this.reports.set(list ?? []);
          this.loading.set(false);
        },
        error: () => {
          this.toast.error(this.translate.instant('ULTRASOUND.LOAD_ERROR'));
          this.loading.set(false);
        },
      });
      return;
    }
    const source =
      this.worklist() === 'pending'
        ? this.ultrasoundService.pendingOrders(hospitalId)
        : this.worklist() === 'high-risk'
          ? this.ultrasoundService.highRiskOrders(hospitalId)
          : this.ultrasoundService.ordersByHospital(hospitalId);
    source.subscribe({
      next: (list) => {
        this.orders.set(list ?? []);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('ULTRASOUND.LOAD_ERROR'));
        this.loading.set(false);
      },
    });
  }

  statusClass(status: UltrasoundOrderStatus): string {
    switch (status) {
      case 'ORDERED':
        return 'status-badge status-submitted';
      case 'SCHEDULED':
        return 'status-badge status-acknowledged';
      case 'IN_PROGRESS':
        return 'status-badge status-in-progress';
      case 'COMPLETED':
      case 'REPORT_AVAILABLE':
        return 'status-badge status-completed';
      default:
        return 'status-badge status-cancelled';
    }
  }

  findingClass(category: UltrasoundFindingCategory | undefined): string {
    switch (category) {
      case 'URGENT':
      case 'CONCERNING_FOR_ANOMALY':
      case 'ABNORMAL':
        return 'risk-badge risk-high';
      case 'MONITORING_REQUIRED':
      case 'VARIANT':
        return 'risk-badge risk-moderate';
      case 'NORMAL':
        return 'risk-badge risk-low';
      default:
        return 'risk-badge';
    }
  }

  /* ── Order create/edit ── */

  emptyOrderForm(): UltrasoundOrderRequest {
    return {
      patientId: '',
      hospitalId: '',
      scanType: 'ANATOMY_SCAN',
      priority: 'ROUTINE',
      isHighRiskPregnancy: false,
    };
  }

  openCreateOrder(): void {
    this.editedOrder = null;
    this.orderForm = this.emptyOrderForm();
    this.editingOrderId.set(null);
    this.orderPatient.set(null);
    this.showOrderModal.set(true);
  }

  /** QA D1447: the stored order an edit echoes; a value it does not change is not judged. */
  private editedOrder: UltrasoundOrderResponse | null = null;

  /**
   * QA D1447: an appointment is not booked in the past. An order whose date has
   * passed keeps it when edited on its other fields, so the date stays selectable.
   */
  orderDateMin(): string {
    const today = localDateString();
    const stored = this.editedOrder?.scheduledDate;
    return stored && stored < today ? stored : today;
  }

  /** QA D1447: the field to refuse before sending, as the server would. */
  private orderProblem(): { id: string; messageKey: string } | null {
    const { scheduledDate, scanCountForPregnancy } = this.orderForm;
    const stored = this.editedOrder;
    if (
      scheduledDate &&
      scheduledDate !== stored?.scheduledDate &&
      scheduledDate < localDateString()
    ) {
      return { id: 'us-date', messageKey: 'ULTRASOUND.SCHEDULED_DATE_PAST' };
    }
    const count = scanCountForPregnancy as number | string | null | undefined;
    if (
      count !== null &&
      count !== undefined &&
      count !== '' &&
      Number(count) < 1 &&
      Number(count) !== stored?.scanCountForPregnancy
    ) {
      return { id: 'us-scan-count', messageKey: 'ULTRASOUND.SCAN_COUNT_INVALID' };
    }
    return null;
  }

  openEditOrder(order: UltrasoundOrderResponse): void {
    this.editedOrder = order;
    this.orderForm = {
      patientId: order.patientId,
      hospitalId: order.hospitalId,
      scanType: order.scanType,
      gestationalAgeAtOrder: order.gestationalAgeAtOrder ?? undefined,
      clinicalIndication: order.clinicalIndication ?? '',
      scheduledDate: order.scheduledDate ?? undefined,
      scheduledTime: order.scheduledTime ?? '',
      appointmentLocation: order.appointmentLocation ?? '',
      priority: order.priority || 'ROUTINE',
      isHighRiskPregnancy: order.isHighRiskPregnancy ?? false,
      highRiskNotes: order.highRiskNotes ?? '',
      specialInstructions: order.specialInstructions ?? '',
      scanCountForPregnancy: order.scanCountForPregnancy ?? undefined,
    };
    this.editingOrderId.set(order.id);
    this.orderPatient.set({
      id: order.patientId,
      firstName: order.patientDisplayName ?? '',
      lastName: '',
      email: '',
      mrn: order.patientMrn,
    } as PatientResponse);
    this.showOrderModal.set(true);
  }

  closeOrderModal(): void {
    this.showOrderModal.set(false);
  }

  onOrderPatientPicked(p: PatientResponse | null): void {
    this.orderPatient.set(p);
    this.orderForm.patientId = p?.id ?? '';
  }

  submitOrder(): void {
    if (!this.hospitalId || !this.orderForm.patientId) {
      // QA D1156 class: the missing patient is marked where it is asked for.
      // QA D1358: name the blank fields, not a fixed list — the field itself says
      // it is required, and with the patient chosen the refusal can only be the
      // missing hospital context.
      refuseFields(
        this.host.nativeElement,
        this.orderForm.patientId ? [] : ['us-patient'],
        ['us-patient'],
        this.translate.instant('COMMON.FIELD_REQUIRED'),
      );
      if (this.orderForm.patientId) {
        this.toast.error(this.translate.instant('COMMON.NO_HOSPITAL_ASSIGNED'));
      }
      return;
    }
    const problem = this.orderProblem();
    if (problem) {
      const message = this.translate.instant(problem.messageKey);
      refuseFields(this.host.nativeElement, [problem.id], ORDER_CHECKED_IDS, message);
      this.toast.error(message);
      return;
    }
    refuseFields(this.host.nativeElement, [], ORDER_CHECKED_IDS, '');
    this.orderForm.hospitalId = this.hospitalId;
    this.orderSaving.set(true);
    const editingId = this.editingOrderId();
    const op = editingId
      ? this.ultrasoundService.updateOrder(editingId, this.orderForm)
      : this.ultrasoundService.createOrder(this.orderForm);
    op.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(
            editingId ? 'ULTRASOUND.ORDER_UPDATED' : 'ULTRASOUND.ORDER_CREATED',
          ),
        );
        this.orderSaving.set(false);
        this.closeOrderModal();
        this.load();
      },
      error: (err: unknown) => {
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('ULTRASOUND.ORDER_SAVE_ERROR'),
        );
        this.orderSaving.set(false);
      },
    });
  }

  /* ── Detail / cancel ── */

  openOrder(order: UltrasoundOrderResponse): void {
    this.viewedOrder.set(order);
  }

  closeOrder(): void {
    this.viewedOrder.set(null);
    this.showCancelPrompt.set(false);
  }

  openCancelPrompt(): void {
    this.cancelReason = '';
    this.showCancelPrompt.set(true);
    // QA D1144: the button that opened the prompt is gone; the keyboard goes to the reason.
    focusWhenRendered(this.host.nativeElement, '#us-cancel-reason');
  }

  /** Closes the cancel prompt and gives the keyboard back to "Cancel the order". */
  closeCancelPrompt(): void {
    this.showCancelPrompt.set(false);
    focusWhenRendered(this.host.nativeElement, '[data-testid="us-cancel-order"]');
  }

  submitCancel(): void {
    const order = this.viewedOrder();
    if (!order) return;
    const reason = this.cancelReason.trim();
    if (!reason) {
      // QA D1098: every cancel states why (the server refuses a blank reason too).
      const message = this.translate.instant(CANCEL_REASON_REQUIRED_KEY);
      refuseFields(this.host.nativeElement, ['us-cancel-reason'], ['us-cancel-reason'], message);
      return;
    }
    this.cancelBusy.set(true);
    this.ultrasoundService.cancelOrder(order.id, reason).subscribe({
      next: (updated) => {
        this.toast.success(this.translate.instant('ULTRASOUND.ORDER_CANCELLED'));
        this.cancelBusy.set(false);
        this.showCancelPrompt.set(false);
        this.viewedOrder.set(updated);
        // QA D1144: a cancelled order offers no "Cancel" button to return to;
        // the keyboard lands on the dialog's Close, never on the page body.
        focusWhenRendered(this.host.nativeElement, '[data-testid="us-order-close"]');
        // Patch in place; a cancelled order leaves the pending worklist.
        this.orders.update((list) =>
          this.worklist() === 'pending'
            ? list.filter((o) => o.id !== updated.id)
            : list.map((o) => (o.id === updated.id ? updated : o)),
        );
      },
      error: (err: unknown) => {
        // QA D930 class: the server says only the ordering clinician may cancel.
        this.toast.error(refusalReason(err) ?? this.translate.instant('ULTRASOUND.CANCEL_ERROR'));
        this.cancelBusy.set(false);
      },
    });
  }

  /* ── Report ── */

  /**
   * QA D1150: the finding is never preselected. A blank report saved with the
   * seeded "NORMAL" read back as "Rapport Normal" with "Notify the patient"
   * offered; the clinician now picks the finding. QA D1525: the three
   * observations start at "not assessed".
   */
  /** The label key of one observation (literal keys, QA D1525). */
  flagLabel(flag: TriStateFlag): string {
    return FLAG_KEYS[flag].labelKey;
  }

  /** The option key for a stated answer of one observation. */
  flagAnswer(flag: TriStateFlag, answer: boolean): string {
    return answer ? FLAG_KEYS[flag].trueKey : FLAG_KEYS[flag].falseKey;
  }

  /**
   * How a stored report reads one observation (QA D1525): a report saved
   * before D1525 says "not stated (legacy)" whatever its default value.
   */
  observationKey(report: UltrasoundReportResponse, flag: TriStateFlag): string {
    if (!report.observationsStated) return LEGACY_KEYS.legacyKey;
    const answer = report[flag];
    if (answer === true || answer === false) return this.flagAnswer(flag, answer);
    return FLAG_KEYS[flag].notAssessedKey;
  }

  /** The not-assessed option of one observation, worded for its noun. */
  flagNotAssessed(flag: TriStateFlag): string {
    return FLAG_KEYS[flag].notAssessedKey;
  }

  /**
   * The stored fetus count as read (QA D1525): a number, "not counted" when
   * null, and "no answer (legacy)" for the default 1 of a pre-D1525 report.
   */
  fetusCount(report: UltrasoundReportResponse): { count?: number; key?: string } {
    const count = report.numberOfFetuses;
    if (!report.observationsStated && (count === 1 || count == null))
      return { key: LEGACY_KEYS.legacyKey };
    if (count == null) return { key: FETUS_KEYS.notAssessedKey };
    return { count };
  }

  emptyReportForm(): UltrasoundReportForm {
    return {
      scanDate: localDateString(),
      findingCategory: '',
      fetalCardiacActivity: null,
      fetalMovementObserved: null,
      fetalToneNormal: null,
      anomaliesDetected: null,
      reportFinalized: false,
    };
  }

  openReport(order: UltrasoundOrderResponse): void {
    this.reportTargetOrder.set(order);
    const existing = order.report;
    if (existing) {
      // Strip the response-only bookkeeping fields before reusing as a request.
      const {
        id: _id,
        ultrasoundOrderId: _orderId,
        reportFinalizedAt: _finalizedAt,
        reportFinalizedBy: _finalizedBy,
        reportReviewedByProvider: _reviewed,
        patientNotified: _notified,
        patientNotifiedAt: _notifiedAt,
        createdAt: _createdAt,
        updatedAt: _updatedAt,
        providerReviewNotes,
        observationsStated: _stated,
        ...fields
      } = existing;
      this.reportForm = {
        ...fields,
        scanDate: existing.scanDate ?? localDateString(),
        findingCategory: existing.findingCategory ?? '',
        // QA D1525: a report saved before D1525 carries server defaults
        // (cardiac activity, movement, tone "yes", "no anomalies", one fetus)
        // nobody stated. They load as "not assessed" so a save never re-sends
        // a default as the clinician's statement.
        ...(existing.observationsStated
          ? {
              fetalCardiacActivity: existing.fetalCardiacActivity ?? null,
              fetalMovementObserved: existing.fetalMovementObserved ?? null,
              fetalToneNormal: existing.fetalToneNormal ?? null,
              anomaliesDetected: existing.anomaliesDetected ?? null,
            }
          : {
              fetalCardiacActivity: null,
              fetalMovementObserved: null,
              fetalToneNormal: null,
              anomaliesDetected: existing.anomaliesDetected === true ? true : null,
              numberOfFetuses:
                existing.numberOfFetuses === 1
                  ? undefined
                  : (existing.numberOfFetuses ?? undefined),
            }),
        reportFinalized: false,
        providerReviewNotes: providerReviewNotes ?? undefined,
      };
    } else {
      this.reportForm = this.emptyReportForm();
    }
    this.showReportModal.set(true);
  }

  closeReportModal(): void {
    this.showReportModal.set(false);
    this.reportTargetOrder.set(null);
  }

  /*
   * QA D1525: the nuchal-translucency and anatomy-scan templates are gone. They
   * pre-filled findings; without them they carried only today's date, which
   * overwrote a stored scan date.
   */

  submitReport(): void {
    const order = this.reportTargetOrder();
    if (!order) return;
    const form = this.reportForm;
    // QA D1525: a report that states nothing is refused first, as the server does, before the date and the category.
    if (!hasUltrasoundFinding(form)) {
      refuseFields(
        this.host.nativeElement,
        ['rep-summary'],
        REPORT_FIELD_IDS,
        this.translate.instant('ULTRASOUND.REPORT_NO_FINDINGS'),
      );
      return;
    }
    // QA D1150: the date and the finding are named where they are missing —
    // a report must never read as "Normal" by default.
    const missing = blankFields(
      { 'rep-date': form.scanDate, 'rep-category': form.findingCategory },
      ['rep-date', 'rep-category'],
    );
    if (missing.length > 0) {
      // QA D1358: name the blank fields, not a fixed list — each blank field
      // says it is required, not the sentence about both.
      refuseFields(
        this.host.nativeElement,
        missing,
        REPORT_FIELD_IDS,
        this.translate.instant('COMMON.FIELD_REQUIRED'),
      );
      return;
    }
    if (normalContradictsObservation(form)) {
      refuseFields(
        this.host.nativeElement,
        ['rep-category'],
        REPORT_FIELD_IDS,
        this.translate.instant('ULTRASOUND.NORMAL_CONTRADICTS_OBSERVATION'),
      );
      return;
    }
    refuseFields(this.host.nativeElement, [], REPORT_FIELD_IDS, '');
    // QA D1525: the three observations are sent as stated (null = not
    // assessed); the server no longer turns a missing one into "yes".
    const request = { ...form } as Partial<UltrasoundReportRequest>;
    this.reportSaving.set(true);
    this.ultrasoundService.submitReport(order.id, request as UltrasoundReportRequest).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('ULTRASOUND.REPORT_SAVED'));
        this.reportSaving.set(false);
        this.closeReportModal();
        this.load();
      },
      error: (err: unknown) => {
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('ULTRASOUND.REPORT_SAVE_ERROR'),
        );
        this.reportSaving.set(false);
      },
    });
  }

  reviewReport(report: UltrasoundReportResponse): void {
    this.reportActionBusy.set(true);
    this.ultrasoundService.reviewReport(report.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('ULTRASOUND.REPORT_REVIEWED'));
        this.reportActionBusy.set(false);
        this.load();
        this.closeOrder();
      },
      error: (err: unknown) => {
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('ULTRASOUND.REPORT_ACTION_ERROR'),
        );
        this.reportActionBusy.set(false);
      },
    });
  }

  notifyPatient(report: UltrasoundReportResponse): void {
    this.reportActionBusy.set(true);
    this.ultrasoundService.notifyPatient(report.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('ULTRASOUND.PATIENT_NOTIFIED'));
        this.reportActionBusy.set(false);
        this.load();
        this.closeOrder();
      },
      error: (err: unknown) => {
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('ULTRASOUND.REPORT_ACTION_ERROR'),
        );
        this.reportActionBusy.set(false);
      },
    });
  }
}
