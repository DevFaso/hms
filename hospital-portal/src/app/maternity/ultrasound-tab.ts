import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
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

type UltrasoundWorklist = 'all' | 'pending' | 'high-risk' | 'follow-up' | 'anomalies';

/** The report form: the finding stays empty until the clinician picks one (QA D1525). */
export type UltrasoundReportForm = Omit<UltrasoundReportRequest, 'findingCategory'> & {
  findingCategory: UltrasoundReportRequest['findingCategory'] | '';
};

/**
 * The measured or written findings of a report: at least one is required
 * (QA D1525, the server's UltrasoundReportFindings). The yes/no flags are not
 * findings on their own, and one fetus is not (it was a default); twins are.
 */
const FINDING_FIELDS: readonly (keyof UltrasoundReportRequest)[] = [
  'gestationalAgeAtScan',
  'gestationalAgeDays',
  'nuchalTranslucencyMm',
  'crownRumpLengthMm',
  'estimatedDueDate',
  'fetalPosition',
  'biparietalDiameterMm',
  'headCircumferenceMm',
  'abdominalCircumferenceMm',
  'femurLengthMm',
  'estimatedFetalWeightGrams',
  'placentalLocation',
  'placentalGrade',
  'amnioticFluidIndex',
  'amnioticFluidLevel',
  'cervicalLengthMm',
  'umbilicalArteryDoppler',
  'uterineArteryDoppler',
  'fetalHeartRate',
  'anatomyFindings',
  'findingsSummary',
  'interpretation',
  'anomalyDescription',
];

/** The tri-state observations (QA D1525): null is "not assessed", the default. */
export const TRI_STATE_FLAGS = [
  'fetalCardiacActivity',
  'fetalMovementObserved',
  'fetalToneNormal',
] as const;

/** Whether the clinician stated at least one finding (QA D1525). */
export function hasUltrasoundFinding(
  form: Partial<UltrasoundReportRequest> | UltrasoundReportForm,
): boolean {
  const record = form as Record<string, unknown>;
  const fetuses = form.numberOfFetuses;
  if (typeof fetuses === 'number' && fetuses > 1) return true;
  return FINDING_FIELDS.some((field) => {
    const value = record[field];
    if (value === undefined || value === null) return false;
    if (typeof value === 'string') return value.trim() !== '';
    return true;
  });
}

