import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  ImagingService,
  ImagingOrderResponse,
  ImagingOrderRequest,
  ImagingModality,
  ImagingPriority,
  ImagingLaterality,
  ImagingReportResponse,
  ImagingReportStatus,
  ImagingReportAuthorRequest,
} from '../services/imaging.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { PatientService, PatientResponse } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { AuthService } from '../auth/auth.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { PatientPickerComponent } from '../shared/patient-picker/patient-picker.component';

type ImagingForm = Omit<ImagingOrderRequest, 'laterality'> & {
  laterality?: ImagingLaterality | '';
};

/** The statuses a radiologist may assert while authoring. */
type AuthorableReportStatus = Extract<
  ImagingReportStatus,
  'DRAFT' | 'PRELIMINARY' | 'ADDENDUM' | 'CORRECTED' | 'AMENDED'
>;

/** The administrative outcomes the void modal may write. */
type VoidReportStatus = Extract<ImagingReportStatus, 'CANCELLED' | 'ERROR'>;

/**
 * Bound to the authoring form. Strings rather than optionals throughout so
 * `[(ngModel)]` never sees undefined; they are trimmed to undefined on submit.
 */
interface ReportForm {
  reportTitle: string;
  modality: ImagingModality;
  bodyRegion: string;
  accessionNumber: string;
  technique: string;
  comparisonStudies: string;
  findings: string;
  impression: string;
  recommendations: string;
  contrastAdministered: boolean;
  contrastDetails: string;
  criticalFinding: boolean;
  reportStatus: AuthorableReportStatus;
}

@Component({
  selector: 'app-imaging',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, EnumLabelPipe, PatientPickerComponent],
  templateUrl: './imaging.html',
  styleUrl: './imaging.scss',
})
export class ImagingComponent implements OnInit {
  private readonly imagingService = inject(ImagingService);
  private readonly hospitalService = inject(HospitalService);
  private readonly patientService = inject(PatientService);
  private readonly toast = inject(ToastService);
  private readonly roleContext = inject(RoleContextService);
  private readonly auth = inject(AuthService);
  private readonly translate = inject(TranslateService);

  orders = signal<ImagingOrderResponse[]>([]);
  filtered = signal<ImagingOrderResponse[]>([]);
  loading = signal(true);
  searchTerm = '';
  activeTab = signal<'all' | 'ordered' | 'completed' | 'cancelled'>('all');
  selectedOrder = signal<ImagingOrderResponse | null>(null);
  selectedOrderReports = signal<ImagingReportResponse[]>([]);

  hospitals = signal<HospitalResponse[]>([]);

  // Patient picker
  selectedPatient = signal<PatientResponse | null>(null);

  /* ── CRUD signals ── */
  showModal = signal(false);
  editing = signal(false);
  saving = signal(false);
  editId = '';
  form: ImagingForm = this.emptyForm();

  showDeleteConfirm = signal(false);
  deletingItem = signal<ImagingOrderResponse | null>(null);
  deleting = signal(false);

  /* ── Results state ── */
  view = signal<'orders' | 'results'>('orders');
  reports = signal<ImagingReportResponse[]>([]);
  reportsLoading = signal(false);
  reportStatusFilter = signal<ImagingReportStatus>('FINAL');
  reportModalityFilter = signal<ImagingModality | ''>('');
  criticalOnly = signal(false);
  selectedReport = signal<ImagingReportResponse | null>(null);
  reportLoading = signal(false);

  showStatusModal = signal(false);
  /** Separate from selectedReport so the status modal never drags the detail overlay open. */
  statusTarget = signal<ImagingReportResponse | null>(null);
  statusForm: { status: VoidReportStatus; statusReason: string } = {
    status: 'CANCELLED',
    statusReason: '',
  };
  statusSubmitting = signal(false);
  acknowledging = signal(false);

  /* ── Authoring state (Tier 2 item 26) ── */
  showReportModal = signal(false);
  reportSubmitting = signal(false);
  signing = signal(false);
  /** Set when revising an existing draft; null when authoring a new report. */
  reportEditId = signal<string | null>(null);
  reportOrderId = signal<string | null>(null);
  reportForm: ReportForm = this.emptyReportForm();

  /** Filter tabs on the Results view — reading by any status is fine. */
  readonly reportStatuses: ImagingReportStatus[] = [
    'DRAFT',
    'PRELIMINARY',
    'FINAL',
    'ADDENDUM',
    'CORRECTED',
    'AMENDED',
    'CANCELLED',
  ];

  /**
   * The statuses an author may assert. FINAL is absent because signing is the
   * only path to it, and CANCELLED/ERROR because those are administrative
   * outcomes that require a reason — both enforced backend-side too, so this
   * list is a convenience, not the gate.
   */
  readonly authorableStatuses = [
    'DRAFT',
    'PRELIMINARY',
    'ADDENDUM',
    'CORRECTED',
    'AMENDED',
  ] as const;

  /** What the void modal may write. */
  readonly voidStatuses: VoidReportStatus[] = ['CANCELLED', 'ERROR'];

  readonly canSeeResults = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_RADIOLOGIST',
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_SUPER_ADMIN',
  ]);
  /** Mirrors CREATE_RADIOLOGY_REPORTS / SIGN_IMAGING_REPORTS on the controller. */
  readonly canAuthorReports = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_RADIOLOGIST',
    'ROLE_SUPER_ADMIN',
  ]);
  readonly canUpdateReportStatus = this.canAuthorReports;
  /** RADIOLOGIST joins DOCTOR here — the endpoint has admitted the role since item 26. */
  readonly canAckCritical = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_RADIOLOGIST',
    'ROLE_SUPER_ADMIN',
  ]);

  modalities: ImagingModality[] = [
    'XRAY',
    'CT',
    'MRI',
    'ULTRASOUND',
    'PET',
    'MAMMOGRAPHY',
    'FLUOROSCOPY',
    'NUCLEAR_MEDICINE',
    'INTERVENTIONAL_RADIOLOGY',
    'DEXA',
    'OTHER',
  ];
  priorities: ImagingPriority[] = ['ROUTINE', 'URGENT', 'STAT'];
  lateralities: { value: ImagingLaterality; label: string }[] = [
    { value: 'LEFT', label: 'Left' },
    { value: 'RIGHT', label: 'Right' },
    { value: 'BILATERAL', label: 'Bilateral' },
    { value: 'MIDLINE', label: 'Midline' },
    { value: 'NOT_APPLICABLE', label: 'N/A' },
  ];

  ngOnInit(): void {
    this.load();
    this.loadAssignedHospitals();
  }

  emptyForm(): ImagingForm {
    return {
      patientId: '',
      hospitalId: '',
      modality: 'XRAY' as ImagingModality,
      studyType: '',
      priority: 'ROUTINE' as ImagingPriority,
      laterality: undefined,
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
    return h.length === 1 ? h[0].name : 'No hospital assigned';
  }

  get hospitalLocked(): boolean {
    return !this.roleContext.isSuperAdmin();
  }

  onPatientPicked(p: PatientResponse | null): void {
    this.selectedPatient.set(p);
    this.form.patientId = p?.id ?? '';
  }

  openCreate(): void {
    this.form = this.emptyForm();
    this.editing.set(false);
    this.editId = '';
    this.selectedPatient.set(null);
    // Re-apply locked hospital after emptyForm() reset
    if (this.hospitalLocked) {
      const h = this.hospitals();
      if (h.length === 1) this.form.hospitalId = h[0].id;
    }
    this.showModal.set(true);
  }

  openEdit(o: ImagingOrderResponse): void {
    this.form = {
      patientId: o.patientId,
      hospitalId: o.hospitalId ?? '',
      modality: o.modality ?? 'XRAY',
      studyType: o.studyType ?? '',
      bodyRegion: o.bodyRegion ?? '',
      priority: o.priority ?? 'ROUTINE',
      laterality: (o.laterality as ImagingLaterality) || '',
      clinicalQuestion: o.clinicalQuestion ?? '',
    };
    this.selectedPatient.set({
      id: o.patientId,
      firstName: o.patientDisplayName?.split(' ')[0] ?? '',
      lastName: o.patientDisplayName?.split(' ').slice(1).join(' ') ?? '',
      email: '',
      mrn: o.patientMrn,
    } as PatientResponse);
    this.editId = o.id;
    this.editing.set(true);
    this.showModal.set(true);
  }

  closeModal(): void {
    this.showModal.set(false);
  }

  submitForm(): void {
    this.saving.set(true);
    const payload: ImagingOrderRequest = {
      ...this.form,
      laterality: this.form.laterality || undefined,
    };
    const op = this.editing()
      ? this.imagingService.updateOrder(this.editId, payload)
      : this.imagingService.createOrder(payload);
    op.subscribe({
      next: () => {
        this.toast.success(this.editing() ? 'Order updated' : 'Order created');
        this.closeModal();
        this.saving.set(false);
        this.load();
      },
      error: () => {
        this.toast.error('Save failed');
        this.saving.set(false);
      },
    });
  }

  confirmCancel(o: ImagingOrderResponse): void {
    this.deletingItem.set(o);
    this.showDeleteConfirm.set(true);
  }
  cancelDeleteAction(): void {
    this.showDeleteConfirm.set(false);
    this.deletingItem.set(null);
  }
  executeCancel(): void {
    this.deleting.set(true);
    this.imagingService
      .updateOrderStatus(this.deletingItem()!.id, {
        status: 'CANCELLED',
        notes: 'Cancelled by admin',
      })
      .subscribe({
        next: () => {
          this.toast.success('Order cancelled');
          this.cancelDeleteAction();
          this.deleting.set(false);
          this.load();
        },
        error: () => {
          this.toast.error('Cancel failed');
          this.deleting.set(false);
        },
      });
  }

  load(): void {
    this.loading.set(true);
    this.imagingService.getAllOrders().subscribe({
      next: (list) => {
        this.orders.set(Array.isArray(list) ? list : []);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load imaging orders');
        this.loading.set(false);
      },
    });
  }

  setTab(tab: 'all' | 'ordered' | 'completed' | 'cancelled'): void {
    this.activeTab.set(tab);
    this.applyFilter();
  }

  applyFilter(): void {
    let list = this.orders();
    const tab = this.activeTab();
    if (tab === 'ordered')
      list = list.filter((o) => ['ORDERED', 'SCHEDULED', 'IN_PROGRESS'].includes(o.status));
    else if (tab === 'completed')
      list = list.filter((o) => ['COMPLETED', 'RESULTS_AVAILABLE'].includes(o.status));
    else if (tab === 'cancelled') list = list.filter((o) => o.status === 'CANCELLED');
    const term = this.searchTerm.toLowerCase().trim();
    if (term) {
      list = list.filter(
        (o) =>
          (o.patientDisplayName ?? '').toLowerCase().includes(term) ||
          (o.studyType ?? '').toLowerCase().includes(term) ||
          (o.bodyRegion ?? '').toLowerCase().includes(term) ||
          (o.modality ?? '').toLowerCase().includes(term),
      );
    }
    this.filtered.set(list);
  }

  viewDetail(o: ImagingOrderResponse): void {
    this.selectedOrder.set(o);
    this.selectedOrderReports.set([]);
    this.imagingService.getReportsForOrder(o.id).subscribe({
      next: (reports) => this.selectedOrderReports.set(reports ?? []),
      error: () => this.selectedOrderReports.set([]),
    });
  }
  closeDetail(): void {
    this.selectedOrder.set(null);
    this.selectedOrderReports.set([]);
  }

  openPacsViewer(url: string): void {
    if (!url) return;
    window.open(url, '_blank', 'noopener,noreferrer');
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'ORDERED':
      case 'SCHEDULED':
        return 'status-ordered';
      case 'IN_PROGRESS':
        return 'status-progress';
      case 'COMPLETED':
      case 'RESULTS_AVAILABLE':
        return 'status-completed';
      case 'DRAFT':
      case 'PENDING_AUTHORIZATION':
        return 'status-preliminary';
      case 'CANCELLED':
        return 'status-cancelled';
      default:
        return '';
    }
  }

  getPriorityClass(priority: string): string {
    switch (priority) {
      case 'STAT':
        return 'priority-stat';
      case 'URGENT':
        return 'priority-urgent';
      case 'ROUTINE':
        return 'priority-routine';
      default:
        return '';
    }
  }

  readonly activeOrderCount = computed(
    () =>
      this.orders().filter((o) => ['ORDERED', 'SCHEDULED', 'IN_PROGRESS'].includes(o.status))
        .length,
  );
  readonly completedOrderCount = computed(
    () => this.orders().filter((o) => ['COMPLETED', 'RESULTS_AVAILABLE'].includes(o.status)).length,
  );

  /* ── Results ── */

  setView(view: 'orders' | 'results'): void {
    this.view.set(view);
    if (view === 'results' && this.reports().length === 0) {
      this.loadReports();
    }
  }

  private resultsHospitalId(): string | null {
    return this.roleContext.activeHospitalId ?? this.auth.getHospitalId();
  }

  loadReports(): void {
    const hospitalId = this.resultsHospitalId();
    if (!hospitalId) return;
    this.reportsLoading.set(true);
    // Backend ignores `modality` whenever `status` is present, so fetch by
    // status only and apply the modality filter client-side (visibleReports).
    this.imagingService
      .getReportsByHospital(hospitalId, { status: this.reportStatusFilter() })
      .subscribe({
        next: (list) => {
          this.reports.set(Array.isArray(list) ? list : []);
          this.reportsLoading.set(false);
        },
        error: () => {
          this.toast.error(this.translate.instant('IMAGING.RESULTS_LOAD_ERROR'));
          this.reportsLoading.set(false);
        },
      });
  }

  setReportStatusFilter(status: ImagingReportStatus): void {
    this.reportStatusFilter.set(status);
    this.loadReports();
  }

  onReportModalityChange(modality: string): void {
    this.reportModalityFilter.set(modality as ImagingModality | '');
  }

  toggleCriticalOnly(): void {
    this.criticalOnly.update((v) => !v);
  }

  readonly visibleReports = computed(() => {
    let list = this.reports();
    const modality = this.reportModalityFilter();
    if (modality) list = list.filter((r) => r.modality === modality);
    return this.criticalOnly() ? list.filter((r) => this.isCritical(r)) : list;
  });

  isCritical(report: ImagingReportResponse): boolean {
    return !!report.criticalResultFlaggedAt;
  }

  isCriticalUnacked(report: ImagingReportResponse): boolean {
    return !!report.criticalResultFlaggedAt && !report.criticalResultAcknowledgedAt;
  }

  openReport(report: ImagingReportResponse): void {
    this.selectedReport.set(report);
  }

  closeReport(): void {
    this.selectedReport.set(null);
  }

  /** Open the latest report for an order (from the orders table/detail). */
  viewReportForOrder(order: ImagingOrderResponse): void {
    this.reportLoading.set(true);
    this.imagingService.getLatestReportByOrder(order.id).subscribe({
      next: (report) => {
        this.reportLoading.set(false);
        this.selectedOrder.set(null);
        this.selectedReport.set(report);
      },
      error: () => {
        this.reportLoading.set(false);
        this.toast.error(this.translate.instant('IMAGING.NO_REPORT_FOR_ORDER'));
      },
    });
  }

  /** The server stamps the authenticated caller; no staff id travels. */
  acknowledgeCritical(report: ImagingReportResponse): void {
    this.acknowledging.set(true);
    this.imagingService.acknowledgeCriticalReport(report.id).subscribe({
      next: (updated) => {
        this.toast.success(this.translate.instant('IMAGING.CRITICAL_ACKED'));
        this.acknowledging.set(false);
        this.selectedReport.set(updated);
        this.loadReports();
      },
      error: () => {
        this.toast.error(this.translate.instant('IMAGING.CRITICAL_ACK_ERROR'));
        this.acknowledging.set(false);
      },
    });
  }

  /* ── Authoring (Tier 2 item 26) ── */

  /**
   * Author a report against an order. A study that already carries a signed
   * read may only receive a revision, so the form opens pre-set to ADDENDUM
   * and the backend enforces the same rule.
   */
  openAuthorReport(order: ImagingOrderResponse, existingSigned = false): void {
    this.reportForm = {
      reportTitle: order.studyType ?? '',
      modality: order.modality,
      bodyRegion: order.bodyRegion ?? '',
      accessionNumber: '',
      technique: '',
      comparisonStudies: '',
      findings: '',
      impression: '',
      recommendations: '',
      contrastAdministered: false,
      contrastDetails: '',
      criticalFinding: false,
      reportStatus: existingSigned ? 'ADDENDUM' : 'PRELIMINARY',
    };
    this.reportOrderId.set(order.id);
    this.reportEditId.set(null);
    this.showReportModal.set(true);
  }

  /** Revise an unsigned draft. Signed reports are read-only by contract. */
  openEditReport(report: ImagingReportResponse): void {
    if (report.signed) {
      this.toast.error(this.translate.instant('IMAGING.REPORT_LOCKED'));
      return;
    }
    this.reportForm = {
      reportTitle: report.reportTitle ?? '',
      modality: report.modality,
      bodyRegion: report.bodyRegion ?? '',
      accessionNumber: report.accessionNumber ?? '',
      technique: report.technique ?? '',
      comparisonStudies: report.comparisonStudies ?? '',
      findings: report.findings ?? '',
      impression: report.impression ?? '',
      recommendations: report.recommendations ?? '',
      contrastAdministered: !!report.contrastAdministered,
      contrastDetails: '',
      criticalFinding: report.criticalFinding,
      reportStatus: this.authorableStatusOf(report.reportStatus),
    };
    this.reportOrderId.set(report.imagingOrderId);
    this.reportEditId.set(report.id);
    this.showReportModal.set(true);
  }

  closeReportModal(): void {
    this.showReportModal.set(false);
    this.reportEditId.set(null);
    this.reportOrderId.set(null);
  }

  submitReport(): void {
    const orderId = this.reportOrderId();
    if (!orderId) return;
    const editId = this.reportEditId();
    const payload: ImagingReportAuthorRequest = {
      ...this.reportForm,
      bodyRegion: this.reportForm.bodyRegion.trim() || undefined,
      accessionNumber: this.reportForm.accessionNumber.trim() || undefined,
      technique: this.reportForm.technique.trim() || undefined,
      comparisonStudies: this.reportForm.comparisonStudies.trim() || undefined,
      findings: this.reportForm.findings.trim() || undefined,
      impression: this.reportForm.impression.trim() || undefined,
      recommendations: this.reportForm.recommendations.trim() || undefined,
      contrastDetails: this.reportForm.contrastDetails.trim() || undefined,
      reportTitle: this.reportForm.reportTitle.trim() || undefined,
      imagingOrderId: orderId,
    };

    this.reportSubmitting.set(true);
    const request$ = editId
      ? this.imagingService.updateReport(editId, payload)
      : this.imagingService.createReport(payload);

    request$.subscribe({
      next: (saved) => {
        this.toast.success(
          this.translate.instant(editId ? 'IMAGING.REPORT_UPDATED' : 'IMAGING.REPORT_CREATED'),
        );
        this.reportSubmitting.set(false);
        this.closeReportModal();
        this.selectedReport.set(saved);
        this.loadReports();
      },
      error: () => {
        this.toast.error(this.translate.instant('IMAGING.REPORT_SAVE_ERROR'));
        this.reportSubmitting.set(false);
      },
    });
  }

  /**
   * Sign the open report. Signing is irreversible and closes the report to
   * edits, so it asks first — the backend refuses a re-sign outright and the
   * only way back is a corrected version.
   */
  signReport(report: ImagingReportResponse): void {
    if (!window.confirm(this.translate.instant('IMAGING.SIGN_CONFIRM'))) return;
    this.signing.set(true);
    this.imagingService.signReport(report.id).subscribe({
      next: (signed) => {
        this.toast.success(this.translate.instant('IMAGING.REPORT_SIGNED'));
        this.signing.set(false);
        this.selectedReport.set(signed);
        this.loadReports();
      },
      error: () => {
        this.toast.error(this.translate.instant('IMAGING.REPORT_SIGN_ERROR'));
        this.signing.set(false);
      },
    });
  }

  /**
   * The form only offers statuses an author may assert. A report already at
   * FINAL or a terminal state has no authorable equivalent, so it falls back
   * to PRELIMINARY rather than sending a value the backend will refuse.
   */
  private authorableStatusOf(status: ImagingReportStatus): AuthorableReportStatus {
    return (this.authorableStatuses as readonly string[]).includes(status)
      ? (status as AuthorableReportStatus)
      : 'PRELIMINARY';
  }

  /* ── Administrative void ── */

  openStatusUpdate(report: ImagingReportResponse): void {
    this.statusTarget.set(report);
    this.statusForm = { status: 'CANCELLED', statusReason: '' };
    this.showStatusModal.set(true);
  }

  closeStatusUpdate(): void {
    this.showStatusModal.set(false);
    this.statusTarget.set(null);
  }

  submitStatusUpdate(): void {
    const report = this.statusTarget();
    if (!report) return;
    const reason = this.statusForm.statusReason.trim();
    // Mirrors the backend rule rather than letting the server bounce it: a
    // voided radiology report with no account of why is a hole in the chart.
    if (!reason) {
      this.toast.error(this.translate.instant('IMAGING.VOID_REASON_REQUIRED'));
      return;
    }
    this.statusSubmitting.set(true);
    this.imagingService
      .updateReportStatus(report.id, {
        status: this.statusForm.status,
        statusReason: reason,
      })
      .subscribe({
        next: (updated) => {
          this.toast.success(this.translate.instant('IMAGING.STATUS_UPDATED'));
          this.statusSubmitting.set(false);
          this.closeStatusUpdate();
          if (this.selectedReport()?.id === updated.id) {
            this.selectedReport.set(updated);
          }
          this.loadReports();
        },
        error: () => {
          this.toast.error(this.translate.instant('IMAGING.STATUS_UPDATE_ERROR'));
          this.statusSubmitting.set(false);
        },
      });
  }

  private emptyReportForm(): ReportForm {
    return {
      reportTitle: '',
      modality: 'XRAY',
      bodyRegion: '',
      accessionNumber: '',
      technique: '',
      comparisonStudies: '',
      findings: '',
      impression: '',
      recommendations: '',
      contrastAdministered: false,
      contrastDetails: '',
      criticalFinding: false,
      reportStatus: 'PRELIMINARY',
    };
  }

  /** An order can only be read once the study has actually been acquired. */
  canAuthorForOrder(order: ImagingOrderResponse): boolean {
    return (
      this.canAuthorReports &&
      (order.status === 'COMPLETED' ||
        order.status === 'RESULTS_AVAILABLE' ||
        order.status === 'IN_PROGRESS')
    );
  }

  getReportStatusClass(status: string): string {
    switch (status) {
      case 'FINAL':
      case 'ADDENDUM':
        return 'status-completed';
      case 'PRELIMINARY':
      case 'DRAFT':
        return 'status-preliminary';
      case 'CORRECTED':
      case 'AMENDED':
        return 'status-progress';
      case 'CANCELLED':
      case 'ERROR':
        return 'status-cancelled';
      default:
        return '';
    }
  }
}