@Component({
  selector: 'app-ultrasound-tab',
  standalone: true,
  imports: [ServerTimePipe, CommonModule, FormsModule, TranslateModule, PatientPickerComponent],
  templateUrl: './ultrasound-tab.html',
  styleUrl: './maternity.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UltrasoundTabComponent implements OnInit {
  private readonly ultrasoundService = inject(UltrasoundService);
  private readonly auth = inject(AuthService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  private hospitalId: string | null = null;

  /** Order + report writes = DOCTOR/MIDWIFE/SUPER_ADMIN (effective backend roles). */
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
  templateLoading = signal(false);
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
    this.orderForm = this.emptyOrderForm();
    this.editingOrderId.set(null);
    this.orderPatient.set(null);
    this.showOrderModal.set(true);
  }

  openEditOrder(order: UltrasoundOrderResponse): void {
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
      this.toast.error(this.translate.instant('ULTRASOUND.ORDER_REQUIRED_FIELDS'));
      return;
    }
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
      error: () => {
        this.toast.error(this.translate.instant('ULTRASOUND.ORDER_SAVE_ERROR'));
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
  }

  submitCancel(): void {
    const order = this.viewedOrder();
    if (!order) return;
    this.cancelBusy.set(true);
    this.ultrasoundService.cancelOrder(order.id, this.cancelReason.trim() || undefined).subscribe({
      next: (updated) => {
        this.toast.success(this.translate.instant('ULTRASOUND.ORDER_CANCELLED'));
        this.cancelBusy.set(false);
        this.showCancelPrompt.set(false);
        this.viewedOrder.set(updated);
        // Patch in place; a cancelled order leaves the pending worklist.
        this.orders.update((list) =>
          this.worklist() === 'pending'
            ? list.filter((o) => o.id !== updated.id)
            : list.map((o) => (o.id === updated.id ? updated : o)),
        );
      },
      error: () => {
        this.toast.error(this.translate.instant('ULTRASOUND.CANCEL_ERROR'));
        this.cancelBusy.set(false);
      },
    });
  }

  /* ── Report ── */

  /**
   * QA D1525: nothing clinical is preselected. The finding category used to
   * start at NORMAL, and the three observations were left for the server to
   * default to "present"; the clinician now states each one.
   */
  emptyReportForm(): UltrasoundReportForm {
    return {
      scanDate: localDateString(),
      findingCategory: '',
      fetalCardiacActivity: null,
      fetalMovementObserved: null,
      fetalToneNormal: null,
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
        ...fields
      } = existing;
      this.reportForm = {
        ...fields,
        scanDate: existing.scanDate ?? localDateString(),
        findingCategory: existing.findingCategory ?? '',
        fetalCardiacActivity: existing.fetalCardiacActivity ?? null,
        fetalMovementObserved: existing.fetalMovementObserved ?? null,
        fetalToneNormal: existing.fetalToneNormal ?? null,
        reportFinalized: false,
        providerReviewNotes: providerReviewNotes ?? undefined,
      };
    } else {
      this.reportForm = this.emptyReportForm();
    }
    // Snapshot the seeded values so applyTemplate can tell defaults apart
    // from genuine clinician entries.
    this.reportSeed = { ...this.reportForm };
    this.reportBaseline = { ...this.reportForm };
    this.showReportModal.set(true);
  }

  closeReportModal(): void {
    this.showReportModal.set(false);
    this.reportTargetOrder.set(null);
  }

  /** Form values as seeded by openReport — used to distinguish defaults. */
  private reportSeed: UltrasoundReportForm = this.emptyReportForm();
  /** Form values after the last template application (or the seed). */
  private reportBaseline: UltrasoundReportForm = this.emptyReportForm();

  applyTemplate(kind: 'nuchal-translucency' | 'anatomy-scan'): void {
    this.templateLoading.set(true);
    this.ultrasoundService.template(kind).subscribe({
      next: (template) => {
        // Clinician entries (fields changed since the last template/seed)
        // win over the template; the template wins over untouched seeded
        // defaults; fields a previous template filled but this one doesn't
        // fall back to the seed — so switching templates actually switches.
        const base = this.reportBaseline as unknown as Record<string, unknown>;
        const userChanged: Record<string, unknown> = {};
        for (const [key, value] of Object.entries(this.reportForm)) {
          if (value !== base[key] && value !== undefined && value !== null && value !== '') {
            userChanged[key] = value;
          }
        }
        this.reportForm = {
          ...this.reportSeed,
          ...this.stripEmpty(template as UltrasoundReportRequest),
          ...userChanged,
        } as UltrasoundReportForm;
        this.reportBaseline = { ...this.reportForm };
        this.templateLoading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('ULTRASOUND.TEMPLATE_ERROR'));
        this.templateLoading.set(false);
      },
    });
  }

  private stripEmpty(form: UltrasoundReportRequest): Partial<UltrasoundReportRequest> {
    const result: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(form)) {
      if (value !== undefined && value !== null && value !== '') result[key] = value;
    }
    return result as Partial<UltrasoundReportRequest>;
  }

  submitReport(): void {
    const order = this.reportTargetOrder();
    if (!order) return;
    const form = this.reportForm;
    // QA D1525: an untouched template is an empty report — refused before the
    // category is asked for, as the server does.
    if (!hasUltrasoundFinding(form)) {
      this.toast.error(this.translate.instant('ULTRASOUND.REPORT_NO_FINDINGS'));
      return;
    }
    if (!form.scanDate || !form.findingCategory) {
      this.toast.error(this.translate.instant('ULTRASOUND.REPORT_REQUIRED_FIELDS'));
      return;
    }
    this.reportSaving.set(true);
    this.ultrasoundService.submitReport(order.id, form as UltrasoundReportRequest).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('ULTRASOUND.REPORT_SAVED'));
        this.reportSaving.set(false);
        this.closeReportModal();
        this.load();
      },
      error: () => {
        this.toast.error(this.translate.instant('ULTRASOUND.REPORT_SAVE_ERROR'));
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
      error: () => {
        this.toast.error(this.translate.instant('ULTRASOUND.REPORT_ACTION_ERROR'));
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
      error: () => {
        this.toast.error(this.translate.instant('ULTRASOUND.REPORT_ACTION_ERROR'));
        this.reportActionBusy.set(false);
      },
    });
  }
}
